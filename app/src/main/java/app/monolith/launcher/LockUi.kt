package app.monolith.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LockScreen(onUnlock: () -> Unit, onOpen: (PendingIntent?) -> Unit) {
    val ctx = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val battery = rememberBattery()
    val now = rememberNow()
    val is24 = DateFormat.is24HourFormat(ctx)
    val prefs = remember { ctx.getSharedPreferences("teddy", Context.MODE_PRIVATE) }
    val dim = DimLevels[prefs.getInt("dim", 2) % DimLevels.size].coerceAtLeast(0.35f)
    val showText = prefs.getBoolean("show_text", false)
    var drag by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { 140.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.4f),
                    0.38f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.5f),
                )
            )
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> drag = (drag + d).coerceAtMost(0f) },
                onDragStopped = { v ->
                    if (drag < -thresholdPx || v < -1500f) onUnlock()
                    drag = 0f
                },
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = drag * 0.5f
                    alpha = (1f + drag / 900f).coerceIn(0.2f, 1f)
                }
        ) {
            if (landscape) {
                LockLandscape(now, is24, battery)
            } else {
                LockPortrait(now, is24, battery, showText, onOpen)
            }
        }
        UnlockHandle(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 22.dp),
        )
    }
}

@Composable
private fun UnlockHandle(modifier: Modifier) {
    val accent = Color(rememberWallpaperAccent())
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "h")
    val bob by t.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "bob",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .graphicsLayer { translationY = bob * density }
                .drawBehind {
                    for (i in 3 downTo 1) {
                        val g = i * 3.dp.toPx()
                        drawRoundRect(
                            color = accent.copy(alpha = 0.10f * (4 - i)),
                            topLeft = Offset(-g, -g),
                            size = Size(size.width + g * 2, size.height + g * 2),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + g),
                        )
                    }
                }
                .size(width = 46.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "SWIPE UP",
            style = TextStyle(
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
fun rememberWallpaperAccent(): Int {
    val ctx = LocalContext.current
    return remember {
        var out = 0xFFFAC775.toInt()
        if (android.os.Build.VERSION.SDK_INT >= 27) runCatching {
            val wm = android.app.WallpaperManager.getInstance(ctx)
            val c = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
            if (c != null) {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(c.primaryColor.toArgb(), hsv)
                hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
                hsv[2] = 1f
                out = android.graphics.Color.HSVToColor(hsv)
            }
        }
        out
    }
}

@Composable
private fun LockPortrait(
    @Suppress("UNUSED_PARAMETER") nowMinute: LocalDateTime,
    is24: Boolean,
    @Suppress("UNUSED_PARAMETER") battery: Battery,
    showText: Boolean,
    onOpen: (PendingIntent?) -> Unit,
) {
    val now = rememberNowSeconds()
    val accent = Color(rememberWallpaperAccent())
    val shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), 12f)
    val serif = androidx.compose.ui.text.font.FontFamily.Serif
    val italic = androidx.compose.ui.text.font.FontStyle.Italic
    val tnum = "tnum"
    val sec = now.second
    val hh = now.format(DateTimeFormatter.ofPattern(if (is24) "HH" else "hh"))
    val mm = now.format(DateTimeFormatter.ofPattern("mm"))

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = now.format(DateTimeFormatter.ofPattern("EEEE")),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = serif,
                fontStyle = italic,
                fontSize = 20.sp,
                shadow = shadow,
            ),
        )
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                for (i in 0 until 60) {
                    val a = (i / 60f) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                    val major = i % 5 == 0
                    val r1 = (if (major) 100f else 106f) * density
                    val r2 = 116f * density
                    val col = when {
                        i < sec -> accent.copy(alpha = 0.9f)
                        i == sec -> Color.White
                        else -> Color.White.copy(alpha = 0.28f)
                    }
                    drawLine(
                        color = col,
                        start = Offset(c.x + kotlin.math.cos(a) * r1, c.y + kotlin.math.sin(a) * r1),
                        end = Offset(c.x + kotlin.math.cos(a) * r2, c.y + kotlin.math.sin(a) * r2),
                        strokeWidth = (if (major) 2f else 1.4f) * density,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = hh,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 78.sp,
                        lineHeight = 68.sp,
                        fontWeight = FontWeight.Thin,
                        letterSpacing = (-2).sp,
                        fontFeatureSettings = tnum,
                        shadow = shadow,
                    ),
                )
                Text(
                    text = mm,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 78.sp,
                        lineHeight = 76.sp,
                        fontWeight = FontWeight.Thin,
                        letterSpacing = (-2).sp,
                        fontFeatureSettings = tnum,
                        shadow = shadow,
                    ),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = now.dayOfMonth.toString() + " " + now.format(DateTimeFormatter.ofPattern("MMMM")).lowercase(),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.8f),
                fontFamily = serif,
                fontStyle = italic,
                fontSize = 17.sp,
                shadow = shadow,
            ),
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 32.dp)) {
            LockState.media?.let {
                MediaCard(it)
                Spacer(Modifier.height(16.dp))
            }
            LockState.notifications.take(4).forEach { n ->
                NotifRow(n, showText) { onOpen(n.intent) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LockLandscape(now: LocalDateTime, is24: Boolean, battery: Battery) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DialClock(
            now = now,
            is24 = is24,
            batteryLevel = battery.level,
            color = Color.White,
            modifier = Modifier.size(width = 560.dp, height = 250.dp),
        )
        LockState.media?.let {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(460.dp)) { MediaCard(it) }
        }
    }
}

