package io.github.m0n0t0ne0ne.colophon.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.m0n0t0ne0ne.colophon.core.ChapterHeading
import io.github.m0n0t0ne0ne.colophon.core.Timeline
import io.github.m0n0t0ne0ne.colophon.core.formatClock
import io.github.m0n0t0ne0ne.colophon.data.Book
import io.github.m0n0t0ne0ne.colophon.data.Chapter
import io.github.m0n0t0ne0ne.colophon.playback.PlayerState
import java.io.File

private val OnArt = Color(0xFFF4F3EE)
private val OnArtSoft = OnArt.copy(alpha = 0.85f)

data class PlayerActions(
    val onBack: () -> Unit = {},
    val onSeries: () -> Unit = {},
    val onDetails: () -> Unit = {},
    val onChapters: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onSeekBy: (Long) -> Unit = {},
    val onSeekTo: (Long) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onSleep: (Int?) -> Unit = {},
    val onSleepChapter: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onBookmark: () -> Unit = {},
    val onMode: (BackgroundMode) -> Unit = {},
    val onStrength: (Float) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    book: Book,
    state: PlayerState,
    chapters: List<Chapter>,
    mode: BackgroundMode,
    strength: Float,
    colors: ArtColors,
    jumps: List<Long>,
    actions: PlayerActions,
) {
    var sheet by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sleepCustom by remember { mutableStateOf("") }

    val position = state.positionMs
    val duration = state.durationMs.coerceAtLeast(1)
    val starts = chapters.map { it.startMs }
    val index = if (chapters.isEmpty()) -1 else Timeline.chapterAt(starts, position)
    val chapter = chapters.getOrNull(index)
    val chapterStart = chapter?.startMs ?: 0L
    val chapterEnd = chapters.getOrNull(index + 1)?.startMs ?: duration
    val chapterLength = (chapterEnd - chapterStart).coerceAtLeast(1)
    val inChapter = (position - chapterStart).coerceIn(0, chapterLength)
    val accent = if (mode == BackgroundMode.Black) C.Accent else colors.accent

    Box(Modifier.fillMaxSize().background(C.Ground)) {
        Backdrop(book, mode, strength, colors)

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.offset(x = (-12).dp)) { IconBtn(Ic.Down, "Close player", OnArt, actions.onBack) }
                val series = book.series
                Text(
                    if (series != null) listOfNotNull(book.seriesNumber?.let { "Book ${it.toInt()}" }, series).joinToString(" · ") else book.author,
                    style = Small.copy(color = OnArtSoft, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(if (series != null) Modifier.clickable(onClick = actions.onSeries) else Modifier).padding(vertical = 12.dp),
                )
                Box(Modifier.offset(x = 12.dp)) {
                    IconBtn(Ic.More, "More options", OnArt) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Book details and cover") }, onClick = { menu = false; actions.onDetails() })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; actions.onSettings() })
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ThinProgress(position.toFloat() / duration, color = accent, track = OnArt.copy(alpha = 0.22f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatLong(position)} of ${formatLong(duration)}", style = Tiny.copy(color = OnArtSoft))
                    Text("${(position * 100 / duration)}% · ${formatLong(duration - position)} left", style = Tiny.copy(color = OnArtSoft))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BoxWithConstraints {
                    Cover(book, minOf(maxWidth, maxHeight))
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val heading = chapter?.let { ChapterHeading.of(it.title) }
                if (heading?.name != null) {
                    Text(
                        heading.label.uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = Tiny.copy(color = OnArtSoft, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    heading?.name ?: heading?.label ?: book.title, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TitleMedium.copy(color = OnArt, fontSize = 26.sp, lineHeight = 30.sp),
                )
                Spacer(Modifier.height(12.dp))
                Text(book.title, style = Small.copy(color = OnArtSoft, fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Column {
                SeekBar(
                    fraction = dragging ?: (inChapter.toFloat() / chapterLength),
                    accent = accent,
                    onScrub = { dragging = it },
                    onCommit = {
                        dragging?.let { actions.onSeekTo(chapterStart + (it * chapterLength).toLong()) }
                        dragging = null
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(inChapter), style = Tiny.copy(color = OnArtSoft))
                    Text("-${formatClock(chapterLength - inChapter)}", style = Tiny.copy(color = OnArtSoft))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Prev, "Previous chapter", OnArt) {
                    val target = if (inChapter > 3000 || index <= 0) chapterStart else chapters[index - 1].startMs
                    actions.onSeekTo(target)
                }
                SkipButton("-30", "Back 30 seconds") { actions.onSeekBy(-30_000) }
                IconButton(
                    onClick = actions.onToggle,
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = OnArt, contentColor = C.Ground),
                ) { Icon(if (state.playing) Ic.Pause else Ic.Play, if (state.playing) "Pause" else "Play", Modifier.size(26.dp)) }
                SkipButton("+30", "Forward 30 seconds") { actions.onSeekBy(30_000) }
                IconBtn(Ic.Next, "Next chapter", OnArt) {
                    chapters.getOrNull(index + 1)?.let { actions.onSeekTo(it.startMs) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.8f)
                Tool("Speed", Modifier.weight(1f), text = "${state.speed.toString().removeSuffix(".0")}x") {
                    actions.onSpeed(speeds[(speeds.indexOf(state.speed) + 1).mod(speeds.size)])
                }
                Tool("Chapters", Modifier.weight(1f), Ic.Chapters, onClick = actions.onChapters)
                Tool(state.sleepLeftMs?.let { formatClock(it) } ?: "Sleep", Modifier.weight(1f), Ic.Moon) { sheet = "sleep" }
                Tool("Bookmark", Modifier.weight(1f), Ic.Bookmark, onClick = actions.onBookmark)
                Tool("Backdrop", Modifier.weight(1f), Ic.Picture) { sheet = "background" }
            }
        }

        when (sheet) {
            "background" -> Sheet("Background", { sheet = null }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackgroundMode.entries.forEach { m -> ChoiceChip(m.label, m == mode, Modifier.weight(1f)) { actions.onMode(m) } }
                }
                if (mode != BackgroundMode.Black) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Strength", style = Small)
                        Text("${(strength * 100).toInt()}%", style = Small)
                    }
                    Slider(strength, actions.onStrength, colors = SliderDefaults.colors(thumbColor = OnArt, activeTrackColor = OnArt, inactiveTrackColor = C.Border))
                }
            }
            "sleep" -> Sheet("Sleep timer", { sheet = null }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { m ->
                        ChoiceChip("$m min", state.sleepChoice == m.toString(), Modifier.weight(1f)) { actions.onSleep(m); sheet = null }
                    }
                }
                if (chapter != null && chapterEnd > position) {
                    ChoiceChip("End of chapter", state.sleepChoice == "chapter", Modifier.fillMaxWidth()) { actions.onSleepChapter(); sheet = null }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        sleepCustom, { sleepCustom = it.filter(Char::isDigit).take(3) },
                        singleLine = true, placeholder = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("Start", Modifier.height(56.dp)) {
                        sleepCustom.toIntOrNull()?.takeIf { it > 0 }?.let { actions.onSleep(it); sleepCustom = ""; sheet = null }
                    }
                }
                if (state.sleepLeftMs != null) SecondaryButton("Turn off", Modifier.fillMaxWidth()) { actions.onSleep(null); sheet = null }
            }
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, accent: Color, onScrub: (Float) -> Unit, onCommit: () -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val thumbPx = with(LocalDensity.current) { 12.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onScrub((offset.x / width).coerceIn(0f, 1f)); onCommit() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onScrub((offset.x / width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ -> onScrub((change.position.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                )
            },
    ) {
        // The line and the thumb are both centred vertically, so the dot always sits on the line.
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(OnArt.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(accent))
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((fraction.coerceIn(0f, 1f) * width - thumbPx / 2f).toInt().coerceIn(0, (width - thumbPx).toInt().coerceAtLeast(0)), 0) }
                .size(12.dp).clip(CircleShape).background(OnArt),
        )
    }
}

private fun formatLong(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
}

@Composable
private fun Backdrop(book: Book, mode: BackgroundMode, strength: Float, colors: ArtColors) {
    val overlay = 0.82f - 0.32f * strength
    val hasArt = book.coverPath != null && File(book.coverPath).exists()
    when {
        mode == BackgroundMode.Art && hasArt -> {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context).data(File(book.coverPath!!)).size(if (Build.VERSION.SDK_INT >= 31) 256 else 24).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(44.dp) else Modifier),
            )
            Box(Modifier.fillMaxSize().background(Color(0xFF050608).copy(alpha = overlay)))
        }
        mode == BackgroundMode.Black -> Unit
        else -> {
            Box(Modifier.fillMaxSize().background(colors.tone))
            Box(Modifier.fillMaxSize().background(Color(0xFF050608).copy(alpha = overlay)))
        }
    }
}

@Composable
private fun SkipButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.size(52.dp).describe(description), shape = CircleShape, contentPadding = PaddingValues(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, OnArt.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnArt),
    ) { Text(label, fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Tool(label: String, modifier: Modifier, icon: ImageVector? = null, text: String? = null, onClick: () -> Unit) {
    Column(
        modifier.height(56.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = OnArt, modifier = Modifier.size(20.dp))
        else Text(text.orEmpty(), style = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = OnArt))
        Spacer(Modifier.height(4.dp))
        Text(label, style = TextStyle(fontFamily = Sans, fontSize = 11.sp, color = OnArt), maxLines = 1)
    }
}

@Composable
private fun BoxScope.Sheet(title: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onDone))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(C.Surface).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TitleMedium.copy(fontSize = 22.sp))
            TextButton(onClick = onDone) { Text("Done", color = C.Text, fontFamily = Sans, fontWeight = FontWeight.SemiBold) }
        }
        content()
    }
}
