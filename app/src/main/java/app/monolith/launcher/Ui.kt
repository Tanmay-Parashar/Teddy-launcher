package app.monolith.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MonoFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

// white, black, red
private val ClockColors = listOf(Color.White, Color(0xFF0B0B0B), Color(0xFFFF5A5F))
internal val DimLevels = listOf(0f, 0.30f, 0.50f, 0.70f)

// ---------------------------------------------------------------- helpers

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

data class Battery(val charging: Boolean, val level: Int)

private fun readBattery(i: Intent?): Battery {
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
    return Battery(charging, pct)
}

@Composable
fun rememberBattery(): Battery {
    val ctx = LocalContext.current
    var battery by remember {
        mutableStateOf(
            readBattery(ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        )
    }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                battery = readBattery(i)
            }
        }
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    return battery
}

@Composable
fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50L)
        }
    }
    return now
}

internal fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..20 -> "Good evening"
    else -> "Good night"
}

// ---------------------------------------------------------------- root

@Composable
fun LauncherRoot(vm: LauncherViewModel) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val battery = rememberBattery()
    if (landscape) {
        StandBy(battery)
    } else {
        HomeAndDrawer(vm, battery)
    }
}

@Composable
fun HomeAndDrawer(vm: LauncherViewModel, battery: Battery) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var progress by remember { mutableFloatStateOf(0f) }
    val animJob = remember { mutableStateOf<Job?>(null) }
    var focusSearch by remember { mutableStateOf(false) }
    val dragRangePx = with(density) { (config.screenHeightDp * 0.55f).dp.toPx() }
    val drawerVisible by remember { derivedStateOf { progress > 0.001f } }
    val settle = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    fun go(target: Float) {
        animJob.value?.cancel()
        animJob.value = scope.launch {
            animate(progress, target, animationSpec = settle) { v, _ -> progress = v }
        }
    }

    // Back never leaves the launcher
    BackHandler(enabled = true) { }
    BackHandler(enabled = vm.showSettings) { vm.closeSettings() }
    BackHandler(enabled = drawerVisible && !vm.showSettings) { go(0f) }
    LaunchedEffect(vm.homeSignal) { go(0f) }
    LaunchedEffect(drawerVisible) { if (!drawerVisible) focusSearch = false }

    SideEffect {
        val window = view.context.findActivity()?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                vm.clockColor == 1 && !drawerVisible
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = DimLevels[vm.dimLevel % DimLevels.size]))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d ->
                    progress = (progress - d / dragRangePx).coerceIn(0f, 1f)
                },
                onDragStarted = { animJob.value?.cancel() },
                onDragStopped = { v ->
                    val target = when {
                        v < -700f -> 1f
                        v > 700f -> 0f
                        else -> if (progress > 0.4f) 1f else 0f
                    }
                    go(target)
                },
            )
    ) {
        HomeContent(
            vm = vm,
            battery = battery,
            onSearch = {
                focusSearch = true
                go(1f)
            },
            modifier = Modifier.graphicsLayer {
                alpha = (1f - progress * 1.5f).coerceIn(0f, 1f)
                val s = 1f - 0.05f * progress
                scaleX = s
                scaleY = s
            },
        )
        if (drawerVisible) {
            AppDrawer(vm, { progress }, focusSearch) {
                animJob.value?.cancel()
                progress = 0f
            }
        }
        if (vm.showSettings) {
            SettingsPanel(vm)
        }
    }
}

// ---------------------------------------------------------------- home

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    vm: LauncherViewModel,
    battery: Battery,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val is24 = DateFormat.is24HourFormat(ctx)
    val color = ClockColors[vm.clockColor % ClockColors.size]
    val shadow = Shadow(
        color = if (vm.clockColor == 1) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.45f),
        offset = Offset(0f, 2f),
        blurRadius = 12f,
    )
    val small = TextStyle(
        color = color.copy(alpha = 0.85f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        shadow = shadow,
    )
    val apps = vm.dockPkgs.mapNotNull { p -> vm.apps.firstOrNull { it.pkg == p } }

    Column(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { vm.cycleDim() })
            }
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = 44.dp, top = 28.dp, end = 24.dp, bottom = 20.dp)
    ) {
        Column(
            Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    runCatching { ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
                },
                onLongClick = { vm.cycleClockColor() },
            )
        ) {
            Text(
                text = greeting(now.hour),
                style = small.copy(fontSize = 11.sp, letterSpacing = 1.sp),
            )
            Text(
                text = now.dayOfMonth.toString(),
                style = TextStyle(
                    color = color,
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
        }

        Spacer(Modifier.height(20.dp))
        SearchRing(color = color, onClick = onSearch)

        Spacer(Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            apps.forEach { app ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier
                            .combinedClickable(
                                onClick = { launchApp(ctx, app) },
                                onLongClick = { menu = true },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppGlyph(app, 34.dp, color, vm.iconMono)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = app.label,
                            maxLines = 1,
                            style = TextStyle(
                                color = color,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.3.sp,
                                shadow = shadow,
                            ),
                        )
                    }
                    AppMenu(menu, { menu = false }, app, vm, pinned = true)
                }
            }
        }
    }
}

