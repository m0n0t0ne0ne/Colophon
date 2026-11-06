package io.github.m0n0t0ne0ne.colophon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.m0n0t0ne0ne.colophon.R
import io.github.m0n0t0ne0ne.colophon.data.Book
import java.io.File

object C {
    val Ground = Color(0xFF0B0C0E)
    val Surface = Color(0xFF15171A)
    val Raised = Color(0xFF22262A)
    val Line = Color(0xFF26292D)
    val Border = Color(0xFF3A3E42)
    val Text = Color(0xFFECEBE6)
    val Muted = Color(0xFFA2A6AA)
    val Accent = Color(0xFF9CC2DE)
    val Warn = Color(0xFFD9A95B)
    val tones = listOf(0xFF1C2733, 0xFF2E1A1C, 0xFF1B2A24, 0xFF2B2418, 0xFF251C2E, 0xFF22262B).map { Color(it) }
}

val Serif = FontFamily(Font(R.font.newsreader))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Sans = FontFamily(
    Font(R.font.hanken, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.hanken, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.hanken, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

@Composable
fun ColophonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Text, onPrimary = C.Ground, background = C.Ground, onBackground = C.Text,
            surface = C.Surface, onSurface = C.Text, surfaceVariant = C.Raised, onSurfaceVariant = C.Muted,
            outline = C.Border, secondary = C.Accent, error = C.Warn,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp),
            bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp),
            bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp),
            labelLarge = TextStyle(fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}

val TitleLarge = TextStyle(fontFamily = Serif, fontSize = 34.sp, lineHeight = 38.sp, color = C.Text)
val HeaderTitle = TextStyle(fontFamily = Serif, fontSize = 40.sp, lineHeight = 44.sp, color = C.Text)
val TitleMedium = TextStyle(fontFamily = Serif, fontSize = 24.sp, lineHeight = 28.sp, color = C.Text)
val BookTitle = TextStyle(fontFamily = Serif, fontSize = 19.sp, lineHeight = 23.sp, color = C.Text)
val Body = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, color = C.Text)
val Small = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp, color = C.Muted)
val Tiny = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, color = C.Muted)

/** Builds an icon from an SVG path on a 24-unit grid. */
private fun fill(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(d), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.White)).build()

