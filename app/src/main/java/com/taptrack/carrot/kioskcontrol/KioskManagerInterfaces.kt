package com.taptrack.carrot.kioskcontrol

import android.nfc.NdefMessage
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface KioskManager {
    enum class Status{
        UNINITIALIZED, INITIALIZING, HEALTHY, HEARTBEAT_FAILURE ,CLOSING, CLOSED
    }
    interface ManagerStatusListener {
        fun onNewKioskStatus(kioskStatus: Status)
    }
    
    interface KioskResponseListener {
        fun onNdefRead(uid: ByteArray, tagType: Byte, ndef: NdefMessage)
        fun onTagRead(uid: ByteArray, tagType: Byte)
        fun onHeartbeatReceived(time: Long)
    }

    fun registerStatusListener(listener: ManagerStatusListener)
    fun unregisterStatusListener(listener: ManagerStatusListener)

    fun registerResponseListener(listener: KioskResponseListener)
    fun unregisterResponseListener(listener: KioskResponseListener)

    fun getStatus(): Status
    fun initialize(): Unit
    fun close(): Unit
}

internal class KioskCallbacks(private val manager: KioskManager, private val notifyLooper: Looper) {
    val statusListeners = hashSetOf<KioskManager.ManagerStatusListener>()
    val statusRwLock = ReentrantReadWriteLock()

    val kioskResponseListeners = CopyOnWriteArraySet<KioskManager.KioskResponseListener>()
    val responseRwLock = ReentrantReadWriteLock()

    val notifyHandler = Handler(notifyLooper)

    fun registerStatusListener(listener: KioskManager.ManagerStatusListener) {
        statusRwLock.write {
            val current = manager.getStatus()
            statusListeners.add(listener)
            listener.onNewKioskStatus(current)
        }
    }
    fun unregisterStatusListener(listener: KioskManager.ManagerStatusListener) {
        statusRwLock.write {
            statusListeners.remove(listener)
        }
    }

    fun notifyOfNewStatus(kioskStatus: KioskManager.Status) {
        notifyHandler.post {
            statusRwLock.read {
                statusListeners.forEach {
                    it.onNewKioskStatus(kioskStatus)
                }
            }
        }
    }

    fun registerKioskResponseListener(listener: KioskManager.KioskResponseListener) {
        responseRwLock.write {
            kioskResponseListeners.add(listener)
        }
    }
    fun unregisterKioskResponseListener(listener: KioskManager.KioskResponseListener) {
        responseRwLock.write {
            kioskResponseListeners.remove(listener)
        }
    }

    fun notifyOfNewNdefRead(uid: ByteArray, tagType: Byte, ndef: NdefMessage) {
        notifyHandler.post {
            responseRwLock.read {
                kioskResponseListeners.forEach { it.onNdefRead(uid, tagType, ndef) }
            }
        }
    }

    fun notifyOfNewTagRead(uid: ByteArray, tagType: Byte) {
        notifyHandler.post {
            responseRwLock.read {
                kioskResponseListeners.forEach { it.onTagRead(uid, tagType) }
            }
        }
    }

    fun notifyOfNewHeartbeatReceived(time: Long) {
        notifyHandler.post {
            responseRwLock.read {
                kioskResponseListeners.forEach { it.onHeartbeatReceived(time) }
            }
        }
    }
}




