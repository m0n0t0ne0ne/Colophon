package io.github.m0n0t0ne0ne.colophon.analyzer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import io.github.m0n0t0ne0ne.colophon.R
import io.github.m0n0t0ne0ne.colophon.core.*
import io.github.m0n0t0ne0ne.colophon.data.BookFile
import io.github.m0n0t0ne0ne.colophon.data.Chapter
import io.github.m0n0t0ne0ne.colophon.data.ChapterSet
import io.github.m0n0t0ne0ne.colophon.data.ColophonDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

private const val MAX_CANDIDATES = 400
private const val LISTEN_WINDOW_MS = 15_000L
private const val SAMPLE_RATE = 16_000
private const val CHANNEL = "analysis"

/** Runs entirely on the phone: decodes the audio, finds pauses, then transcribes just after each one. */
class AnalyzeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val bookId get() = inputData.getLong(KEY_BOOK, -1)
    private var lastProgress = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Analyzing audio", 0)

    private fun foreground(text: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Chapter analysis", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Colophon")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(7, notification)
    }

    private suspend fun report(stage: Int, percent: Int, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgress < 1500) return
        lastProgress = now
        setProgress(workDataOf(KEY_STAGE to stage, KEY_PERCENT to percent, KEY_DETAIL to detail))
        runCatching { setForeground(foreground(detail, percent)) }
    }

    /** Scanning one long file can take many minutes, so progress is reported during the file. */
    private fun reportScan(doneMs: Long, totalMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgress < 2000) return
        lastProgress = now
        val percent = (doneMs.coerceAtMost(totalMs) * 45 / totalMs).toInt()
        val detail = "Finding likely breaks: ${formatLeft(doneMs)} of ${formatLeft(totalMs)}"
        setProgressAsync(workDataOf(KEY_STAGE to 2, KEY_PERCENT to percent, KEY_DETAIL to detail))
        runCatching { setForegroundAsync(foreground(detail, percent)) }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val dao = ColophonDatabase.get(applicationContext).dao()
        val book = dao.book(bookId) ?: return@withContext Result.failure()
        val files = dao.files(bookId)
        if (files.isEmpty()) return@withContext Result.failure()
        runCatching { setForeground(getForegroundInfo()) }

        try {
            val total = files.sumOf { it.durationMs }.coerceAtLeast(1)
            val pauses = mutableListOf<Pause>()
            var offset = 0L
            for (file in files) {
                val detector = PauseDetector()
                val base = offset
                Decoder(applicationContext, Uri.parse(file.uri)).decode(0, null) { samples, count ->
                    if (isStopped) return@decode false
                    feedFrames(detector, samples, count)
                    reportScan(base + detector.positionMs, total)
                    true
                }
                detector.finish()
                pauses += detector.pauses.map { Pause(it.startMs + base, it.endMs + base) }
                offset += file.durationMs
                report(2, ((offset * 45) / total).toInt(), "Finding likely breaks")
                if (isStopped) return@withContext Result.failure()
            }

            val chosen = pauses.sortedByDescending { it.lengthMs }.take(MAX_CANDIDATES).sortedBy { it.startMs }
            val fileStarts = runningStarts(files).map { Pause(it, it) }
            val targets = (chosen + fileStarts).sortedBy { it.endMs }

            val model = Model(ensureModel(applicationContext).absolutePath)
            val candidates = mutableListOf<Candidate>()
            try {
                targets.forEachIndexed { i, pause ->
                    if (isStopped) return@withContext Result.failure()
                    val words = transcribe(model, files, pause.endMs)
                    candidates += Candidate(
                        startMs = (pause.endMs - 300).coerceAtLeast(0),
                        pauseMs = pause.lengthMs,
                        heading = TitleExtractor.extract(words),
                        firstWords = words.take(5).joinToString(" ") { it.text },
                    )
                    report(3, 45 + (i + 1) * 50 / targets.size, "Transcribing ${i + 1} of ${targets.size}")
                }
            } finally {
                model.close()
            }

            setProgress(workDataOf(KEY_STAGE to 4, KEY_PERCENT to 97, KEY_DETAIL to "Extracting titles"))
            val suggested = ChapterBuilder.build(candidates, book.durationMs)
            dao.replaceSet(
                bookId, ChapterSet.SUGGESTED,
                suggested.map { Chapter(bookId = bookId, chapterSet = ChapterSet.SUGGESTED, startMs = it.startMs, title = it.title, kind = it.kind.name, reason = it.reason) },
            )
            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_DETAIL to (e.message ?: "Analysis failed")))
        }
    }

    private fun runningStarts(files: List<BookFile>): List<Long> {
        var t = 0L
        return files.map { f -> t.also { t += f.durationMs } }
    }

    private fun feedFrames(detector: PauseDetector, samples: ShortArray, count: Int) {
        val frame = SAMPLE_RATE / 10
        var i = 0
        while (i + frame <= count) {
            var sum = 0.0
            for (j in i until i + frame) {
                val v = samples[j] / 32768.0
                sum += v * v
            }
            detector.feed(sqrt(sum / frame))
            i += frame
        }
    }

    private fun transcribe(model: Model, files: List<BookFile>, globalMs: Long): List<Word> {
        val (index, local) = Timeline.locate(files.map { it.durationMs }, globalMs)
        val out = mutableListOf<Word>()
        Recognizer(model, SAMPLE_RATE.toFloat()).use { r ->
            r.setWords(true)
            Decoder(applicationContext, Uri.parse(files[index].uri)).decode(local, local + LISTEN_WINDOW_MS) { samples, count ->
                if (r.acceptWaveForm(samples, count)) out += parseWords(r.result)
                !isStopped
            }
            out += parseWords(r.finalResult)
        }
        return out
    }

    private fun parseWords(json: String): List<Word> {
        val items = JSONObject(json).optJSONArray("result") ?: return emptyList()
        return List(items.length()) { i ->
            val o = items.getJSONObject(i)
            Word(o.getString("word"), o.getDouble("start"), o.getDouble("end"))
        }
    }

    companion object {
        const val KEY_BOOK = "book"
        const val KEY_STAGE = "stage"
        const val KEY_PERCENT = "percent"
        const val KEY_DETAIL = "detail"
        const val TAG = "analysis"

        fun tagFor(bookId: Long) = "book-$bookId"

        fun enqueue(context: Context, bookId: Long, onlyWhileCharging: Boolean) {
            val request = OneTimeWorkRequestBuilder<AnalyzeWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId))
                .setConstraints(Constraints.Builder().setRequiresCharging(onlyWhileCharging).build())
                .addTag(TAG)
                .addTag(tagFor(bookId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("analyze-$bookId", ExistingWorkPolicy.REPLACE, request)
        }

        @Synchronized
        fun ensureModel(context: Context): File {
            val root = File(context.filesDir, "speech-model")
            val done = File(root, ".ready")
            if (!done.exists()) {
                root.deleteRecursively()
                root.mkdirs()
                ZipInputStream(context.assets.open("model.zip")).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        val target = File(root, entry.name.substringAfter('/'))
                        require(target.canonicalPath.startsWith(root.canonicalPath))
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                done.createNewFile()
            }
            return root
        }
    }
}

