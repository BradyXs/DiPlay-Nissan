package com.shilapi.xcertplay

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Avoids AndroidJUnitRunner's desugared constructor before KitKat installs secondary DEX files. */
class T3LegacyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        androidx.multidex.MultiDex.installInstrumentation(context, targetContext)
        InstrumentationRegistry.registerInstance(this, arguments ?: Bundle())
        start()
    }

    override fun onStart() {
        val junit = JUnitCore()
        junit.addListener(object : RunListener() {
            override fun testStarted(description: org.junit.runner.Description) {
                sendStatus(1, Bundle().apply { putString("stream", "START ${description.methodName}\n") })
            }
            override fun testFailure(failure: Failure) {
                sendStatus(-2, Bundle().apply { putString("stream", failure.trace) })
            }
        })
        val result = junit.run(T3LegacyRuntimeTest::class.java)
        finish(if (result.wasSuccessful()) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("stream", "Tests run: ${result.runCount}, failures: ${result.failureCount}, ignored: ${result.ignoreCount}")
            })
    }
}
