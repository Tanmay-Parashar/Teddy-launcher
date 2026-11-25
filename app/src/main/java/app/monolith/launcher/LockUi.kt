package app.monolith.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
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
            .background(Color.Black.copy(alpha = dim))
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
        Text(
            text = "SWIPE UP TO UNLOCK",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 14.dp),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun LockPortrait(
    now: LocalDateTime,
    is24: Boolean,
    battery: Battery,
    showText: Boolean,
    onOpen: (PendingIntent?) -> Unit,
) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 12f)
    val small = TextStyle(
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        shadow = shadow,
    )
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = 44.dp, top = 40.dp, end = 28.dp, bottom = 44.dp)
    ) {
        Text(text = greeting(now.hour), style = small.copy(fontSize = 11.sp, letterSpacing = 1.sp))
        Text(
            text = now.dayOfMonth.toString(),
            style = TextStyle(
                color = Color.White,
                fontSize = 116.sp,
                lineHeight = 120.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-2).sp,
                shadow = shadow,
            ),
        )
        Text(
            text = now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "hh:mm a")),
            style = small,
        )
        Text(text = "Battery ${battery.level}%", style = small)
        Text(text = now.format(DateTimeFormatter.ofPattern("EEE, d MMM")), style = small)

        Spacer(Modifier.height(28.dp))

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
