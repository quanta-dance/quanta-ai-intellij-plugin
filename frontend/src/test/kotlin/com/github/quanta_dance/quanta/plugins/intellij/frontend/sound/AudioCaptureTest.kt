// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.sound

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioCaptureTest {
    @Test
    fun `first audible buffer reports speech even without preceding silence`() {
        assertTrue(AudioCapture.shouldNotifySpeech(inSilence = false, inSpeech = false))
    }

    @Test
    fun `speech resuming during an open segment reports speech`() {
        assertTrue(AudioCapture.shouldNotifySpeech(inSilence = true, inSpeech = true))
    }

    @Test
    fun `continuous speech does not report every audio buffer`() {
        assertFalse(AudioCapture.shouldNotifySpeech(inSilence = false, inSpeech = true))
    }

    @Test
    fun `pre-roll keeps audio immediately before speech`() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)

        val buffered = AudioCapture.appendPreRoll(first, second, maxBytes = 5)

        assertTrue(buffered.contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `pre-roll discards the oldest audio when full`() {
        val existing = byteArrayOf(1, 2, 3, 4)
        val newest = byteArrayOf(5, 6, 7)

        val buffered = AudioCapture.appendPreRoll(existing, newest, maxBytes = 5)

        assertTrue(buffered.contentEquals(byteArrayOf(3, 4, 5, 6, 7)))
    }

    @Test
    fun `pre-roll trims a chunk larger than its capacity`() {
        val buffered = AudioCapture.appendPreRoll(ByteArray(0), byteArrayOf(1, 2, 3, 4), maxBytes = 2)

        assertTrue(buffered.contentEquals(byteArrayOf(3, 4)))
    }
}
