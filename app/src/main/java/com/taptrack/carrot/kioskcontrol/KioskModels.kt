package com.taptrack.carrot.kioskcontrol

sealed class KioskStatus {
    object Connecting: KioskStatus()
    object HeartbeatGood: KioskStatus()
    object HeartbeatFailure: KioskStatus()
    object Disconnecting: KioskStatus()
    object Closed: KioskStatus()
    object Unknown: KioskStatus()
}

interface KioskStatusListener{
    fun onKioskStatusUpdate(status: KioskStatus)
}

interface KioskHeartbeatListener {
    fun onHeartbeatReceived(timeReceived: Long)
}

interface TappyKiosk {
    fun getId(): String
    fun getName(): String
    fun requestClose()
    fun addStatusListener(kioskStatusListener: KioskStatusListener)
    fun removeStatusListener(kioskStatusListener: KioskStatusListener)
    fun addHeartbeatListener(heartbeatListener: KioskHeartbeatListener)
    fun removeHeartbeatListener(heartbeatListener: KioskHeartbeatListener)
}