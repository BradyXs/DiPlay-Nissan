package com.shilapi.xcertplay.media

import android.os.Process

/** Give the time-sensitive video workers Android's display scheduling priority when permitted. */
internal object VideoThreadPriority {
    fun apply(label: String, report: (String) -> Unit) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            runCatching {
                report("Video thread priority label=$label level=display(" +
                    Process.THREAD_PRIORITY_DISPLAY + ")")
            }
        } catch (error: Exception) {
            runCatching {
                report("Video thread priority label=$label unchanged error=" +
                    error.javaClass.simpleName)
            }
        }
    }
}