@Composable
private fun NotifRow(n: NotifItem, showText: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            n.icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = n.app.uppercase(),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (n.title.isNotBlank()) {
                Text(
                    text = n.title,
                    maxLines = 1,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            }
            if (showText && n.text.isNotBlank()) {
                Text(
                    text = n.text,
                    maxLines = 2,
                    style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
                )
            }
        }
    }
}

@Composable
private fun MediaCard(m: MediaInfo) {
    val tc = m.controller.transportControls
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            m.art?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = m.title,
                maxLines = 1,
                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            if (m.artist.isNotBlank()) {
                Text(
                    text = m.artist,
                    maxLines = 1,
                    style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp),
                )
            }
        }
        ControlIcon(0) { tc.skipToPrevious() }
        ControlIcon(if (m.playing) 2 else 1) { if (m.playing) tc.pause() else tc.play() }
        ControlIcon(3) { tc.skipToNext() }
    }
}

/** kind: 0 = previous, 1 = play, 2 = pause, 3 = next */
@Composable
private fun ControlIcon(kind: Int, onClick: () -> Unit) {
    Canvas(
        Modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    ) {
        val w = size.width
        val h = size.height
        val c = Color.White
        fun tri(x1: Float, x2: Float): Path = Path().apply {
            moveTo(x1 * w, h * 0.28f)
            lineTo(x1 * w, h * 0.72f)
            lineTo(x2 * w, h * 0.5f)
            close()
        }
        when (kind) {
            1 -> drawPath(tri(0.34f, 0.74f), c)
            2 -> {
                drawRect(c, Offset(w * 0.30f, h * 0.25f), Size(w * 0.14f, h * 0.5f))
                drawRect(c, Offset(w * 0.56f, h * 0.25f), Size(w * 0.14f, h * 0.5f))
            }
            3 -> {
                drawPath(tri(0.26f, 0.62f), c)
                drawRect(c, Offset(w * 0.66f, h * 0.28f), Size(w * 0.06f, h * 0.44f))
            }
            else -> {
                drawRect(c, Offset(w * 0.28f, h * 0.28f), Size(w * 0.06f, h * 0.44f))
                drawPath(tri(0.74f, 0.38f), c)
            }
        }
    }
}
