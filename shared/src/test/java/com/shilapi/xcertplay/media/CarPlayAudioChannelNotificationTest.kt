package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayAudioChannelNotificationTest {
    @Test fun overlappingAudioPublishesChangesAndClearsOnClose() {
        val events = mutableListOf<String>()
        val sink = AndroidMediaSink(onCarPlayAudioChannelChanged = events::add)
        val channelsField = AndroidMediaSink::class.java.getDeclaredField("activeCarPlayAudioChannels")
        channelsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val channels = channelsField.get(sink) as MutableMap<AudioStreamId, AudioChannel>
        val guidanceField = AndroidMediaSink::class.java.getDeclaredField("activeGuidanceAudioIds")
        guidanceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val guidance = guidanceField.get(sink) as MutableSet<AudioStreamId>
        val notify = AndroidMediaSink::class.java.getDeclaredMethod("notifyCarPlayAudioChannelChanged")
        notify.isAccessible = true
        val music = AudioStreamId(96, "media")
        val nav = AudioStreamId(101, "alert")
        val call = AudioStreamId(96, "telephony")
        channels[music] = AudioChannel.MEDIA
        notify.invoke(sink)
        notify.invoke(sink) // unchanged state is not emitted twice
        channels[nav] = AudioChannel.NAVIGATION
        guidance.add(nav)
        notify.invoke(sink)
        channels[call] = AudioChannel.PHONE
        notify.invoke(sink)
        channels.remove(call)
        notify.invoke(sink)
        guidance.remove(nav)
        channels.remove(nav)
        notify.invoke(sink)
        sink.close()
        assertEquals(listOf("media", "navigation", "call", "navigation", "media", "none"), events)
    }
}
