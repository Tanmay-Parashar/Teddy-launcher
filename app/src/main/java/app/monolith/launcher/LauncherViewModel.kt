package app.monolith.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: ImageBitmap,
    val mono: ImageBitmap? = null,
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val pm: PackageManager = application.packageManager
    private val prefs = application.getSharedPreferences("teddy", Context.MODE_PRIVATE)

    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set

    var dockPkgs by mutableStateOf(
        prefs.getString("dock", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    )
        private set

    /** 0 = white, 1 = black, 2 = red */
    var clockColor by mutableIntStateOf(prefs.getInt("clock_color", 0))
        private set

    /** index into the dim levels used by the UI (0 = none) */
    var dimLevel by mutableIntStateOf(prefs.getInt("dim", 2))
        private set

    fun cycleDim() {
        dimLevel = (dimLevel + 1) % 4
        prefs.edit().putInt("dim", dimLevel).apply()
    }

    var homeSignal by mutableIntStateOf(0)
        private set

    fun onHomePressed() {
        homeSignal++
    }

    fun cycleClockColor() {
        clockColor = (clockColor + 1) % 3
        prefs.edit().putInt("clock_color", clockColor).apply()
    }

    fun pin(pkg: String) {
        dockPkgs = (dockPkgs.filter { it != pkg } + pkg).takeLast(6)
        saveDock()
    }

    fun unpin(pkg: String) {
        dockPkgs = dockPkgs.filter { it != pkg }
        saveDock()
    }

    private fun saveDock() {
        prefs.edit().putString("dock", dockPkgs.joinToString(",")).apply()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) { loadApps() }
            apps = list
            if (prefs.getString("dock", null) == null && dockPkgs.isEmpty()) {
                val defaults = defaultDock().filter { p -> list.any { it.pkg == p } }
                if (defaults.isNotEmpty()) dockPkgs = defaults
            }
        }
    }

    private fun loadApps(): List<AppItem> {
        val ownPkg = getApplication<Application>().packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ownPkg }
            .map { ri ->
                val d = ri.loadIcon(pm)
                AppItem(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ri.activityInfo.packageName,
                    cls = ri.activityInfo.name,
                    icon = d.toBitmap(96, 96).asImageBitmap(),
                    mono = monoIcon(d),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun monoIcon(d: Drawable): ImageBitmap? {
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) {
            val m = d.monochrome ?: return null
            return m.toBitmap(128, 128).asImageBitmap()
        }
        return null
    }

    private fun resolvePkg(intent: Intent): String? {
        val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val p = ri.activityInfo.packageName
        return if (p == "android") null else p
    }

    private fun defaultDock(): List<String> = listOfNotNull(
        resolvePkg(Intent(Intent.ACTION_DIAL)),
        resolvePkg(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)),
        resolvePkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        resolvePkg(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
    ).distinct()
}

fun launchApp(ctx: Context, app: AppItem) {
    val i = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app.pkg, app.cls)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    runCatching { ctx.startActivity(i) }
}