@Composable
fun AppGlyph(app: AppItem, size: Dp, tint: Color = Color.White, mono: Boolean = false) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!mono) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        } else if (app.mono != null) {
            Image(
                bitmap = app.mono,
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(size),
            )
        } else {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                colorFilter = MonoFilter,
                modifier = Modifier
                    .size(size * 0.72f)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
fun SearchRing(color: Color, onClick: () -> Unit) {
    Canvas(
        Modifier
            .size(124.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f - 12.dp.toPx()
        drawCircle(
            color = color.copy(alpha = 0.22f),
            radius = r - 12.dp.toPx(),
            style = Stroke(width = 1.dp.toPx()),
        )
        val path = android.graphics.Path()
        path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        val paint = Paint().apply {
            isAntiAlias = true
            this.color = color.copy(alpha = 0.9f).toArgb()
            textSize = 9.sp.toPx()
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        drawIntoCanvas { c ->
            c.nativeCanvas.drawTextOnPath("FIND ANYTHING YOU WANT", path, 6.dp.toPx(), 0f, paint)
        }
    }
}

// ---------------------------------------------------------------- drawer

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppDrawer(
    vm: LauncherViewModel,
    progress: () -> Float,
    autoFocus: Boolean,
    onLaunched: () -> Unit,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val filtered = remember(vm.apps, query) {
        val q = query.trim()
        if (q.isEmpty()) vm.apps else vm.apps.filter { it.label.contains(q, ignoreCase = true) }
    }

    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = progress()
                translationY = (1f - progress()) * 120f
            }
            .background(Color(0xFF0A0D12).copy(alpha = 0.88f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 40.dp)
        ) {
            if (query.isBlank()) {
                item(key = "settings") {
                    Text(
                        text = "SETTINGS",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.openSettings()
                                onLaunched()
                            }
                            .padding(vertical = 12.dp),
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
            items(filtered, key = { it.pkg + "/" + it.cls }) { app ->
                AppRow(app, vm, pinned = vm.dockPkgs.contains(app.pkg)) {
                    launchApp(ctx, app)
                    onLaunched()
                }
            }
        }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                filtered.firstOrNull()?.let {
                    launchApp(ctx, it)
                    onLaunched()
                }
            }),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.09f))
                        .padding(horizontal = 22.dp, vertical = 15.dp)
                ) {
                    if (query.isEmpty()) {
                        Text(
                            "FIND ANYTHING YOU WANT",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 12.sp,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .focusRequester(focusRequester),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(app: AppItem, vm: LauncherViewModel, pinned: Boolean, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppGlyph(app, 34.dp, Color.White, vm.iconMono)
            Spacer(Modifier.width(18.dp))
            Text(
                text = app.label,
                maxLines = 1,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.3.sp,
                ),
            )
        }
        AppMenu(menu, { menu = false }, app, vm, pinned)
    }
}

@Composable
fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    app: AppItem,
    vm: LauncherViewModel,
    pinned: Boolean,
) {
    val ctx = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (pinned) {
            DropdownMenuItem(
                text = { Text("Remove from home") },
                onClick = { vm.unpin(app.pkg); onDismiss() },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Pin to home") },
                onClick = { vm.pin(app.pkg); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text("App info") },
            onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    )
                }
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Uninstall") },
            onClick = {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
                onDismiss()
            },
        )
    }
}

// ---------------------------------------------------------------- StandBy (dial clock)

@Composable
fun StandBy(battery: Battery) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val now = rememberNow()
    val night = now.hour >= 22 || now.hour < 6
    val color = if (night) Color(0xFFB3261E) else Color.White
    val is24 = DateFormat.is24HourFormat(ctx)

    DisposableEffect(night, battery.charging) {
        val window = ctx.findActivity()?.window
        view.keepScreenOn = battery.charging
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (window != null && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            val lp = window.attributes
            lp.screenBrightness =
                if (night) 0.04f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    // slow pixel shift to protect the AMOLED
    val shift = ((now.minute % 6) - 3) * 4

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        DialClock(
            now = now,
            is24 = is24,
            batteryLevel = battery.level,
            color = color,
            modifier = Modifier
                .size(width = 560.dp, height = 300.dp)
                .graphicsLayer {
                    translationX = shift.dp.toPx()
                    translationY = -shift.dp.toPx()
                },
        )
    }
}

private fun textPaint(color: Color, sizePx: Float, family: String, align: Paint.Align, spacing: Float = 0f) =
    Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        textSize = sizePx
        typeface = Typeface.create(family, Typeface.NORMAL)
        textAlign = align
        letterSpacing = spacing
    }

