package io.github.m0n0t0ne0ne.colophon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0n0t0ne0ne.colophon.core.Timeline
import io.github.m0n0t0ne0ne.colophon.data.Book
import io.github.m0n0t0ne0ne.colophon.data.Bookmark
import io.github.m0n0t0ne0ne.colophon.data.Chapter

private fun hms(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
}

/** A readable chapter length, e.g. "2 min", "47 min", "1h 12m". */
private fun lenLabel(ms: Long): String {
    val mins = (ms.coerceAtLeast(0) + 30_000) / 60_000
    return when {
        mins < 1 -> "under a minute"
        mins < 60 -> "$mins min"
        else -> "${mins / 60}h ${mins % 60}m"
    }
}

@Composable
fun ChaptersContent(
    current: List<Chapter>,
    original: List<Chapter>,
    bookmarks: List<Bookmark>,
    positionMs: Long,
    durationMs: Long,
    onBack: () -> Unit,
    onPlayAt: (Long) -> Unit,
    onEdit: (Chapter) -> Unit,
    onReset: () -> Unit,
    onPadNumbers: (Int) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onSaveNote: (Long, String?) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<Bookmark?>(null) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val currentIndex = if (current.isEmpty()) 0 else Timeline.chapterAt(current.map { it.startMs }, positionMs)
    // Scroll to the playing chapter only on first open; keep the reader's place after editing a chapter.
    var scrolledToCurrent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(current.size) {
        if (!scrolledToCurrent && current.isNotEmpty()) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
            scrolledToCurrent = true
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        HeaderRow("Chapters", onBack) {
            Box {
                IconBtn(Ic.More, "Chapter options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("No number padding (1, 2…)") }, onClick = { menu = false; onPadNumbers(1) })
                    DropdownMenuItem(text = { Text("Pad numbers to 01, 02…") }, onClick = { menu = false; onPadNumbers(2) })
                    DropdownMenuItem(text = { Text("Pad numbers to 001, 002…") }, onClick = { menu = false; onPadNumbers(3) })
                }
            }
        }
        UnderlineTabs(listOf("Your chapters", "Original file", "Bookmarks"), tab) { tab = it }

        when (tab) {
            0 -> {
                LazyColumn(Modifier.weight(1f), listState) {
                    itemsIndexed(current, key = { _, c -> c.id }) { i, c ->
                        val active = i == currentIndex
                        val length = (current.getOrNull(i + 1)?.startMs ?: durationMs) - c.startMs
                        Row(
                            Modifier.fillMaxWidth().background(if (active) C.Surface else androidx.compose.ui.graphics.Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f).heightIn(min = 54.dp).clickable { onPlayAt(c.startMs) }.padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(c.title, style = Body.copy(color = if (active) C.Accent else C.Text))
                                    Text(lenLabel(length), style = Tiny.copy(color = C.Muted))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(hms(c.startMs), style = Tiny)
                            }
                            IconBtn(Ic.Edit, "Edit ${c.title}", C.Muted) { onEdit(c) }
                        }
                        HorizontalDivider(color = C.Line)
                    }
                }
                SecondaryButton("Reset to original", Modifier.fillMaxWidth().padding(vertical = 10.dp).navigationBarsPadding(), 48.dp) { confirmReset = true }
            }
            1 -> LazyColumn(Modifier.fillMaxSize()) {
                items(original, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPlayAt(c.startMs) }.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.title, style = Body, modifier = Modifier.weight(1f))
                        Text(hms(c.startMs), style = Tiny)
                    }
                    HorizontalDivider(color = C.Line)
                }
            }
            else -> {
                val starts = current.map { it.startMs }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (bookmarks.isEmpty()) item { Text("Tap Bookmark in the player to save a moment.", style = Small, modifier = Modifier.padding(vertical = 20.dp)) }
                    items(bookmarks, key = { it.id }) { b ->
                        val chapter = if (starts.isEmpty()) null else current.getOrNull(Timeline.chapterAt(starts, b.positionMs))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).clickable { onPlayAt(b.positionMs) }.padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(hms(b.positionMs), style = Body)
                                    chapter?.let { Text(it.title, style = Tiny.copy(color = C.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                b.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = Small.copy(color = C.Text)) }
                            }
                            IconBtn(Ic.Edit, "Edit note", C.Muted) { editingNote = b }
                            IconBtn(Ic.Close, "Delete bookmark", C.Muted) { onDeleteBookmark(b.id) }
                        }
                        HorizontalDivider(color = C.Line)
                    }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset chapters?") },
            text = { Text("Your edits for this book will be replaced by the file's original chapters.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Reset", color = C.Warn) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    editingNote?.let { bookmark ->
        var text by remember(bookmark.id) { mutableStateOf(bookmark.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("Bookmark note") },
            text = {
                OutlinedTextField(
                    text, { text = it }, placeholder = { Text("What happens here?") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
                )
            },
            confirmButton = { TextButton(onClick = { onSaveNote(bookmark.id, text.trim().ifBlank { null }); editingNote = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingNote = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun EditorContent(
    chapter: Chapter,
    book: Book,
    siblings: List<Chapter>,
    onBack: () -> Unit,
    onSave: (Chapter) -> Unit,
    onOpenBook: () -> Unit,
    onPlay: (Long) -> Unit,
    onEditChapter: (Chapter) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(chapter.title) }
    val index = siblings.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0)
    val prev = siblings.getOrNull(index - 1)
    val next = siblings.getOrNull(index + 1)
    val duration = book.durationMs.coerceAtLeast(1)
    val start = chapter.startMs
    val end = next?.startMs ?: book.durationMs
    val fraction = (start.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        HeaderRow("Edit chapter", onBack) {
            IconBtn(Ic.Undo, "Undo changes") { title = chapter.title }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(2.dp))
            Field("Title", title) { title = it }

            // Book the chapter belongs to — tap to open the player.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenBook),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(book, 60.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(book.title, style = BookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(book.author.ifBlank { null }, "Chapter ${index + 1} of ${siblings.size}").joinToString(" · "),
                        style = Small,
                    )
                }
            }

            // Where the chapter sits in the book.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(C.Raised).padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(lenLabel(end - start), style = Body)
                    Text("${hms(start)} → ${hms(end)}", style = Small)
                }
                ThinProgress(fraction)
                Text("${(fraction * 100).toInt()}% through the book", style = Tiny, modifier = Modifier.align(Alignment.End))
            }

            prev?.let { NeighborRow("Edit previous", it, onEditChapter) }
            next?.let { NeighborRow("Edit next", it, onEditChapter) }

            HorizontalDivider(color = C.Line)
            OutlinedButton(
                onClick = { onPlay(start) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(9.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, C.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text),
            ) {
                Icon(Ic.Play, null, Modifier.size(15.dp))
                Spacer(Modifier.width(9.dp))
                Text("Play this chapter", style = Body)
            }
        }
        Text("Saved separately. Your audio file isn't changed.", style = Small, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
        PrimaryButton("Save", Modifier.fillMaxWidth().padding(vertical = 12.dp).navigationBarsPadding()) {
            onSave(chapter.copy(title = title.trim().ifEmpty { chapter.title }))
        }
    }
}

@Composable
private fun NeighborRow(label: String, chapter: Chapter, onClick: (Chapter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick(chapter) }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), style = Tiny.copy(letterSpacing = 0.8.sp))
            Text(chapter.title, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(hms(chapter.startMs), style = Tiny)
        Icon(Ic.Forward, null, tint = C.Muted, modifier = Modifier.size(18.dp))
    }
}
