package pl.zamirski.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.zamirski.jarvis.core.AppEntry
import pl.zamirski.jarvis.core.AppMatcher
import pl.zamirski.jarvis.core.ToolCall
import pl.zamirski.jarvis.core.ToolRegistry
import pl.zamirski.jarvis.core.ToolResult
import pl.zamirski.jarvis.core.ToolValidation

/**
 * Wykonuje na telefonie wyłącznie narzędzia z ToolRegistry, po walidacji parametrów.
 * Potwierdzenia działań wrażliwych obsługuje ViewModel PRZED wywołaniem execute().
 */
class LocalToolExecutor(context: Context) {

    private val app = context.applicationContext

    suspend fun execute(call: ToolCall): ToolResult {
        val args = when (val v = ToolRegistry.validate(call)) {
            is ToolValidation.Ok -> v.args
            is ToolValidation.Error -> return ToolResult(call.id, false, v.message)
        }
        return try {
            when (call.tool) {
                ToolRegistry.OPEN_APP -> openApp(call.id, args["name"] as String)
                else -> ToolResult(call.id, false, "Narzędzie niedostępne na tym telefonie")
            }
        } catch (e: SecurityException) {
            ToolResult(call.id, false, "Android nie pozwolił wykonać tego działania")
        } catch (e: Exception) {
            ToolResult(call.id, false, "Błąd wykonania: ${e.javaClass.simpleName}")
        }
    }

    /** Aplikacje z ikoną w menu (bez samego Jarvisa). Wymaga deklaracji <queries> dla LAUNCHER (Android 11+). */
    suspend fun launchableApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == app.packageName) return@mapNotNull null
                AppEntry(ri.loadLabel(pm).toString(), pkg)
            }
            .distinctBy { it.packageName }
    }

    private suspend fun openApp(id: String, name: String): ToolResult {
        val match = AppMatcher.match(name, launchableApps())
        if (match !is AppMatcher.Match.Found) return ToolResult(id, false, AppMatcher.resultText(name, match))
        val launch = app.packageManager.getLaunchIntentForPackage(match.app.packageName)
            ?: return ToolResult(id, false, "Aplikacji ${match.app.label} nie da się uruchomić z zewnątrz")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            withContext(Dispatchers.Main) { app.startActivity(launch) }
            ToolResult(id, true, AppMatcher.resultText(name, match))
        } catch (e: ActivityNotFoundException) {
            ToolResult(id, false, "Nie udało się uruchomić aplikacji ${match.app.label}")
        }
    }
}