/** Decodes any format the phone can decode into 16 kHz mono PCM. */
class Decoder(private val context: Context, private val uri: Uri) {
    fun decode(fromMs: Long, toMs: Long?, sink: (ShortArray, Int) -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (fromMs > 0) extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var carry = 0.0
            var pending = ShortArray(SAMPLE_RATE)
            var pendingCount = 0

            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (o >= 0) {
                    val timeMs = info.presentationTimeUs / 1000
                    if (toMs != null && timeMs >= toMs) {
                        codec.releaseOutputBuffer(o, false)
                        break
                    }
                    if (info.size > 0 && timeMs >= fromMs) {
                        val buffer = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val step = rate.toDouble() / SAMPLE_RATE
                        val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        val inputFrames = info.size / (bytes * channels)
                        var frame = 0
                        while (frame < inputFrames) {
                            var mix = 0f
                            repeat(channels) {
                                mix += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f
                            }
                            mix /= channels
                            carry += 1.0
                            if (carry >= step) {
                                carry -= step
                                pending[pendingCount++] = (mix.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                                if (pendingCount == pending.size) {
                                    val keepGoing = sink(pending, pendingCount)
                                    pendingCount = 0
                                    if (!keepGoing) {
                                        codec.releaseOutputBuffer(o, false)
                                        return
                                    }
                                }
                            }
                            frame++
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (pendingCount > 0) sink(pending, pendingCount)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
