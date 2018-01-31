package com.taptrack.carrot.kioskcontrol

import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.*
import com.taptrack.carrot.CarrotApplication
import com.taptrack.carrot.MainActivity
import com.taptrack.carrot.R
import com.taptrack.carrot.getCarrotApplication
import com.taptrack.tcmptappy.tcmp.MalformedPayloadException
import com.taptrack.tcmptappy2.MessageResolver
import com.taptrack.tcmptappy2.MessageResolverMux
import com.taptrack.tcmptappy2.TCMPMessage
import com.taptrack.tcmptappy2.ble.TappyBle
import com.taptrack.tcmptappy2.ble.TappyBleDeviceDefinition
import com.taptrack.tcmptappy2.commandfamilies.basicnfc.BasicNfcCommandResolver
import com.taptrack.tcmptappy2.commandfamilies.basicnfc.responses.NdefFoundResponse
import com.taptrack.tcmptappy2.commandfamilies.basicnfc.responses.TagFoundResponse
import com.taptrack.tcmptappy2.commandfamilies.systemfamily.SystemCommandResolver
import io.reactivex.disposables.Disposable
import timber.log.Timber
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.concurrent.read
import kotlin.concurrent.write

interface ControlledKiosksListener {
    fun controlledKiosksChanged(newCollection: Collection<TappyKiosk>)
}

private sealed class ActiveTappyKiosk {
    data class TappyBleKiosk(
            val tappy: TappyBle,
            val manager: KioskManager,
            val tappyKiosk: TappyKiosk,
            val managerStatusListener: KioskManager.ManagerStatusListener,
            val responseListener: KioskManager.KioskResponseListener) : ActiveTappyKiosk() {
    }
}

class TappyKioskService: Service() {
    private var mtHandler = Handler(Looper.getMainLooper())

    private val connectionListenerSet = HashSet<ControlledKiosksListener>()
    private val connectionListenerLock = ReentrantReadWriteLock()

    private var autolaunchDisposable: Disposable? = null
    private var shouldAutolaunch: Boolean = false
    private var lastLaunched = 0.toLong()

    private var heartBeatDisposable: Disposable? = null


    private val bleDesiredRwLock = ReentrantReadWriteLock()
    private val desiredTappyBles = HashMap<String,TappyBleDeviceDefinition>()

    private val bleManagerMapRwLock = ReentrantReadWriteLock()
    private val tappyBleManagers = HashMap<String, ActiveTappyKiosk.TappyBleKiosk>()

    private val currentControllableConnectionsRef = AtomicReference<List<TappyKiosk>>(emptyList())

    private var wakeLock: PowerManager.WakeLock? = null

