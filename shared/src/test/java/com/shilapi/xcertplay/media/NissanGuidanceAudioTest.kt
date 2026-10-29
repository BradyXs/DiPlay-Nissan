package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NissanGuidanceAudioTest {
    @Test fun defaultAudioNeedsNearbyNavigationUpdateAndDucking() {
        val classifier = NissanGuidanceAudio()
        assertFalse(classifier.isGuidance("default", AudioChannel.NAVIGATION, 10_000))
        classifier.onRouteGuidanceUpdate(10_000)
        assertFalse(classifier.isGuidance("default", AudioChannel.NAVIGATION, 10_400))
        classifier.onAirPlayDuckingChanged(true)
        assertTrue(classifier.isGuidance("default", AudioChannel.NAVIGATION, 10_400))
        assertFalse(classifier.isGuidance("default", AudioChannel.NAVIGATION, 16_000))
        assertFalse(classifier.isGuidance("default", AudioChannel.NAVIGATION, 9_999))
        classifier.onAirPlayDuckingChanged(false)
        assertFalse(classifier.isGuidance("default", AudioChannel.NAVIGATION, 10_400))
    }

    @Test fun confirmedDefaultStreamDucksAgainAfterRouteUpdateAgesOut() {
        val classifier = NissanGuidanceAudio()
        classifier.onRouteGuidanceUpdate(1_000)
        classifier.onAirPlayDuckingChanged(true)
        assertTrue(classifier.isGuidance("default", AudioChannel.NAVIGATION, 1_200))
        classifier.onAirPlayDuckingChanged(false)
        assertFalse(classifier.isGuidance("default", AudioChannel.NAVIGATION, 8_000, confirmedStream = true))
        classifier.onAirPlayDuckingChanged(true)
        assertTrue(classifier.isGuidance("default", AudioChannel.NAVIGATION, 8_100, confirmedStream = true))
        assertFalse(classifier.isGuidance("default", AudioChannel.NAVIGATION, 8_100))
    }

    @Test fun explicitNavigationAndMediaKeepTheirOwnClassification() {
        val classifier = NissanGuidanceAudio()
        assertTrue(classifier.isGuidance("alert", AudioChannel.NAVIGATION, 30_000))
        assertFalse(classifier.isGuidance("media", AudioChannel.MEDIA, 30_000))
    }
}
