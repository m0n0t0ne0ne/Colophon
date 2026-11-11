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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

private const val MAX_CANDIDATES = 400
private const val LISTEN_WINDOW_MS = 12_000L
private const val SEGMENT_MS = 600_000L
private const val SEGMENT_OVERLAP_MS = 3_000L
private const val MIN_MARKERS = 3
private const val MAX_MARKER_SPACING_MS = 45 * 60_000L
private const val SAMPLE_RATE = 16_000
private const val CHANNEL = "analysis"

/** Runs entirely on the phone: decodes the audio, finds pauses, then transcribes just after each one. */
class AnalyzeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val bookId get() = inputData.getLong(KEY_BOOK, -1)
    @Volatile private var lastProgress = 0L

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

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val dao = ColophonDatabase.get(applicationContext).dao()
        val book = dao.book(bookId) ?: return@withContext Result.failure()
        val files = dao.files(bookId)
        if (files.isEmpty()) return@withContext Result.failure()
        runCatching { setForeground(getForegroundInfo()) }

        try {
            val total = files.sumOf { it.durationMs }.coerceAtLeast(1)
            val markers = dao.chapters(bookId, ChapterSet.ORIGINAL)
            val spacing = if (markers.size > 1) total / markers.size else Long.MAX_VALUE
            val useMarkers = !inputData.getBoolean(KEY_FULL, false) && markers.size >= MIN_MARKERS && spacing <= MAX_MARKER_SPACING_MS
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            val targets: List<Target> = if (useMarkers) {
                markers.map { Target(it.startMs, 0, it.title) }
            } else {
                val pauses = scan(files, total, workers)
                if (isStopped) return@withContext Result.failure()
                val chosen = pauses.sortedByDescending { it.lengthMs }.take(MAX_CANDIDATES).map { Target(it.endMs, it.lengthMs, null) }
                val starts = runningStarts(files).map { Target(it, 0, null) }
                (chosen + starts).sortedBy { it.ms }
            }

            val candidates = transcribeAll(files, targets, workers, if (useMarkers) 0 else 45)
            if (isStopped) return@withContext Result.failure()

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

    private class Target(val ms: Long, val pauseMs: Long, val fallbackTitle: String?)

    private fun timeLeft(startedAt: Long, done: Long, total: Long): String {
        val elapsed = System.currentTimeMillis() - startedAt
        if (done <= 0 || elapsed < 20_000) return ""
        val left = elapsed * (total - done).coerceAtLeast(0) / done
        return ", about ${formatLeft(left.coerceAtLeast(60_000))} left in this step"
    }

    /** Scans fixed-size segments on several cores. Segments overlap so no pause is cut in half. */
    private suspend fun scan(files: List<BookFile>, total: Long, workers: Int): List<Pause> = coroutineScope {
        val segments = ArrayDeque<Triple<BookFile, Long, Long>>()
        var offset = 0L
        for (file in files) {
            var start = 0L
            while (start < file.durationMs) {
                segments += Triple(file, offset, start)
                start += SEGMENT_MS
            }
            offset += file.durationMs
        }
        val lock = Any()
        val found = mutableListOf<Pause>()
        val done = AtomicLong(0)
        val startedAt = System.currentTimeMillis()
        List(workers) {
            launch(Dispatchers.Default) {
                while (!isStopped) {
                    val (file, base, start) = synchronized(lock) { segments.removeFirstOrNull() } ?: break
                    val detector = PauseDetector()
                    var counted = 0L
                    Decoder(applicationContext, Uri.parse(file.uri)).decode(start, start + SEGMENT_MS + SEGMENT_OVERLAP_MS) { samples, count ->
                        if (isStopped) return@decode false
                        feedFrames(detector, samples, count)
                        val fresh = detector.positionMs.coerceAtMost(SEGMENT_MS) - counted
                        counted += fresh
                        val all = done.addAndGet(fresh)
                        val time = timeLeft(startedAt, all, total)
                        reportAsync(2, (all.coerceAtMost(total) * 45 / total).toInt(), "Finding likely breaks: ${formatLeft(all)} of ${formatLeft(total)}$time")
                        true
                    }
                    detector.finish()
                    synchronized(lock) { found += detector.pauses.map { Pause(it.startMs + start + base, it.endMs + start + base) } }
                }
            }
        }.joinAll()
        PauseMerger.merge(found)
    }

    private suspend fun transcribeAll(files: List<BookFile>, targets: List<Target>, workers: Int, floor: Int): List<Candidate> = coroutineScope {
        reportAsync(3, floor, "Preparing speech recognition")
        val model = Model(ensureModel(applicationContext).absolutePath)
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val lock = Any()
        val out = mutableListOf<Candidate>()
        val startedAt = System.currentTimeMillis()
        try {
            List(workers) {
                launch(Dispatchers.Default) {
                    while (!isStopped) {
                        val target = targets.getOrNull(next.getAndIncrement()) ?: break
                        val words = transcribe(model, files, target.ms)
                        val baseStart = (target.ms - if (target.pauseMs > 0) 300 else 0).coerceAtLeast(0)
                        // A heading heard right at the mark is trusted as-is. Otherwise, if the window is
                        // the previous chapter's tail followed by a pause and a heading (an embedded mark
                        // placed a few seconds early), snap the start onto that heading.
                        val direct = TitleExtractor.extract(words)
                        val reloc = if (direct == null) HeadingLocator.relocate(words) else null
                        val candidate = Candidate(
                            startMs = reloc?.let { (target.ms + (it.first * 1000).toLong() - 300).coerceAtLeast(0) } ?: baseStart,
                            pauseMs = target.pauseMs,
                            heading = direct ?: reloc?.second,
                            firstWords = words.take(5).joinToString(" ") { it.text },
                            fallbackTitle = target.fallbackTitle,
                        )
                        synchronized(lock) { out += candidate }
                        val n = done.incrementAndGet()
                        val time = timeLeft(startedAt, n.toLong(), targets.size.toLong())
                        reportAsync(3, floor + n * (95 - floor) / targets.size, "Transcribing $n of ${targets.size}$time")
                    }
                }
            }.joinAll()
        } finally {
            model.close()
        }
        out
    }

    private fun reportAsync(stage: Int, percent: Int, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastProgress < 2000) return
        lastProgress = now
        setProgressAsync(workDataOf(KEY_STAGE to stage, KEY_PERCENT to percent, KEY_DETAIL to detail))
        runCatching { setForegroundAsync(foreground(detail, percent)) }
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
        const val KEY_FULL = "full"
        const val KEY_STAGE = "stage"
        const val KEY_PERCENT = "percent"
        const val KEY_DETAIL = "detail"
        const val TAG = "analysis"

        fun tagFor(bookId: Long) = "book-$bookId"

        fun enqueue(context: Context, bookId: Long, onlyWhileCharging: Boolean, fullScan: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<AnalyzeWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId, KEY_FULL to fullScan))
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
            val pending = ShortArray(SAMPLE_RATE)
            var pendingCount = 0

            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            decoding@ while (true) {
                var progressed = false
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    progressed = true
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
                // Wait only when there was nothing to feed, so the codec is never left idle.
                val o = codec.dequeueOutputBuffer(info, if (progressed) 0 else 2_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (o >= 0) {
                    val timeMs = info.presentationTimeUs / 1000
                    if (toMs != null && timeMs >= toMs) {
                        codec.releaseOutputBuffer(o, false)
                        break@decoding
                    }
                    if (info.size > 0 && timeMs >= fromMs) {
                        val buffer = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val step = rate.toDouble() / SAMPLE_RATE
                        val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        val values = info.size / if (isFloat) 4 else 2
                        if (isFloat) {
                            if (floats.size < values) floats = FloatArray(values)
                            buffer.asFloatBuffer().get(floats, 0, values)
                        } else {
                            if (shorts.size < values) shorts = ShortArray(values)
                            buffer.asShortBuffer().get(shorts, 0, values)
                        }
                        var v = 0
                        while (v + channels <= values) {
                            carry += 1.0
                            if (carry >= step) {
                                carry -= step
                                var mix = 0f
                                for (c in 0 until channels) mix += if (isFloat) floats[v + c] else shorts[v + c] / 32768f
                                pending[pendingCount++] = (mix / channels).coerceIn(-1f, 1f).times(32767).toInt().toShort()
                                if (pendingCount == pending.size) {
                                    val keepGoing = sink(pending, pendingCount)
                                    pendingCount = 0
                                    if (!keepGoing) {
                                        codec.releaseOutputBuffer(o, false)
                                        return
                                    }
                                }
                            }
                            v += channels
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@decoding
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
