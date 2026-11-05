package io.github.m0n0t0ne0ne.colophon.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.m0n0t0ne0ne.colophon.MainActivity
import io.github.m0n0t0ne0ne.colophon.R
import io.github.m0n0t0ne0ne.colophon.core.Resume
import io.github.m0n0t0ne0ne.colophon.core.SectionMap
import io.github.m0n0t0ne0ne.colophon.core.Timeline
import io.github.m0n0t0ne0ne.colophon.data.ChapterSet
import io.github.m0n0t0ne0ne.colophon.data.ColophonDatabase
import io.github.m0n0t0ne0ne.colophon.data.PositionHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs

private const val SAVE_INTERVAL_MS = 5_000L
private const val JUMP_WORTH_REMEMBERING_MS = 60_000L
private const val FINISHED_MARGIN_MS = 30_000L

private const val JUMP_MS = 30_000L
private const val ACTION_JUMP_BACK = "io.github.m0n0t0ne0ne.colophon.JUMP_BACK"
private const val ACTION_JUMP_FORWARD = "io.github.m0n0t0ne0ne.colophon.JUMP_FORWARD"

/** The real player, for the app's screens: the session's player reports positions within a chapter. */
private object RawPlayer {
    var current: Player? = null
}

private fun mediaId(bookId: Long, index: Int) = "$bookId:$index"
internal fun parseId(id: String?): Pair<Long, Int>? {
    val parts = id?.split(':') ?: return null
    return (parts.getOrNull(0)?.toLongOrNull() ?: return null) to (parts.getOrNull(1)?.toIntOrNull() ?: return null)
}

/** Saves the position from here, so it keeps working when the app's screens are gone. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val durations = HashMap<Long, List<Long>>()
    private var saver: Job? = null
    private var sections: Job? = null
    private var raw: Player? = null
    private var sectionsBook: Long? = null

    private val sessionCallback = object : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_JUMP_BACK, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_JUMP_FORWARD, Bundle.EMPTY))
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player as? ChapterPlayer
            when (customCommand.customAction) {
                ACTION_JUMP_BACK -> player?.jump(-JUMP_MS)
                ACTION_JUMP_FORWARD -> player?.jump(JUMP_MS)
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Media3's default small icon is a generic play button, and launchers and
        // media widgets show this icon to identify the app that is playing.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(JUMP_MS)
            .setSeekForwardIncrementMs(JUMP_MS)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        raw = player
        RawPlayer.current = player
        val chapterPlayer = ChapterPlayer(player)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                scope.launch { save(player) }
                saver?.cancel()
                if (isPlaying) saver = scope.launch {
                    while (isActive) {
                        delay(SAVE_INTERVAL_MS)
                        save(player)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                watchSections(chapterPlayer, parseId(mediaItem?.mediaId)?.first)
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                val before = parseId(old.mediaItem?.mediaId) ?: return
                val after = parseId(new.mediaItem?.mediaId) ?: return
                if (before.first != after.first) return
                scope.launch {
                    val files = durationsFor(before.first)
                    if (files.isEmpty()) return@launch
                    val from = Timeline.global(files, before.second, old.positionMs)
                    val to = Timeline.global(files, after.second, new.positionMs)
                    if (abs(from - to) >= JUMP_WORTH_REMEMBERING_MS) {
                        val dao = ColophonDatabase.get(this@PlaybackService).dao()
                        dao.insertHistory(PositionHistory(bookId = before.first, positionMs = from, at = System.currentTimeMillis()))
                        dao.trimHistory(before.first)
                    }
                    save(player)
                }
            }
        })
        session = MediaSession.Builder(this, chapterPlayer)
            .setCallback(sessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCustomLayout(
                listOf(
                    jumpButton(CommandButton.ICON_SKIP_BACK_30, "Back 30 seconds", ACTION_JUMP_BACK),
                    jumpButton(CommandButton.ICON_SKIP_FORWARD_30, "Forward 30 seconds", ACTION_JUMP_FORWARD),
                ),
            )
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun jumpButton(icon: Int, name: String, action: String): CommandButton =
        CommandButton.Builder(icon)
            .setDisplayName(name)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .build()

    /** Keeps the controls in step with the book's chapters, including edits made while it plays. */
    private fun watchSections(player: ChapterPlayer, bookId: Long?) {
        if (bookId == sectionsBook) return
        sectionsBook = bookId
        sections?.cancel()
        player.setBook(null)
        if (bookId == null) return
        sections = scope.launch {
            val dao = ColophonDatabase.get(this@PlaybackService).dao()
            val files = dao.files(bookId).map { it.durationMs }
            val book = dao.book(bookId)
            dao.chaptersFlow(bookId, ChapterSet.CURRENT).collect { chapters ->
                val total = files.sum()
                player.setBook(
                    if (chapters.isNotEmpty()) {
                        BookSections(bookId, files, SectionMap(chapters.map { it.startMs }, total), chapters.map { it.title })
                    } else {
                        // Without chapters, each file is a section; a single file is just the book.
                        val starts = files.runningFold(0L) { sum, d -> sum + d }.dropLast(1)
                        val titles = if (files.size > 1) files.indices.map { "Part ${it + 1}" } else listOf(book?.title)
                        BookSections(bookId, files, SectionMap(starts, total), titles)
                    },
                )
            }
        }
    }

    private suspend fun durationsFor(bookId: Long): List<Long> =
        durations[bookId] ?: ColophonDatabase.get(this).dao().files(bookId).map { it.durationMs }.also { durations[bookId] = it }

    private suspend fun save(player: Player) {
        val (bookId, index) = parseId(player.currentMediaItem?.mediaId) ?: return
        val position = player.currentPosition
        val files = durationsFor(bookId)
        if (files.isEmpty()) return
        val global = Timeline.global(files, index, position)
        val finished = files.sum() - global < FINISHED_MARGIN_MS
        val dao = ColophonDatabase.get(this).dao()
        dao.savePosition(bookId, global, System.currentTimeMillis(), finished)
        // Playing a book again brings it back to Continue listening.
        if (player.isPlaying) dao.setHidden(bookId, false)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let { s ->
            raw?.let { player -> runBlocking { save(player) } }
            s.release()
            s.player.release()
        }
        RawPlayer.current = null
        raw = null
        session = null
        scope.cancel()
        super.onDestroy()
    }
}

