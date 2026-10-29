package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.orchestration.CarPlayController

/** Standard Android phone keys sent to the CarPlay telephony HID device. */
internal enum class CarPlayCallAction {
    ANSWER, END;

    fun send(controller: CarPlayController?): Boolean = when (this) {
        ANSWER -> controller?.answerCall() == true
        END -> controller?.endCall() == true
    }

    companion object {
        fun forKeyCode(keyCode: Int): CarPlayCallAction? = when (keyCode) {
            KeyEvent.KEYCODE_CALL -> ANSWER
            KeyEvent.KEYCODE_ENDCALL -> END
            else -> null
        }
    }
}
