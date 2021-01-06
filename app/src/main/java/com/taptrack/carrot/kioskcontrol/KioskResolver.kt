package com.taptrack.carrot.kioskcontrol

import android.nfc.NdefMessage
import com.taptrack.tcmptappy2.MalformedPayloadException
import com.taptrack.tcmptappy2.MessageResolverMux
import com.taptrack.tcmptappy2.TCMPMessage
import com.taptrack.tcmptappy2.commandfamilies.basicnfc.BasicNfcCommandResolver
import com.taptrack.tcmptappy2.commandfamilies.basicnfc.responses.NdefFoundResponse
import com.taptrack.tcmptappy2.commandfamilies.basicnfc.responses.TagFoundResponse
import com.taptrack.tcmptappy2.commandfamilies.systemfamily.SystemCommandResolver
import com.taptrack.tcmptappy2.commandfamilies.systemfamily.responses.PingResponse
import java.util.*

sealed class ResolvedMessage {
    data class Ndef(val uid: ByteArray, val tagType: Byte, val ndef: NdefMessage, val receivedAt: Long) : ResolvedMessage() {
        constructor(uid: ByteArray,  tagType: Byte, ndef: NdefMessage) : this(uid, tagType, ndef, System.currentTimeMillis())

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Ndef

            if (!Arrays.equals(uid, other.uid)) return false
            if (ndef != other.ndef) return false
            if (tagType != other.tagType) return false
            if (receivedAt != other.receivedAt) return false

            return true
        }

        override fun hashCode(): Int {
            var result = Arrays.hashCode(uid)
            result = 31 * result + ndef.hashCode()
            result = 31 * result + tagType
            result = 31 * result + receivedAt.hashCode()
            return result
        }
    }

    data class Tag(val uid: ByteArray, val tagType: Byte, val receivedAt: Long) : ResolvedMessage() {
        constructor(uid: ByteArray, tagType: Byte) : this(uid, tagType, System.currentTimeMillis())

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Tag

            if (!Arrays.equals(uid, other.uid)) return false
            if (tagType != other.tagType) return false
            if (receivedAt != other.receivedAt) return false

            return true
        }

        override fun hashCode(): Int {
            var result = Arrays.hashCode(uid)
            result = 31 * result + tagType
            result = 31 * result + receivedAt.hashCode()
            return result
        }
    }

    data class Ping(val receivedAt: Long): ResolvedMessage() {
        constructor() : this(System.currentTimeMillis())
    }

    data class UnexpectedResponse(val receivedAt: Long): ResolvedMessage(){
        constructor() : this(System.currentTimeMillis())
    }
}

object KioskResolver {
    private val resolver = MessageResolverMux(SystemCommandResolver(),BasicNfcCommandResolver())

    fun resolve(msg: TCMPMessage): ResolvedMessage? {
        try {
            val resolved = resolver.resolveResponse(msg)
            when (resolved) {
                is NdefFoundResponse -> {
                    val ndefMsg = resolved.message
                    return if (ndefMsg == null || ndefMsg.records?.size == 0) {
                        ResolvedMessage.UnexpectedResponse()
                    } else {
                        ResolvedMessage.Ndef(resolved.tagCode, resolved.tagType, ndefMsg, System.currentTimeMillis())
                    }
                }
                is TagFoundResponse -> {
                    return ResolvedMessage.Tag(resolved.tagCode, resolved.tagType, System.currentTimeMillis())
                }
                is PingResponse -> {
                    return ResolvedMessage.Ping(System.currentTimeMillis())
                }
                else -> {
                    return ResolvedMessage.UnexpectedResponse()
                }
            }
        } catch (e: MalformedPayloadException) {
            return ResolvedMessage.UnexpectedResponse()
        }
    }

}