data class PlayerState(
    val bookId: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val sleepLeftMs: Long? = null,
    val error: String? = null,
)

/** The screens' single handle on playback. */
class PlayerHub(private val context: Context) {
    private val dao = ColophonDatabase.get(context).dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var files: List<Long> = emptyList()
    private var sleepJob: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refresh()
                delay(500)
            }
        }
    }

    private suspend fun connected(): MediaController {
        controller?.let { if (it.isConnected) return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine<MediaController> { cont ->
            future.addListener(
                { runCatching { future.get() }.onSuccess { cont.resumeWith(Result.success(it)) }.onFailure { cont.cancel(it) } },
                ContextCompat.getMainExecutor(context),
            )
        }
        c.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(error = "This file couldn't be played. It may have been moved or damaged.", playing = false)
            }
        })
        controller = c
        return c
    }

    private fun refresh() {
        val connected = controller ?: return
        val c: Player = RawPlayer.current ?: connected
        val parsed = parseId(c.currentMediaItem?.mediaId) ?: return
        if (parsed.first != _state.value.bookId && files.isEmpty()) {
            scope.launch { files = dao.files(parsed.first).map { it.durationMs } }
            return
        }
        if (files.isEmpty()) return
        _state.value = _state.value.copy(
            bookId = parsed.first,
            positionMs = Timeline.global(files, parsed.second, c.currentPosition),
            durationMs = files.sum(),
            playing = c.isPlaying,
            speed = c.playbackParameters.speed,
        )
    }

    suspend fun open(bookId: Long, autoplay: Boolean, startAtMs: Long? = null) {
        val c = connected()
        val current = parseId(c.currentMediaItem?.mediaId)?.first
        if (current == bookId) {
            if (startAtMs != null) seekTo(startAtMs)
            if (autoplay) c.play()
            return
        }
        val book = dao.book(bookId) ?: return
        val bookFiles = dao.files(bookId)
        if (bookFiles.isEmpty()) return
        files = bookFiles.map { it.durationMs }
        val artwork = book.coverPath?.let { Uri.fromFile(File(it)) }
        val items = bookFiles.map { f ->
            MediaItem.Builder()
                .setMediaId(mediaId(bookId, f.idx))
                .setUri(f.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(book.title).setArtist(book.author).setArtworkUri(artwork).build(),
                )
                .build()
        }
        val away = if (book.lastPlayedAt > 0) System.currentTimeMillis() - book.lastPlayedAt else 0
        val start = startAtMs ?: if (book.finished) 0 else (book.positionMs - Resume.rewindMs(away)).coerceAtLeast(0)
        val (index, offset) = Timeline.locate(files, start)
        c.setMediaItems(items, index, offset)
        c.setPlaybackSpeed(book.speed)
        c.prepare()
        c.playWhenReady = autoplay
        _state.value = PlayerState(bookId = bookId, positionMs = start, durationMs = files.sum(), playing = autoplay, speed = book.speed)
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun pause() { controller?.pause() }

    fun seekTo(globalMs: Long) {
        val c = controller ?: return
        if (files.isEmpty()) return
        val (index, offset) = Timeline.locate(files, globalMs.coerceAtLeast(0))
        c.seekTo(index, offset)
        _state.value = _state.value.copy(positionMs = globalMs)
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _state.value.bookId?.let { id -> scope.launch { dao.saveSpeed(id, speed) } }
    }

    fun setSleep(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _state.value = _state.value.copy(sleepLeftMs = null)
            return
        }
        sleepJob = scope.launch {
            var left = minutes * 60_000L
            while (left > 0) {
                _state.value = _state.value.copy(sleepLeftMs = left)
                delay(1000)
                left -= 1000
            }
            controller?.pause()
            _state.value = _state.value.copy(sleepLeftMs = null)
        }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun forget(bookId: Long) {
        val c = controller ?: return
        if (parseId(c.currentMediaItem?.mediaId)?.first == bookId) {
            c.stop()
            c.clearMediaItems()
            files = emptyList()
            _state.value = PlayerState()
        }
    }
}
