package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** iPhone call evidence from iAP2 CallStateUpdate, scoped to one CarPlay controller. */
enum class CarPlayCallPhase { RINGING, DIALING, ACTIVE }

/** Epoch distinguishes successive calls even when the iPhone omits a call UUID. */
data class CarPlayCallSnapshot(val phase: CarPlayCallPhase, val epoch: Long)

internal class CarPlayCallState {
    private data class Call(val status: Int, val epoch: Long)
    private val calls = LinkedHashMap<String, Call>()
    private var nextEpoch = 0L

    /** Returns the reported status for diagnostics, or null for unrelated/malformed frames. */
    fun accept(frame: Iap2Frame): Int? {
        if (frame.messageId != CALL_STATE_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull() ?: return null
        if (status !in DISCONNECTED..DISCONNECTING) return null
        val id = runCatching { body.optionalString(CALL_UUID) }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: runCatching { body.optionalString(REMOTE_ID) }.getOrNull()
                ?.takeIf(String::isNotBlank)
            ?: SINGLE_CALL
        if (status == DISCONNECTED || status == DISCONNECTING) calls.remove(id)
        else calls[id] = Call(status, calls[id]?.epoch ?: ++nextEpoch)
        return status
    }

    fun current(): CarPlayCallSnapshot? {
        calls.values.firstOrNull { it.status == RINGING }?.let {
            return CarPlayCallSnapshot(CarPlayCallPhase.RINGING, it.epoch)
        }
        calls.values.firstOrNull { it.status == ACTIVE || it.status == HELD }?.let {
            return CarPlayCallSnapshot(CarPlayCallPhase.ACTIVE, it.epoch)
        }
        calls.values.firstOrNull { it.status == SENDING || it.status == CONNECTING }?.let {
            return CarPlayCallSnapshot(CarPlayCallPhase.DIALING, it.epoch)
        }
        return null
    }

    fun count(): Int = calls.size

    fun clear() = calls.clear()

    companion object {
        const val CALL_STATE_UPDATE = 0x4155
        private const val REMOTE_ID = 0
        private const val STATUS = 2
        private const val CALL_UUID = 4
        private const val DISCONNECTED = 0
        private const val SENDING = 1
        private const val RINGING = 2
        private const val CONNECTING = 3
        private const val ACTIVE = 4
        private const val HELD = 5
        private const val DISCONNECTING = 6
        private const val SINGLE_CALL = "single-call"
    }
}