    private val broadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(ACTION_DISCONNECT_ALL_TAPPIES == intent?.action) {
                bleDesiredRwLock.write { desiredTappyBles.clear() }

                syncDesiredActiveTappyBle()
                updateForegroundState()
            }
        }
    }

    inner class TappyKioskServiceBinder : Binder() {
        fun registerKioskControlListener(listener: ControlledKiosksListener, sendCurrent: Boolean) {
            connectionListenerLock.write {
                connectionListenerSet.add(listener)
            }
            if(sendCurrent) {
                listener.controlledKiosksChanged(getCurrentConnections())
            }
        }

        fun unregisterKioskControlListener(listener: ControlledKiosksListener) {
            connectionListenerLock.write {
                connectionListenerSet.remove(listener)
            }
        }

        fun requestConnectToTappyBle(tappyBleDeviceDefinition: TappyBleDeviceDefinition) {
            connectTappyBle(tappyBleDeviceDefinition)
        }

    }

    val binder = TappyKioskServiceBinder()

    private fun getCurrentConnections(): Collection<TappyKiosk> {
        return currentControllableConnectionsRef.get()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        wakeLock?.setReferenceCounted(false)

        autolaunchDisposable = getCarrotApplication().getAutolaunchEnabled()
                .subscribe {
                    shouldAutolaunch = it
                }

        val filter = IntentFilter(ACTION_DISCONNECT_ALL_TAPPIES)
        registerReceiver(broadcastReceiver,filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        autolaunchDisposable?.dispose()
        heartBeatDisposable?.dispose()

        unregisterReceiver(broadcastReceiver)
        wakeLock?.release()
    }

    private fun connectTappyBle(definition: TappyBleDeviceDefinition) {
        var didChange = false
        bleDesiredRwLock.write {
            if(!desiredTappyBles.containsKey(definition.address)) {
                desiredTappyBles.put(definition.address,definition)
                didChange = true
            }
        }

        if (didChange) {
            updateForegroundState()
            syncDesiredActiveTappyBle()
        }
    }

    private fun removeTappyBle(definition: TappyBleDeviceDefinition) {
        var didChange = false
        bleDesiredRwLock.write {
            if(desiredTappyBles.containsKey(definition.address)) {
                desiredTappyBles.remove(definition.address)
                didChange = true
            }
        }

        if (didChange) {
            updateForegroundState()
            syncDesiredActiveTappyBle()
        }
    }

    private fun syncDesiredActiveTappyBle() {
        var didUpdate = false
        bleDesiredRwLock.read {
            val desiredAddresses = desiredTappyBles.keys
            bleManagerMapRwLock.write {
                val managerAddresses = tappyBleManagers.keys
                val toRemove = managerAddresses.minus(desiredAddresses)
                val toAdd = desiredAddresses.minus(managerAddresses)

                for ( addr in toAdd ) {
                    val defn = desiredTappyBles[addr] ?: throw IllegalStateException()
                    val tappy = TappyBle.getTappyBle(this,defn)
                    val manager = KioskConnectionManager(
                        tappy = tappy,
                        config = TappyKioskConfiguration.HeartbeatTappy(),
                            notifyLooper = Looper.getMainLooper(),
                            opsLooper = Looper.getMainLooper()
                    )

                    val control= object: TappyKiosk {
                        val statusListeners = HashSet<KioskStatusListener>()
                        val heartbeatListeners = HashSet<KioskHeartbeatListener>()
                        val listenerRwLock = ReentrantReadWriteLock()
                        val lastKioskStatusRef = AtomicReference<KioskStatus>(KioskStatus.Unknown)

                        override fun getId(): String = defn.address

                        override fun getName(): String = defn.name

                        override fun requestClose() {
                            removeTappyBle(defn)
                        }

                        fun updateStatus(managerStatus: KioskManager.Status) {
                            val kioskStatus = when(managerStatus) {
                                KioskManager.Status.UNINITIALIZED -> KioskStatus.Unknown
                                KioskManager.Status.INITIALIZING -> KioskStatus.Connecting
                                KioskManager.Status.HEALTHY -> KioskStatus.HeartbeatGood
                                KioskManager.Status.HEARTBEAT_FAILURE -> KioskStatus.HeartbeatFailure
                                KioskManager.Status.CLOSING -> KioskStatus.Disconnecting
                                KioskManager.Status.CLOSED -> KioskStatus.Closed
                            }

                            val lastStatus = lastKioskStatusRef.getAndSet(kioskStatus)
                            if (lastStatus != kioskStatus) {
                                notifyStatusListeners(kioskStatus)
                            }
                        }

                        private fun notifyStatusListeners(status: KioskStatus) {
                            listenerRwLock.read {
                                statusListeners.forEach {
                                    it.onKioskStatusUpdate(status)
                                }
                            }
                        }

                        override fun addStatusListener(kioskStatusListener: KioskStatusListener) {
                            listenerRwLock.write {
                                statusListeners.add(kioskStatusListener)
                            }
                            kioskStatusListener.onKioskStatusUpdate(lastKioskStatusRef.get())
                        }

                        override fun removeStatusListener(kioskStatusListener: KioskStatusListener) {
                            listenerRwLock.write {
                                statusListeners.remove(kioskStatusListener)
                            }
                        }


                        fun heartbeatReceived(timeReceived: Long) {
                            listenerRwLock.read {
                                heartbeatListeners.forEach {
                                    it.onHeartbeatReceived(timeReceived)
                                }
                            }
                        }

                        override fun removeHeartbeatListener(heartbeatListener: KioskHeartbeatListener) {
                            listenerRwLock.write {
                                heartbeatListeners.remove(heartbeatListener)
                            }
                        }

                        override fun addHeartbeatListener(heartbeatListener: KioskHeartbeatListener) {
                            listenerRwLock.write {
                                heartbeatListeners.add(heartbeatListener)
                            }
                        }

                    }

                    val statusListener = object: KioskManager.ManagerStatusListener {
                        override fun onNewKioskStatus(kioskStatus: KioskManager.Status) {
                            control.updateStatus(kioskStatus)
                            when(kioskStatus) {
                                KioskManager.Status.CLOSED -> {
                                    bleManagerMapRwLock.write {
                                        managerDidClose(addr)
                                    }
                                    syncDesiredActiveTappyBle()
                                }
                                else -> {}
                            }
                        }
                    }

                    val kioskResponseListener = object: KioskManager.KioskResponseListener {
                        override fun onHeartbeatReceived(time: Long) {
                            control.heartbeatReceived(time)
                        }

                        override fun onNdefRead(uid: ByteArray,tagType: Byte, ndef: NdefMessage) {
                            if(shouldAutolaunch) {
                                throttleAndLaunch(ndef)
                            }
                            broadcastNdefFound(uid,tagType,ndef)
                        }

                        override fun onTagRead(uid: ByteArray, tagType: Byte) {
                            broadcastTagFound(uid,tagType)
                        }

                    }

                    manager.registerResponseListener(kioskResponseListener)
                    manager.registerStatusListener(statusListener)

                    val trio = ActiveTappyKiosk.TappyBleKiosk(tappy,manager,control,statusListener,kioskResponseListener)
                    tappyBleManagers.put(addr,trio)
                    manager.initialize()
                }

                toRemove.map { tappyBleManagers[it] }
                        .forEach { it?.manager?.close() }

                didUpdate = toRemove.isNotEmpty() || toAdd.isNotEmpty()
            }
        }

        if (didUpdate) {
            updateControlListAndNotify()
        }
    }

    private fun managerDidClose(address: String) {
        var didUpdate = false
        bleManagerMapRwLock.write {
            val manager = tappyBleManagers.remove(address)
            if (manager != null) {
                manager.manager.unregisterResponseListener(manager.responseListener)
                manager.manager.unregisterStatusListener(manager.managerStatusListener)
                didUpdate = true
            }
        }
        if (didUpdate) {
            updateControlListAndNotify()
        }
    }

    private fun updateControlListAndNotify() {
        var managers = emptyList<TappyKiosk>()
        bleManagerMapRwLock.read {
            managers = tappyBleManagers.values.map { it.tappyKiosk }
        }
        currentControllableConnectionsRef.set(managers)

        connectionListenerLock.read {
            for(listener in connectionListenerSet) {
                listener.controlledKiosksChanged(currentControllableConnectionsRef.get())
            }
        }

    }

    private fun updateForegroundState() {
        var activeDeviceCount = 0

        bleDesiredRwLock.read {
            activeDeviceCount = desiredTappyBles.size
        }

        if (activeDeviceCount > 0) {
            wakeLock?.acquire()

            val notificationTitle: String
            val notificationContent: String
            if (activeDeviceCount == 1) {
                notificationTitle = getString(R.string.active_tappies_notification_title)
                notificationContent = getString(R.string.one_tappy_active_notification_content)
            } else {
                notificationTitle = getString(R.string.active_tappies_notification_title)
                notificationContent = getString(R.string.multiple_tappies_active_notification_content, activeDeviceCount)
            }

            val disconnectTappiesIntent = Intent(ACTION_DISCONNECT_ALL_TAPPIES)
            val disconnectTappiesPendingIntent = PendingIntent.getBroadcast(this, 0, disconnectTappiesIntent, 0)

            val openActivityIntent = Intent(this, MainActivity::class.java)
            val openActivityPendingIntent = PendingIntent.getActivity(this,0,openActivityIntent,0)

            val notification = TappyNotificationManager.createNotificationBuilder(this)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationContent)
                    .setSmallIcon(R.drawable.ic_tappy_connected_notification)
                    .setTicker(notificationContent)
                    .setContentIntent(openActivityPendingIntent)
                    .addAction(R.drawable.ic_remove_all, getString(R.string.remove_all_tappies), disconnectTappiesPendingIntent)
                    .build()

            startForeground(NOTIFICATION_ID, notification)

            // this makes the service actually started so it isn't killed
            val kickmyselfIntent = Intent(this, TappyKioskService::class.java)
            startService(kickmyselfIntent)
        } else {
            stopForeground(true)
            stopSelf()
            wakeLock?.release()
        }

    }

    private fun handleMessage(message: TCMPMessage) {
        try {
            val response = messageResolver.resolveResponse(message)
            when (response) {
                is NdefFoundResponse -> {
                    if(shouldAutolaunch) {
                        throttleAndLaunch(response.message)
                    }
                    broadcastNdefFound(response.tagCode,response.tagType,response.message)
                }
                is TagFoundResponse -> {
                    broadcastTagFound(response.tagCode,response.tagType)
                }
            }
        } catch (e: MalformedPayloadException) {
            Timber.e(e)
        }

    }

    private fun throttleAndLaunch(message: NdefMessage) {
        val received = SystemClock.uptimeMillis()
        val records = message.records
        if (received - lastLaunched > THROTTLE_URL_MIN_TIME && records.isNotEmpty()) {
            val firstRecord = records[0]
            if (firstRecord.tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(firstRecord.type, NdefRecord.RTD_URI)) {
                val uriPayload = firstRecord.payload
                if (uriPayload.size > 1) {
                    val prefixByte = uriPayload[0]
                    var url: String? = null
                    when (prefixByte) {
                        0x01.toByte() -> url = "http://www." + String(Arrays.copyOfRange(uriPayload, 1, uriPayload.size))
                        0x02.toByte() -> url = "https://www." + String(Arrays.copyOfRange(uriPayload, 1, uriPayload.size))
                        0x03.toByte() -> url = "http://" + String(Arrays.copyOfRange(uriPayload, 1, uriPayload.size))
                        0x04.toByte() -> url = "https://" + String(Arrays.copyOfRange(uriPayload, 1, uriPayload.size))
                    }

                    if (url != null) {
                        val launchUrlIntent = Intent(Intent.ACTION_VIEW)
                        launchUrlIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        launchUrlIntent.data = Uri.parse(url)
                        if (launchUrlIntent.resolveActivity(packageManager) != null) {
                            Timber.v("Attempting to launch view Intent for url %s",url)
                            // this appears to be necessary on Oreo
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val chooserIntent = Intent.createChooser(launchUrlIntent, getString(R.string.open_url_with))
                                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (chooserIntent.resolveActivity(packageManager) != null) {
                                    Timber.v("Launching chooser")
                                } else {
                                    Timber.v("Nothing can handle chooser Intent")
                                }
                            } else {
                                startActivity(launchUrlIntent)
                            }
                        } else {
                            Timber.v("Nothing can handle view Intent")
                        }


                        lastLaunched = received
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun broadcastTagFound(uid: ByteArray, tagType: Byte) {
        val intent = Intent(CarrotApplication.ACTION_TAG_FOUND)
        intent.putExtra(NfcAdapter.EXTRA_ID, uid)
        intent.putExtra(CarrotApplication.EXTRA_TAG_TYPE_INT, tagType)

        broadcastCompat(intent)
    }

    fun broadcastNdefFound(uid: ByteArray, tagType: Byte, message: NdefMessage) {

        val intent = Intent(CarrotApplication.ACTION_NDEF_FOUND)
        intent.putExtra(NfcAdapter.EXTRA_ID, uid)
        intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(message))
        intent.putExtra(CarrotApplication.EXTRA_TAG_TYPE_INT, tagType)

        broadcastCompat(intent)
    }

    protected fun broadcastCompat(intent: Intent) {
        val pm = packageManager
        val matches = pm.queryBroadcastReceivers(intent, 0)

        for (resolveInfo in matches) {
            val explicit = Intent(intent)
            val cn = ComponentName(resolveInfo.activityInfo.applicationInfo.packageName,
                    resolveInfo.activityInfo.name)

            explicit.component = cn
            sendBroadcast(explicit)
        }
    }
    companion object {
        private val TAG = TappyKioskService::class.java.name

        private val THROTTLE_URL_MIN_TIME: Long = 500

        private val NOTIFICATION_ID = 3415
        private val ACTION_DISCONNECT_ALL_TAPPIES = TappyKioskService::class.java.name+".ACTION_DISCONNECT_ALL_TAPPIES"

        private val WAKELOCK_TAG = TappyKioskService::class.java.name

        private val messageResolver: MessageResolver

        init {
            messageResolver = MessageResolverMux(
                    SystemCommandResolver(),
                    BasicNfcCommandResolver()
            )
        }
    }
}