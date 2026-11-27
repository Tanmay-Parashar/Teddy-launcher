package app.monolith.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
fun LockScreen(
    userLandscape: Boolean,
    onUserLandscape: (Boolean) -> Unit,
    onOrientation: (Boolean) -> Unit,
    onUnlock: () -> Unit,
    onOpen: (PendingIntent?) -> Unit,
) {
    val ctx = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val battery = rememberBattery()
    val now = rememberNow()
    val is24 = true
    val prefs = remember { ctx.getSharedPreferences("teddy", Context.MODE_PRIVATE) }
    val dim = DimLevels[prefs.getInt("dim", 2) % DimLevels.size].coerceAtLeast(0.35f)
    val showText = prefs.getBoolean("show_text", false)
    var drag by remember { mutableFloatStateOf(0f) }
    val keepOnPref = prefs.getBoolean("lock_keepon", true)
    val wantLandscape = (keepOnPref && battery.charging) || userLandscape
    LaunchedEffect(wantLandscape) { onOrientation(wantLandscape) }
    val thresholdPx = with(LocalDensity.current) { 140.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
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
            LockClock(
                battery,
                stacked = !landscape,
                onRotate = { onUserLandscape(true) },
                onBack = { onUserLandscape(false) },
            )
        }
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

/** (hours colour, minutes colour). Same colour = single-tone clock. */
internal val LockColors = listOf(
    Color(0xFF6AB7FF) to Color(0xFF6AB7FF), // sky blue (default)
    Color(0xFF6FCF63) to Color(0xFF6FCF63), // lime green
    Color(0xFFE56FD0) to Color(0xFFA982F2), // pink + purple
    Color(0xFFFFB340) to Color(0xFFFFB340), // amber
    Color(0xFF5EE6D0) to Color(0xFF5EE6D0), // mint
    Color(0xFFFF4D3D) to Color(0xFFFF4D3D), // night red
)

private val CondensedBold = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.barlow_condensed_bold, FontWeight.Bold)
)

@Composable
private fun LockClock(battery: Battery, stacked: Boolean, onRotate: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("teddy", Context.MODE_PRIVATE) }
    val idx = prefs.getInt("lock_color", 0).coerceIn(0, LockColors.size - 1)
    val (hc, mc) = LockColors[idx]
    val keepOn = prefs.getBoolean("lock_keepon", true)

    var showSec by remember { mutableStateOf(false) }
    var tapTick by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // Timers don't run while the phone sleeps, so refresh the moment the screen comes back.
    var wake by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) wake++
        }
        owner.lifecycle.addObserver(obs)
        val rcv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) { wake++ }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            ctx,
            rcv,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_TIME_TICK)
                addAction(android.content.Intent.ACTION_TIME_CHANGED)
                addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            owner.lifecycle.removeObserver(obs)
            runCatching { ctx.unregisterReceiver(rcv) }
        }
    }
    LaunchedEffect(showSec, wake) {
        while (true) {
            now = LocalDateTime.now()
            val ms = System.currentTimeMillis()
            delay(if (showSec) 1_000L - (ms % 1_000L) + 5L else 60_000L - (ms % 60_000L) + 50L)
        }
    }
    LaunchedEffect(showSec, tapTick) {
        if (showSec) {
            delay(5_000L)
            showSec = false
        }
    }

    val awake = keepOn && battery.charging
    DisposableEffect(awake) {
        val w = ctx.findActivity()?.window
        if (awake && w != null) {
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val level = prefs.getInt("lock_bright", 0)
            val b = when (level) { 1 -> 0.08f; 2 -> 0.3f; else -> -1f }
            if (b > 0f) {
                val lp = w.attributes
                lp.screenBrightness = b
                w.attributes = lp
            }
        }
        onDispose {
            if (w != null) {
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = w.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    val size by androidx.compose.animation.core.animateFloatAsState(
        targetValue = run {
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val w = cfg.screenWidthDp.toFloat()
            val h = cfg.screenHeightDp.toFloat()
            if (stacked) {
                if (showSec) minOf(h * 0.22f, w * 0.45f) else minOf(h * 0.30f, w * 0.62f)
            } else {
                if (showSec) minOf(h * 0.50f, w * 0.225f) else minOf(h * 0.80f, w * 0.36f)
            }
        },
        animationSpec = androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "size",
    )
    val digit = TextStyle(
        fontFamily = CondensedBold,
        fontWeight = FontWeight.Bold,
        fontSize = size.sp,
        lineHeight = size.sp,
        letterSpacing = (-2).sp,
        fontFeatureSettings = "tnum",
    )
    val colonColor = if (hc == mc) hc else Color(0xFF9A9A9A)

    val tap = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) {
        // Portrait: tapping the time rotates the lock screen to landscape. Landscape: tap shows seconds.
        if (stacked) onRotate() else {
            showSec = true
            tapTick++
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (stacked) {
            Column(
                Modifier.align(Alignment.Center).then(tap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(now.format(DateTimeFormatter.ofPattern("HH")), style = digit.copy(color = hc))
                Text(now.format(DateTimeFormatter.ofPattern("mm")), style = digit.copy(color = mc))
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSec,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Text(now.format(DateTimeFormatter.ofPattern("ss")), style = digit.copy(color = mc.copy(alpha = 0.7f)))
                }
            }
        } else {
            Row(
                Modifier.align(Alignment.Center).then(tap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(now.format(DateTimeFormatter.ofPattern("HH")), style = digit.copy(color = hc))
                Text(":", style = digit.copy(color = colonColor))
                Text(now.format(DateTimeFormatter.ofPattern("mm")), style = digit.copy(color = mc))
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSec,
                    enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
                ) {
                    Row {
                        Text(":", style = digit.copy(color = colonColor))
                        Text(now.format(DateTimeFormatter.ofPattern("ss")), style = digit.copy(color = mc))
                    }
                }
            }
        }
        Text(
            text = now.format(DateTimeFormatter.ofPattern("EEE d MMM")).uppercase(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            style = TextStyle(
                color = Color(0xFFCFCFCF),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
            ),
        )
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
