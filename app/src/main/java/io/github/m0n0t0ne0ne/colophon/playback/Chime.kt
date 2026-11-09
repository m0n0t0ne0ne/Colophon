package io.github.m0n0t0ne0ne.colophon.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** One partial of a tone: a frequency multiple of the fundamental and its relative gain. */
private data class Partial(val ratio: Float, val gain: Float)

/** A note within a chime: fundamental frequency, when it starts, how long it rings, and its partials. */
private data class Tone(val freq: Float, val atSec: Float, val durSec: Float, val partials: List<Partial>)

/**
 * The notification sounds for the sleep-timer warning and the shake confirmation.
 *
 * These are chosen to be heard over narration without lowering the book. Speech energy sits mostly
 * in the 300 Hz-3.4 kHz midrange, so the bright options ring clearly above it, and Deep sits below it.
 */
enum class Chime(val label: String, private val tones: List<Tone>) {
    // Deep sits below the speech midrange, so it carries over narration without masking any words.
    Deep("Deep", listOf(
        Tone(165f, 0f, 0.7f, listOf(Partial(1f, 1f), Partial(2f, 0.45f), Partial(4f, 0.12f))),
        Tone(247f, 0.13f, 0.6f, listOf(Partial(1f, 1f), Partial(2f, 0.4f))),
    )),
    Pulse("Pulse", listOf(
        Tone(130f, 0f, 0.9f, listOf(Partial(1f, 1f), Partial(2f, 0.4f), Partial(3f, 0.12f))),
    )),
    Double("Double", listOf(
        Tone(110f, 0f, 0.4f, listOf(Partial(1f, 1f), Partial(2f, 0.45f))),
        Tone(110f, 0.3f, 0.5f, listOf(Partial(1f, 1f), Partial(2f, 0.45f))),
    )),
    Drop("Drop", listOf(
        Tone(247f, 0f, 0.5f, listOf(Partial(1f, 1f), Partial(2f, 0.4f))),
        Tone(165f, 0.16f, 0.75f, listOf(Partial(1f, 1f), Partial(2f, 0.45f), Partial(4f, 0.12f))),
    ));

    fun render(volume: Int): ShortArray {
        val amp = (volume.coerceIn(0, 100) / 100f) * 0.95f
        val total = tones.maxOf { it.atSec + it.durSec }
        val count = (total * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val buf = FloatArray(count)
        for (tone in tones) {
            val start = (tone.atSec * SAMPLE_RATE).toInt()
            val len = (tone.durSec * SAMPLE_RATE).toInt()
            val decay = tone.durSec * 0.35f
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= count) break
                val t = i / SAMPLE_RATE.toFloat()
                val attack = (t / 0.005f).coerceAtMost(1f)
                val env = attack * exp(-t / decay)
                var s = 0f
                for (p in tone.partials) s += p.gain * sin(2.0 * PI * tone.freq * p.ratio * t).toFloat()
                buf[idx] += env * s
            }
        }
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(0.0001f)
        val scale = amp / peak
        return ShortArray(count) { (buf[it] * scale * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort() }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        fun from(name: String?): Chime = entries.firstOrNull { it.name == name } ?: entries.first()
    }
}

/** Plays a [Chime] by synthesizing it and mixing it with whatever else is playing — no ducking. */
object ChimeSynth {
    private const val SAMPLE_RATE = 44100

    fun play(sound: Chime, volume: Int) {
        thread(isDaemon = true) {
            runCatching {
                val samples = sound.render(volume)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(samples.size * 1000L / SAMPLE_RATE + 250)
                runCatching { track.stop() }
                track.release()
            }
        }
    }
}
