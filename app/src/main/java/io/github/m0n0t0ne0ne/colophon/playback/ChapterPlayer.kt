package io.github.m0n0t0ne0ne.colophon.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.m0n0t0ne0ne.colophon.core.ChapterHeading
import io.github.m0n0t0ne0ne.colophon.core.SectionMap
import io.github.m0n0t0ne0ne.colophon.core.Timeline

/** What the media controls need to know about the book that is playing. */
data class BookSections(
    val bookId: Long,
    val fileDurations: List<Long>,
    val map: SectionMap,
    val titles: List<String?>,
)

/**
 * Makes each chapter look like its own track to the session, so the notification, widgets
 * and headset buttons show and control the current chapter rather than the whole file.
 * Absolute seeks by file index still pass straight through, which the app's screens rely on.
 */
@OptIn(UnstableApi::class)
class ChapterPlayer(private val raw: Player) : ForwardingPlayer(raw) {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Player.Listener>()
    private var book: BookSections? = null
    private var published: Pair<Long, Int>? = null

    private val publishPending = Runnable { publish() }
    private val ticker = object : Runnable {
        override fun run() {
            val current = section()
            if (current != null && current != published) publish()
            if (raw.isPlaying) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        raw.addListener(object : Player.Listener {
            // Runs after the raw event reaches the session, so these values are the last it sees.
            override fun onEvents(player: Player, events: Player.Events) {
                handler.removeCallbacks(publishPending)
                handler.post(publishPending)
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    handler.removeCallbacks(ticker)
                    if (raw.isPlaying) handler.post(ticker)
                }
            }
        })
    }

    fun setBook(sections: BookSections?) {
        book = sections
        publish()
    }

    /** The loaded book, only while it is the one actually playing. */
    private fun active(): BookSections? {
        val id = parseId(raw.currentMediaItem?.mediaId)?.first
        return book?.takeIf { it.bookId == id && it.fileDurations.isNotEmpty() }
    }

    private fun globalPosition(sections: BookSections): Long {
        val index = parseId(raw.currentMediaItem?.mediaId)?.second ?: 0
        return Timeline.global(sections.fileDurations, index, raw.currentPosition)
    }

    private fun section(): Pair<Long, Int>? = active()?.let { it.bookId to it.map.indexAt(globalPosition(it)) }

    private fun seekGlobal(sections: BookSections, globalMs: Long) {
        val (index, offset) = Timeline.locate(sections.fileDurations, globalMs.coerceIn(0, sections.map.totalMs))
        raw.seekTo(index, offset)
    }

    private fun publish() {
        published = section()
        val metadata = mediaMetadata
        val commands = availableCommands
        val position = Player.PositionInfo(
            null, currentMediaItemIndex, currentMediaItem, null, currentPeriodIndex,
            currentPosition, currentPosition, -1, -1,
        )
        listeners.toList().forEach {
            it.onAvailableCommandsChanged(commands)
            it.onMediaMetadataChanged(metadata)
            it.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_INTERNAL)
        }
    }

    fun jump(deltaMs: Long) {
        val sections = active()
        if (sections != null) seekGlobal(sections, globalPosition(sections) + deltaMs)
        else if (deltaMs < 0) raw.seekBack() else raw.seekForward()
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        listeners.clear()
        super.release()
    }

    override fun getMediaMetadata(): MediaMetadata {
        val metadata = super.getMediaMetadata()
        val sections = active() ?: return metadata
        val index = sections.map.indexAt(globalPosition(sections))
        val title = sections.titles.getOrNull(index) ?: return metadata
        val heading = ChapterHeading.of(title)
        // A book without chapters keeps its author on the second line.
        val second = heading.name ?: metadata.title?.takeUnless { it.toString() == title } ?: metadata.artist
        return metadata.buildUpon().setTitle(heading.label).setArtist(second).build()
    }

    private fun inSection(sections: BookSections, globalMs: Long): Long {
        val index = sections.map.indexAt(globalPosition(sections))
        val start = sections.map.startOf(index)
        return (globalMs - start).coerceIn(0, sections.map.endOf(index) - start)
    }

    override fun getCurrentPosition(): Long =
        active()?.let { inSection(it, globalPosition(it)) } ?: super.getCurrentPosition()

    override fun getContentPosition(): Long = currentPosition

    override fun getBufferedPosition(): Long = active()?.let {
        inSection(it, globalPosition(it) + (raw.bufferedPosition - raw.currentPosition).coerceAtLeast(0))
    } ?: super.getBufferedPosition()

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun getDuration(): Long = active()?.let {
        val index = it.map.indexAt(globalPosition(it))
        it.map.endOf(index) - it.map.startOf(index)
    } ?: super.getDuration()

    override fun getContentDuration(): Long = duration

    override fun seekTo(positionMs: Long) {
        val sections = active() ?: return super.seekTo(positionMs)
        val index = sections.map.indexAt(globalPosition(sections))
        val target = sections.map.startOf(index) + positionMs
        seekGlobal(sections, target.coerceAtMost(sections.map.endOf(index) - 1))
    }

    override fun seekBack() = jump(-raw.seekBackIncrement)
    override fun seekForward() = jump(raw.seekForwardIncrement)

    private fun skipNext() {
        val sections = active() ?: return super.seekToNext()
        sections.map.next(globalPosition(sections))?.let { seekGlobal(sections, it) }
    }

    private fun skipPrevious() {
        val sections = active() ?: return super.seekToPrevious()
        sections.map.previous(globalPosition(sections))?.let { seekGlobal(sections, it) }
    }

    override fun seekToNext() = skipNext()
    override fun seekToNextMediaItem() = skipNext()
    override fun seekToPrevious() = skipPrevious()
    override fun seekToPreviousMediaItem() = skipPrevious()

    override fun hasNextMediaItem(): Boolean = active()?.let { it.map.next(globalPosition(it)) != null } ?: super.hasNextMediaItem()
    override fun hasPreviousMediaItem(): Boolean = active()?.let { it.map.count > 1 } ?: super.hasPreviousMediaItem()

    // The buttons stay available on single-file books, and in the last chapter, so the controls don't shift around.
    override fun isCommandAvailable(command: Int): Boolean =
        (active() != null && command in SKIP_COMMANDS) || super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        if (active() == null) return commands
        return commands.buildUpon().addAll(*SKIP_COMMANDS).build()
    }

    private companion object {
        const val TICK_MS = 500L
        val SKIP_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
