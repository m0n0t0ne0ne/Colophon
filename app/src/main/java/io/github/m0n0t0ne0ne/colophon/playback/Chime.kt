package io.github.m0n0t0ne0ne.colophon.playback

import android.media.ToneGenerator

/** The notification sounds offered for the sleep-timer warning and the shake confirmation. */
enum class Chime(val label: String, val tone: Int, val ms: Int) {
    Beep("Beep", ToneGenerator.TONE_PROP_BEEP, 220),
    Double("Double", ToneGenerator.TONE_PROP_ACK, 300),
    Chirp("Chirp", ToneGenerator.TONE_CDMA_PIP, 250),
    Bell("Bell", ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 400);

    companion object {
        fun from(name: String?): Chime = entries.firstOrNull { it.name == name } ?: Beep
    }
}