@Composable
fun DialClock(
    now: LocalDateTime,
    is24: Boolean,
    batteryLevel: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val hh = now.format(DateTimeFormatter.ofPattern(if (is24) "HH" else "hh"))
    val mm = now.format(DateTimeFormatter.ofPattern("mm"))
    val dateLine = now.format(DateTimeFormatter.ofPattern("d MMM yyyy")).uppercase()
    val dayLine = now.format(DateTimeFormatter.ofPattern("EEEE")).uppercase()
    val minute = now.minute

    Canvas(modifier) {
        val cy = size.height / 2f
        val hourPaint = textPaint(color, 150.sp.toPx(), "sans-serif-light", Paint.Align.LEFT)
        val minPaint = textPaint(color, 34.sp.toPx(), "sans-serif", Paint.Align.CENTER)
        val smallPaint = textPaint(color.copy(alpha = 0.7f), 11.sp.toPx(), "sans-serif-medium", Paint.Align.LEFT, 0.25f)
        val hourW = hourPaint.measureText(hh)
        val pillW = 92.dp.toPx()
        val pillH = 64.dp.toPx()
        val pillX = hourW + 26.dp.toPx()
        val pillCx = pillX + pillW / 2f
        val radius = 170.dp.toPx()
        val arcCx = pillCx + radius

        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            val hm = hourPaint.fontMetrics
            nc.drawText(hh, 0f, cy - (hm.ascent + hm.descent) / 2f, hourPaint)
            val mm2 = minPaint.fontMetrics
            nc.drawText(mm, pillCx, cy - (mm2.ascent + mm2.descent) / 2f, minPaint)
        }
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(pillX, cy - pillH / 2f),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(pillH / 2f),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // rotary minute dial
        val labelPaint = textPaint(color, 12.sp.toPx(), "sans-serif-medium", Paint.Align.CENTER)
        for (d in -8..8) {
            if (d == 0 || d == 1 || d == -1) continue
            val ang = Math.toRadians(d * 9.0).toFloat()
            val px = arcCx - radius * cos(ang)
            val py = cy - radius * sin(ang)
            val m = ((minute + d) % 60 + 60) % 60
            val fade = (1f - kotlin.math.abs(d) / 10f).coerceIn(0.15f, 1f)
            val major = m % 5 == 0
            val len = (if (major) 14.dp else 7.dp).toPx()
            drawLine(
                color = color.copy(alpha = fade * 0.8f),
                start = Offset(px, py),
                end = Offset(px + len * cos(ang), py + len * sin(ang)),
                strokeWidth = 1.dp.toPx(),
            )
            if (major) {
                val lx = px + 30.dp.toPx() * cos(ang)
                val ly = py + 30.dp.toPx() * sin(ang)
                labelPaint.alpha = (fade * 255).toInt()
                drawIntoCanvas { c ->
                    c.nativeCanvas.drawText("%02d".format(m), lx, ly + 4.sp.toPx(), labelPaint)
                }
            }
        }

        // date block
        val dx = arcCx + 20.dp.toPx()
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.drawText(dateLine, dx, cy - 6.dp.toPx(), smallPaint)
            nc.drawText(dayLine, dx, cy + 14.dp.toPx(), smallPaint)
            nc.drawText("$batteryLevel%", dx, cy + 40.dp.toPx(), smallPaint)
        }
    }
}


// ---------------------------------------------------------------- settings / first run

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text,
        style = TextStyle(
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (sel) Color.White else Color.White.copy(alpha = 0.10f))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    label,
                    style = TextStyle(
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
fun SettingsPanel(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    val first = !vm.onboarded
    val isHome = remember(vm.resumeTick) { isDefaultHome(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D12).copy(alpha = 0.82f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        Text(
            if (first) "WELCOME" else "SETTINGS",
            style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (first) "Pick how it looks. You can change all of this later from the app drawer (SETTINGS)."
            else "Changes apply instantly behind this panel.",
            style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp),
        )

        SectionLabel("HOME APP")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isHome) "Teddy's Launcher is your home app." else "Not your home app yet, so Back closes it.",
                modifier = Modifier.weight(1f),
                style = TextStyle(color = Color.White, fontSize = 14.sp),
            )
            if (!isHome) {
                androidx.compose.material3.TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                }) { Text("SET", color = Color.White, letterSpacing = 2.sp, fontSize = 12.sp) }
            }
        }

        SectionLabel("ICON STYLE")
        ChipRow(listOf("ORIGINAL", "MONOCHROME"), if (vm.iconMono) 1 else 0) { vm.setIconMono(it == 1) }

        SectionLabel("TEXT COLOR")
        ChipRow(listOf("WHITE", "BLACK", "RED"), vm.clockColor) { vm.setClockColor(it) }

        SectionLabel("WALLPAPER DIM")
        ChipRow(listOf("OFF", "30", "50", "70"), vm.dimLevel) { vm.setDim(it) }

        SectionLabel("LOCK SCREEN")
        androidx.compose.material3.TextButton(onClick = {
            ctx.startActivity(Intent(ctx, LockSetupActivity::class.java))
        }) { Text("LOCK SCREEN SETUP", color = Color.White, letterSpacing = 2.sp, fontSize = 12.sp) }

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .clickable { vm.closeSettings() }
                .padding(horizontal = 36.dp, vertical = 12.dp)
        ) {
            Text(
                if (first) "START" else "DONE",
                style = TextStyle(color = Color.Black, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}
