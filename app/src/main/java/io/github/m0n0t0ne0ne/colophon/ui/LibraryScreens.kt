package io.github.m0n0t0ne0ne.colophon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0n0t0ne0ne.colophon.core.formatLeft
import io.github.m0n0t0ne0ne.colophon.data.Book

data class SeriesGroup(val name: String, val books: List<Book>) {
    val finished get() = books.count { it.finished }
    val author get() = books.firstOrNull { it.author.isNotBlank() }?.author.orEmpty()
}

fun groupSeries(books: List<Book>): List<SeriesGroup> =
    books.filter { !it.series.isNullOrBlank() }.groupBy { it.series!! }
        .map { (name, list) -> SeriesGroup(name, list) }.sortedBy { it.name.lowercase() }

fun inProgress(books: List<Book>): List<Book> =
    books.filter { it.positionMs > 0 && !it.finished }.sortedByDescending { it.lastPlayedAt }

@Composable
fun LibraryContent(
    books: List<Book>,
    initialTab: Int = 0,
    importing: Boolean = false,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (String) -> Unit,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
) {
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val filtered = books.filter {
        query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true) || it.series?.contains(query, true) == true
    }
    val groups = groupSeries(filtered)
    val standalone = filtered.filter { it.series.isNullOrBlank() }
    val listening = inProgress(filtered)

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        HeaderRow("Library", null) {
            IconBtn(Ic.Search, "Search library") { searching = !searching; if (!searching) query = "" }
            Box {
                IconBtn(Ic.Add, "Add audiobooks") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Add a folder") }, onClick = { menu = false; onAddFolder() })
                    DropdownMenuItem(text = { Text("Add files") }, onClick = { menu = false; onAddFiles() })
                }
            }
        }
        if (searching) {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Title, author, or series") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        UnderlineTabs(listOf("All", "Series", "Books"), tab) { tab = it }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = C.Accent, trackColor = C.Line)

        if (books.isEmpty()) {
            EmptyLibrary(onAddFolder, onAddFiles)
            return@Column
        }

        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (tab == 0 && listening.isNotEmpty()) {
                header("Continue listening")
                items(listening.take(5), span = { GridItemSpan(3) }) { ContinueRow(it) { onOpenBook(it) } }
            }
            if (tab != 2 && groups.isNotEmpty()) {
                header("Series")
                items(groups, span = { GridItemSpan(3) }) { SeriesRow(it) { onOpenSeries(it.name) } }
            }
            if (tab != 1 && standalone.isNotEmpty()) {
                header("Standalone books")
                items(standalone) { book ->
                    Column(Modifier.clickable { onOpenBook(book) }) {
                        BoxWithConstraints { Cover(book, maxWidth) }
                        Spacer(Modifier.height(6.dp))
                        Text(book.author.ifBlank { book.title }, style = Tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.header(title: String) {
    item(span = { GridItemSpan(3) }) { Text(title, style = Small, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun EmptyLibrary(onAddFolder: () -> Unit, onAddFiles: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 72.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your books, your files.", style = TitleMedium)
        Text("Add DRM-free audiobooks from this phone. M4B, MP3, M4A, FLAC, Ogg, Opus, AAC, and WAV all work.", style = Small)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Add a folder", Modifier.fillMaxWidth(), onClick = onAddFolder)
        SecondaryButton("Add files", Modifier.fillMaxWidth(), onClick = onAddFiles)
    }
}

@Composable
private fun ContinueRow(book: Book, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(book, 88.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = BookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.series?.let { Text(it, style = Small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(4.dp))
            val f = if (book.durationMs > 0) book.positionMs.toFloat() / book.durationMs else 0f
            ThinProgress(f)
            Text("${(f * 100).toInt()}% · ${formatLeft(book.durationMs - book.positionMs)} left", style = Tiny)
        }
    }
}

@Composable
private fun SeriesRow(group: SeriesGroup, onClick: () -> Unit) {
    Column {
        HorizontalDivider(color = C.Line)
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp, 72.dp)) {
                val fan = group.books.sortedByDescending { it.coverPath != null }.take(3)
                fan.reversed().forEachIndexed { i, b ->
                    val index = fan.size - 1 - i
                    val size = (72 - index * 6).dp
                    Cover(b, size, Modifier.offset(x = (index * 22).dp, y = (index * 3).dp), plain = index > 0)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(group.name, style = BookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${group.books.size} books · ${group.finished} finished", style = Small)
            }
            Icon(Ic.Forward, null, tint = C.Muted, modifier = Modifier.size(18.dp))
        }
        HorizontalDivider(color = C.Line)
    }
}

@Composable
fun SeriesContent(
    name: String,
    books: List<Book>,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onEdit: (Book) -> Unit,
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val author = books.firstOrNull { it.author.isNotBlank() }?.author
    val ordered = if (mode == 0) {
        books.sortedWith(compareBy<Book> { it.year ?: Int.MAX_VALUE }.thenBy { it.seriesNumber ?: Double.MAX_VALUE }.thenBy { it.title })
    } else {
        books.sortedWith(compareBy<Book> { it.seriesNumber ?: Double.MAX_VALUE }.thenBy { it.title })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        HeaderRow("", onBack)
        Text(name, style = TitleLarge)
        Text(listOfNotNull(author, "${books.size} books").joinToString(" · "), style = Small, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        UnderlineTabs(listOf("Publication order", "Series order"), mode) { mode = it }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ordered, key = { it.id }) { book ->
                Row(Modifier.fillMaxWidth().clickable { onOpenBook(book) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(book, 56.dp, numberOnly = true)
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = BookTitle.copy(fontSize = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            book.seriesNumber?.let { if (it == 0.0) "Prequel" else "Book ${if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()}" },
                            book.year?.toString(),
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = Small)
                    }
                    val percent = if (book.durationMs > 0) (book.positionMs * 100 / book.durationMs).toInt() else 0
                    when {
                        book.finished -> Text("Finished", style = Tiny)
                        book.positionMs > 0 -> Text("$percent%", style = Tiny.copy(color = C.Accent))
                    }
                    IconBtn(Ic.Edit, "Edit ${book.title}", tint = C.Muted) { onEdit(book) }
                }
                HorizontalDivider(color = Color0)
            }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color(0xFF1C1F22)

@Composable
fun DetailsScreen(
    book: Book,
    onBack: () -> Unit,
    onPickCover: (((String?) -> Unit)) -> Unit,
    onSave: (Book, Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(book.title) }
    var author by rememberSaveable { mutableStateOf(book.author) }
    var series by rememberSaveable { mutableStateOf(book.series.orEmpty()) }
    var number by rememberSaveable { mutableStateOf(book.seriesNumber?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()) }
    var year by rememberSaveable { mutableStateOf(book.year?.toString().orEmpty()) }
    var cover by rememberSaveable { mutableStateOf(book.coverPath) }
    var whole by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        HeaderRow("Book details", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                Cover(book.copy(coverPath = cover, title = title), 150.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Change cover", Modifier.fillMaxWidth()) { onPickCover { it?.let { p -> cover = p } } }
                    if (cover != book.coverPath) SecondaryButton("Undo", Modifier.fillMaxWidth()) { cover = book.coverPath }
                }
            }
            Field("Title", title) { title = it }
            Field("Author", author) { author = it }
            Field("Series", series) { series = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Book number (0 for prequel)", number, Modifier.weight(1f), KeyboardType.Decimal) { number = it }
                Field("Year", year, Modifier.width(96.dp), KeyboardType.Number) { year = it }
            }
            if (series.isNotBlank() && cover != book.coverPath) {
                UnderlineTabs(listOf("Cover: this book", "Whole series"), whole) { whole = it }
            }
            TextButton(onClick = { confirm = true }) { Text("Remove from library", color = C.Warn) }
            Text("This removes the book from Colophon. Your audio file stays on your phone.", style = Tiny)
        }
        PrimaryButton("Save", Modifier.fillMaxWidth().padding(vertical = 12.dp).navigationBarsPadding()) {
            onSave(
                book.copy(
                    title = title.trim().ifEmpty { book.title }, author = author.trim(), series = series.trim().ifEmpty { null },
                    seriesNumber = number.toDoubleOrNull(), year = year.toIntOrNull(), coverPath = cover,
                ),
                whole == 1,
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove this book?") },
            text = { Text("Your position, bookmarks, and chapter edits for this book will be deleted. The audio file stays on your phone.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remove", color = C.Warn) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = RoundedCornerShape(6.dp),
    )
}
