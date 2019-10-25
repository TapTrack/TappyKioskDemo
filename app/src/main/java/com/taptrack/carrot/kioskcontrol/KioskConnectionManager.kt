package com.taptrack.carrot.kioskcontrol

import android.nfc.NdefMessage
import android.os.Handler
import android.os.Looper
import com.taptrack.tcmptappy2.Tappy
import com.taptrack.tcmptappy2.commandfamilies.systemfamily.commands.ConfigureKioskModeCommand
import com.taptrack.tcmptappy2.commandfamilies.systemfamily.commands.PingCommand
import timber.log.Timber
import java.util.*

sealed class TappyKioskConfiguration {

    data class HeartbeatTappy constructor(
            // the max tolerance before the kiosk is considered in an error state
            val maxHeartbeatReceiveToleranceErrSec: Long,
            // the intervals to send heartbeats at
            val sendIntervalSec: Int,
            // the max tolerance before the kiosk should initiate a disconnect/reconnect
            // cycle
            val maxReceiveToleranceBeforeDiscSec: Long): TappyKioskConfiguration() {

        constructor() : this(DEF_HB_REC_TOLERANCE_SEC, DEF_HB_SEND_INTERVAL_SEC, DEF_HB_DISCONNECT_TOLERANCE_SEC)


        companion object {
            val DEF_HB_REC_TOLERANCE_SEC = 10L
            val DEF_HB_SEND_INTERVAL_SEC = 15
            val DEF_HB_DISCONNECT_TOLERANCE_SEC = 30L
        }
    }

}

sealed class KioskTappyEvent {
    object RequestedClose : KioskTappyEvent()
    object RequestedInitialize : KioskTappyEvent()
    object SendHeartbeat : KioskTappyEvent()
    object HeartbeatFailed : KioskTappyEvent()
    object HeartbeatGood : KioskTappyEvent()
    object HeartbeatDrivenClose : KioskTappyEvent()
    object ErrorDrivenClose : KioskTappyEvent()
    object TappyDisconnected : KioskTappyEvent()
    object CheckHeartbeat : KioskTappyEvent()

    data class NewTappyStatus(val status: Int, val time:Long): KioskTappyEvent()
    data class ReceivedPing(val time:Long): KioskTappyEvent()
    data class ReceivedNdef(val tagCode: ByteArray, val tagType: Byte, val ndef: NdefMessage, val time:Long): KioskTappyEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ReceivedNdef

            if (!Arrays.equals(tagCode, other.tagCode)) return false
            if (tagType != other.tagType) return false
            if (ndef != other.ndef) return false
            if (time != other.time) return false

            return true
        }

        override fun hashCode(): Int {
            var result = Arrays.hashCode(tagCode)
            result = 31 * result + tagType
            result = 31 * result + ndef.hashCode()
            result = 31 * result + time.hashCode()
            return result
        }
    }

    data class ReceivedTag(val tagCode: ByteArray, val tagType: Byte, val time:Long): KioskTappyEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ReceivedTag

            if (!Arrays.equals(tagCode, other.tagCode)) return false
            if (tagType != other.tagType) return false
            if (time != other.time) return false

            return true
        }

        override fun hashCode(): Int {
            var result = Arrays.hashCode(tagCode)
            result = 31 * result + tagType
            result = 31 * result + time.hashCode()
            return result
        }
    }

    data class ReceivedUnexpectedResponse(val time:Long): KioskTappyEvent()
}