private fun line(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(d), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

object Ic {
    val Back = line("back", "M15 6l-6 6 6 6")
    val Down = line("down", "M6 9l6 6 6-6")
    val Forward = line("forward", "M9 6l6 6-6 6")
    val Search = line("search", "M18 11a7 7 0 1 1-14 0a7 7 0 0 1 14 0zM20 20l-4-4")
    val Refresh = line("refresh", "M20 12a8 8 0 1 1-2.4-5.7L20 8.5M20 4v4.5h-4.5")
    val Add = line("add", "M12 5v14M5 12h14")
    val More = fill("more", "M12 3.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 0 1 0-3.2zM12 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 0 1 0-3.2zM12 17.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 0 1 0-3.2z")
    val Play = fill("play", "M7 4l14 8-14 8z")
    val Pause = fill("pause", "M6 4h4v16H6zM14 4h4v16h-4z")
    val Prev = fill("prev", "M20 5v14L9 12zM5 5h1.8v14H5z")
    val Next = fill("next", "M4 5v14l11-7zM17.2 5H19v14h-1.8z")
    val Library = line("library", "M5 4v16M10 4v16M15 4l5 16")
    val Series = line("series", "M4 8h12a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM7 4h13a1 1 0 0 1 1 1v12")
    val Wave = line("wave", "M4 10v4M8 6v12M12 3v18M16 7v10M20 10v4")
    val Chapters = line("chapters", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01")
    val Moon = line("moon", "M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z")
    val Bookmark = line("bookmark", "M6 3h12v18l-6-4-6 4z")
    val Book = line("book", "M12 7c-2.2-1.4-5.3-1.4-7.5 0v11c2.2-1.4 5.3-1.4 7.5 0M12 7c2.2-1.4 5.3-1.4 7.5 0v11c-2.2-1.4-5.3-1.4-7.5 0M12 7v11")
    val Picture = line("picture", "M4.5 4h15A1.5 1.5 0 0 1 21 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5v-13A1.5 1.5 0 0 1 4.5 4zM3 16l5-5 4 4 3-3 6 6")
    val Edit = line("edit", "M4 20h4L19 9l-4-4L4 16z")
    val Undo = line("undo", "M9 7L4 12l5 5M4 12h10a6 6 0 0 1 6 6")
    val Close = line("close", "M6 6l12 12M18 6L6 18")
}

@Composable
fun IconBtn(icon: ImageVector, label: String, tint: Color = C.Text, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Cover(book: Book, size: Dp, modifier: Modifier = Modifier, numberOnly: Boolean = false, plain: Boolean = false) {
    val shape = RoundedCornerShape(if (size < 80.dp) 3.dp else 4.dp)
    val path = book.coverPath
    if (path != null && File(path).exists()) {
        AsyncImage(
            model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).background(C.Surface),
        )
    } else {
        val tone = C.tones[((book.seriesNumber?.toInt() ?: book.id.toInt()).mod(C.tones.size))]
        Box(modifier.size(size).clip(shape).background(tone).padding(if (size < 80.dp) 2.dp else 10.dp), contentAlignment = if (numberOnly) Alignment.Center else Alignment.BottomStart) {
            val number = book.seriesNumber?.let { if (it == 0.0) "P" else if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
            if (plain) {
                Spacer(Modifier.size(1.dp))
            } else if (numberOnly && number != null) {
                Text(number, style = TextStyle(fontFamily = Serif, fontSize = 20.sp, color = C.Text))
            } else {
                Text(
                    book.title, maxLines = if (size < 80.dp) 3 else 5, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Serif, fontSize = if (size < 80.dp) 9.sp else (size.value / 7).sp, lineHeight = if (size < 80.dp) 10.sp else (size.value / 6).sp, color = C.Text),
                )
            }
        }
    }
}

@Composable
fun UnderlineTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items.forEachIndexed { i, label ->
                Column(
                    Modifier.height(48.dp).clickable(role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(label, style = Body.copy(fontWeight = FontWeight.Medium, color = if (i == selected) C.Text else C.Muted))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.width(IntrinsicSize.Min).height(2.dp).fillMaxWidth().background(if (i == selected) C.Text else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = C.Line)
    }
}

@Composable
fun HeaderRow(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(if (onBack != null) 56.dp else 76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(Modifier.offset(x = (-12).dp)) { IconBtn(Ic.Back, "Back", onClick = onBack) }
            Text(title, style = TitleMedium, modifier = Modifier.weight(1f).offset(x = (-8).dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(title, style = HeaderTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The action icons sit a touch higher than centre so they align with the large title's cap height.
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

enum class Tab(val label: String) { Library("Library"), Books("Books"), Series("Series"), Playing("Playing"), Analyzer("Analyzer") }

@Composable
fun BottomBar(current: Tab?, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.Ground)) {
        HorizontalDivider(color = C.Line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            Tab.entries.forEach { tab ->
                val active = tab == current
                val tint = if (active) C.Text else C.Muted
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        when (tab) { Tab.Library -> Ic.Library; Tab.Books -> Ic.Book; Tab.Series -> Ic.Series; Tab.Playing -> Ic.Play; Tab.Analyzer -> Ic.Wave },
                        contentDescription = null, tint = tint, modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(tab.label, style = TextStyle(fontFamily = Sans, fontSize = 11.sp, color = tint))
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.Text, contentColor = C.Ground),
    ) { Text(text, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, height: Dp = 52.dp, tint: Color = C.Text, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(height), shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, C.Border), contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
    ) { Text(text, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1) }
}

@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = C.Accent, track: Color = Color(0xFF2F3337)) {
    Box(modifier.fillMaxWidth().height(2.dp).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) C.Text else Color.Transparent)
            .border(1.dp, if (selected) C.Text else C.Border, RoundedCornerShape(6.dp))
            .clickable(role = Role.RadioButton) { onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Small.copy(fontWeight = FontWeight.Medium, color = if (selected) C.Ground else C.Text), maxLines = 1) }
}

fun Modifier.describe(text: String) = semantics { contentDescription = text; role = Role.Button }