class KioskConnectionManager constructor(
        private val tappy: Tappy,
        private val config: TappyKioskConfiguration,
        private val opsLooper: Looper,
        private val notifyLooper: Looper): KioskManager {

    private val cbs: KioskCallbacks = KioskCallbacks(this, notifyLooper)
    private val resolver: KioskResolver = KioskResolver

    private val heartbeatHandler = Handler(opsLooper)
    private val eventHandler = Handler(opsLooper)

    private val eventProcessingLock = Any()

    @Volatile
    private var kioskStatus: KioskManager.Status = KioskManager.Status.UNINITIALIZED
    private var lastMessageReceived: Long = 0
    private var tappyReadyAt: Long = 0
    private var hasInitialized = false
    private var shouldBeClosing = false

    private val pingCommand = PingCommand()

    private val statsListener: Tappy.StatusListener = Tappy.StatusListener {
        enqueueEvent(KioskTappyEvent.NewTappyStatus(it, System.currentTimeMillis()))
    }

    private val msgListener: Tappy.ResponseListener = Tappy.ResponseListener {
        val resolved = KioskResolver.resolve(it)
        when (resolved) {
            is ResolvedMessage.Ndef -> {
                enqueueEvent(event = KioskTappyEvent.ReceivedNdef(resolved.uid, resolved.tagType, resolved.ndef, resolved.receivedAt))
            }
            is ResolvedMessage.Ping -> {
                enqueueEvent(event = KioskTappyEvent.ReceivedPing(resolved.receivedAt))
            }
            is ResolvedMessage.Tag -> {
                enqueueEvent(event = KioskTappyEvent.ReceivedTag(resolved.uid, resolved.tagType, resolved.receivedAt))
            }
            is ResolvedMessage.UnexpectedResponse -> {
                enqueueEvent(event = KioskTappyEvent.ReceivedUnexpectedResponse(resolved.receivedAt))
            }
            else -> {

            }
        }
    }

    private val hbCheckRunnable = Runnable {
        enqueueEvent(KioskTappyEvent.CheckHeartbeat)
    }

    private val hbSendRunnable = Runnable {
        enqueueEvent(KioskTappyEvent.SendHeartbeat)
    }

    private fun enqueueEvent(event: KioskTappyEvent) {
        eventHandler.post {
            processEvent(event)
        }
    }

    private fun processEvent(event: KioskTappyEvent) {
        var statusToNotify: KioskManager.Status? = null
        var nextEvent: KioskTappyEvent? = null

        // There are a lot of 'else {}' statements in the below
        // code. This is because Kotlin doesn't like if's without
        // an else in this block
        synchronized(eventProcessingLock) {
            when (event) {
                is KioskTappyEvent.ReceivedPing -> {
                    Timber.v("Received ping from Tappy %s",tappy.deviceDescription)
                    lastMessageReceived = (event.time)
                    if (config is TappyKioskConfiguration.HeartbeatTappy) {
                        nextEvent = KioskTappyEvent.CheckHeartbeat
                        cbs.notifyOfNewHeartbeatReceived(event.time)
                    } else {}
                }
                is KioskTappyEvent.ReceivedTag -> {
                    Timber.v("Received tag scan from Tappy %s",tappy.deviceDescription)
                    lastMessageReceived = (event.time)
                    cbs.notifyOfNewTagRead(event.tagCode,event.tagType)
                }
                is KioskTappyEvent.ReceivedNdef -> {
                    Timber.v("Received ndef %s from Tappy %s",event.ndef,tappy.deviceDescription)
                    lastMessageReceived = (event.time)
                    cbs.notifyOfNewNdefRead(event.tagCode,event.tagType,event.ndef)
                }
                is KioskTappyEvent.SendHeartbeat -> {
                    if (config is TappyKioskConfiguration.HeartbeatTappy) {
                        if (tappy.latestStatus == Tappy.STATUS_READY && hasInitialized && !shouldBeClosing) {
                            Timber.v("Sending heartbeat to Tappy %s",tappy.deviceDescription)
                            tappy.sendMessage(pingCommand)
                            heartbeatHandler.removeCallbacks(hbSendRunnable)
                            heartbeatHandler.postDelayed(hbSendRunnable,config.sendIntervalSec *1000L)
                        } else {
                            Timber.v("Not sending heartbeat, device not ready %s",tappy.deviceDescription)
                        }
                    } else {}
                }
                is KioskTappyEvent.CheckHeartbeat -> {
                    if (config is TappyKioskConfiguration.HeartbeatTappy) {
                        val time = System.currentTimeMillis()

                        val hbToleranceErrMs = config.maxHeartbeatReceiveToleranceErrSec*1000L
                        val hbToleranceDiscMs = config.maxReceiveToleranceBeforeDiscSec*1000L
                        // check if we have an unhealthy heartbeat
                        val hasBeenConnectedLongEnoughToHaveUnhealthyHb = (time - tappyReadyAt) > hbToleranceErrMs
                        val hasUnhealthyHeartbeatInterval = (time - lastMessageReceived) > hbToleranceErrMs

                        // check if our heartbeat is so unhealthy we should disconnect
                        val hasBeenConnectedLongEnoughToHaveDisconnectHb = (time - tappyReadyAt) > hbToleranceDiscMs
                        val hasDisconnectableHeartbeatInterval = (time - lastMessageReceived) > hbToleranceDiscMs

                        var timeToCheckNext = 500L
                        if (hasBeenConnectedLongEnoughToHaveDisconnectHb && hasDisconnectableHeartbeatInterval) {
                            nextEvent = (KioskTappyEvent.HeartbeatDrivenClose)
                        } else if (hasBeenConnectedLongEnoughToHaveUnhealthyHb && hasUnhealthyHeartbeatInterval) {
                            nextEvent = KioskTappyEvent.HeartbeatFailed
                        } else if (hasBeenConnectedLongEnoughToHaveUnhealthyHb && !hasUnhealthyHeartbeatInterval) {
                            timeToCheckNext = hbToleranceErrMs - (time-lastMessageReceived)
                            nextEvent = KioskTappyEvent.HeartbeatGood
                        } else if (!hasBeenConnectedLongEnoughToHaveDisconnectHb && !hasUnhealthyHeartbeatInterval) {
                            nextEvent = KioskTappyEvent.HeartbeatGood
                        } else {}

                        heartbeatHandler.removeCallbacks(hbCheckRunnable)
                        heartbeatHandler.postDelayed(hbCheckRunnable,timeToCheckNext)
                    } else {}
                }
                is KioskTappyEvent.NewTappyStatus -> {
                    Timber.v("Status for Tappy %s: %d",tappy.deviceDescription,event.status)
                    when (event.status) {
                        Tappy.STATUS_CONNECTING -> {
                            if (shouldBeClosing) {
                                tappy.close()
                            } else {

                            }
                        }
                        Tappy.STATUS_READY -> {
                            tappyReadyAt = System.currentTimeMillis()
                            if (shouldBeClosing) {
                                tappy.close()
                            } else {
                                if (config is TappyKioskConfiguration.HeartbeatTappy) {
                                    heartbeatHandler.removeCallbacks(hbCheckRunnable)
                                    heartbeatHandler.postDelayed(hbCheckRunnable,50)

                                    heartbeatHandler.removeCallbacks(hbSendRunnable)
                                    heartbeatHandler.postDelayed(hbSendRunnable,16)
                                } else {}
                            }
                        }
                        Tappy.STATUS_DISCONNECTED -> {
                            nextEvent = (KioskTappyEvent.TappyDisconnected)
                        }
                        Tappy.STATUS_DISCONNECTING -> {
                            if (!shouldBeClosing) {
                                nextEvent = (KioskTappyEvent.ErrorDrivenClose)
                            } else {}
                        }
                        Tappy.STATUS_CLOSED -> {
                            if (!shouldBeClosing) {
                                nextEvent = (KioskTappyEvent.ErrorDrivenClose)
                            } else {
                                statusToNotify = KioskManager.Status.CLOSED
                                kioskStatus = KioskManager.Status.CLOSED
                            }
                        }
                        Tappy.STATUS_ERROR -> {
                            nextEvent = (KioskTappyEvent.ErrorDrivenClose)
                        }
                        else -> {
                        }
                    }
                }
                is KioskTappyEvent.RequestedInitialize -> {
                    if (!hasInitialized && !shouldBeClosing) {
                        hasInitialized = true

                        if (kioskStatus != KioskManager.Status.INITIALIZING) {
                            statusToNotify = KioskManager.Status.INITIALIZING
                            kioskStatus = KioskManager.Status.INITIALIZING
                        }
                        tappy.registerResponseListener(msgListener)
                        tappy.registerStatusListener(statsListener)
                        if(!tappy.connect()) {
                            nextEvent = (KioskTappyEvent.ErrorDrivenClose)
                        } else {
                            if (config is TappyKioskConfiguration.HeartbeatTappy) {
                                val configurationMsg = ConfigureKioskModeCommand(
                                        ConfigureKioskModeCommand.PollingSettings.NO_CHANGE,
                                        ConfigureKioskModeCommand.NdefSettings.NO_CHANGE,
                                        config.sendIntervalSec,
                                        ConfigureKioskModeCommand.ScanErrorSettings.NO_CHANGE
                                )
                                tappy.sendMessage(configurationMsg)
                            } else {}
                        }
                    } else {}
                }
                is KioskTappyEvent.ErrorDrivenClose -> {
                    // this goes optimistically because it has no other option
                    if (!shouldBeClosing) {
                        shouldBeClosing = true
                    }
                    heartbeatHandler.removeCallbacks(hbSendRunnable)
                    heartbeatHandler.removeCallbacks(hbCheckRunnable)
                    tappy.close()
                    statusToNotify = KioskManager.Status.CLOSED
                    kioskStatus = KioskManager.Status.CLOSED
                }
                is KioskTappyEvent.TappyDisconnected,
                is KioskTappyEvent.HeartbeatDrivenClose,
                is KioskTappyEvent.RequestedClose -> {
                    if (!shouldBeClosing) {
                        shouldBeClosing = true
                        if (kioskStatus != KioskManager.Status.CLOSING && kioskStatus != KioskManager.Status.CLOSED) {
                            statusToNotify = KioskManager.Status.CLOSING
                            kioskStatus = KioskManager.Status.CLOSING
                        } else {}

                        heartbeatHandler.removeCallbacks(hbSendRunnable)
                        heartbeatHandler.removeCallbacks(hbCheckRunnable)

                        if (hasInitialized) {
                            tappy.close()
                        } else {}
                    } else {} // already closing, dont need to do anything
                }
                is KioskTappyEvent.HeartbeatFailed -> {
                    if (kioskStatus == KioskManager.Status.HEALTHY
                            || kioskStatus == KioskManager.Status.HEARTBEAT_FAILURE
                            || kioskStatus == KioskManager.Status.INITIALIZING) {
                        // sending this when healthy is kinda redundant, but it potentially lets
                        // the app know we still are alive
                        statusToNotify = KioskManager.Status.HEARTBEAT_FAILURE
                        kioskStatus = KioskManager.Status.HEARTBEAT_FAILURE
                    } else {}
                }
                is KioskTappyEvent.HeartbeatGood -> {
                    if (kioskStatus == KioskManager.Status.HEARTBEAT_FAILURE
                            || kioskStatus == KioskManager.Status.INITIALIZING
                            || kioskStatus == KioskManager.Status.HEALTHY) {
                        // sending this when healthy is kinda redundant, but it potentially lets
                        // the app know we still are alive
                        statusToNotify = KioskManager.Status.HEALTHY
                        kioskStatus = KioskManager.Status.HEALTHY
                    } else {}
                }
                is KioskTappyEvent.ReceivedUnexpectedResponse -> {
                    Timber.v("Received unexpected response from Tappy %s",tappy.deviceDescription)
                }
            }
        }

        val localStatus = statusToNotify // kotlin requires this
        if (localStatus != null) {
            newStatus(localStatus)
        }

        val localNextEvent = nextEvent
        if (localNextEvent != null) {
            enqueueEvent(localNextEvent)
        }
    }

    private fun newStatus(status: KioskManager.Status) {
        cbs.notifyOfNewStatus(status)
    }

    override fun initialize() {
        Timber.v("Initialization request for Tappy: %s",tappy.deviceDescription)
        enqueueEvent(event = KioskTappyEvent.RequestedInitialize)
    }

    override fun close() {
        Timber.v("Close request for Tappy: %s",tappy.deviceDescription)
        enqueueEvent(event = KioskTappyEvent.RequestedClose)
    }

    override fun getStatus(): KioskManager.Status {
        return kioskStatus
    }

    override fun registerStatusListener(listener: KioskManager.ManagerStatusListener) {
        cbs.registerStatusListener(listener)
    }

    override fun unregisterStatusListener(listener: KioskManager.ManagerStatusListener) {
        cbs.unregisterStatusListener(listener)
    }

    override fun registerResponseListener(listener: KioskManager.KioskResponseListener) {
        cbs.registerKioskResponseListener(listener)
    }

    override fun unregisterResponseListener(listener: KioskManager.KioskResponseListener) {
        cbs.unregisterKioskResponseListener(listener)
    }


}