package pl.zamirski.jarvis.ui

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.zamirski.jarvis.data.ReminderScheduler
import pl.zamirski.jarvis.ui.components.CardShape
import pl.zamirski.jarvis.ui.screens.CalendarScreen
import pl.zamirski.jarvis.ui.screens.ChatScreen
import pl.zamirski.jarvis.ui.screens.ComputerScreen
import pl.zamirski.jarvis.ui.screens.FeaturesScreen
import pl.zamirski.jarvis.ui.screens.HomeScreen
import pl.zamirski.jarvis.ui.screens.NotesScreen
import pl.zamirski.jarvis.ui.screens.RemindersScreen
import pl.zamirski.jarvis.ui.screens.SettingsScreen
import pl.zamirski.jarvis.ui.screens.SmartHomeScreen
import pl.zamirski.jarvis.ui.screens.TasksScreen
import pl.zamirski.jarvis.ui.screens.WeatherScreen
import pl.zamirski.jarvis.ui.theme.JColors
import pl.zamirski.jarvis.ui.theme.JarvisTheme
import java.io.File

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val FEATURES = "features"
    const val WEATHER = "weather"
    const val CALENDAR = "calendar"
    const val REMINDERS = "reminders"
    const val NOTES = "notes"
    const val TASKS = "tasks"
    const val SMART_HOME = "smarthome"
    const val COMPUTER = "computer"
    const val SETTINGS = "settings"
}

/** Akcje wymagające systemu (uprawnienia, aparat, pliki, przeglądarka). */
class AppActions(
    val startVoice: () -> Unit,
    val takePhoto: () -> Unit,
    val pickFile: () -> Unit,
    val webSearch: (String) -> Unit,
    val requestPermission: (String, (Boolean) -> Unit) -> Unit,
    val openAppSettings: () -> Unit,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderScheduler.ensureChannel(this)
        setContent {
            JarvisTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot() {
    val vm: JarvisViewModel = viewModel()
    val nav = rememberNavController()
    val actions = rememberAppActions(vm, nav)

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(vm, actions) { nav.navigate(it) } }
            composable(Routes.CHAT) { ChatScreen(vm, actions) { nav.popBackStack() } }
            composable(Routes.FEATURES) { FeaturesScreen(onBack = { nav.popBackStack() }) { nav.navigate(it) } }
            composable(Routes.WEATHER) { WeatherScreen(vm, actions) { nav.popBackStack() } }
            composable(Routes.CALENDAR) { CalendarScreen(vm, actions) { nav.popBackStack() } }
            composable(Routes.REMINDERS) { RemindersScreen(vm, actions) { nav.popBackStack() } }
            composable(Routes.NOTES) { NotesScreen(vm, actions) { nav.popBackStack() } }
            composable(Routes.TASKS) { TasksScreen(vm) { nav.popBackStack() } }
            composable(Routes.SMART_HOME) { SmartHomeScreen { nav.popBackStack() } }
            composable(Routes.COMPUTER) { ComputerScreen(vm) { nav.popBackStack() } }
            composable(Routes.SETTINGS) { SettingsScreen(vm, actions) { nav.popBackStack() } }
        }

        val notice = vm.notice
        if (notice != null) {
            LaunchedEffect(notice) {
                delay(6000)
                if (vm.notice == notice) vm.notice = null
            }
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(12.dp)
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(JColors.CardSolid)
                    .border(1.dp, JColors.Warning.copy(alpha = 0.6f), CardShape)
                    .clickable { vm.notice = null }
                    .padding(14.dp)
                    .align(Alignment.TopCenter),
            ) {
                Text(notice, color = JColors.Text, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun rememberAppActions(vm: JarvisViewModel, nav: NavHostController): AppActions {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()

    var micBlockedDialog by remember { mutableStateOf(false) }
    var pendingPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    var permissionCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val openSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> vm.startListening()
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> micBlockedDialog = true
            else -> vm.notice = "Bez zgody na mikrofon możesz pisać polecenia tekstem."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionCallback?.invoke(granted)
        permissionCallback = null
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        pendingPhoto = null
        if (ok && uri != null) {
            scope.launch {
                val b64 = withContext(Dispatchers.IO) { Attachments.imageToJpegBase64(context, uri) }
                Attachments.clearCameraCache(context)
                if (b64 == null) {
                    vm.notice = "Nie udało się odczytać zdjęcia."
                } else {
                    vm.send("Co widzisz na tym zdjęciu? Opisz to krótko po polsku.", b64, "Zdjęcie z kamery")
                    nav.navigate(Routes.CHAT) { launchSingleTop = true }
                }
            }
        } else {
            Attachments.clearCameraCache(context)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = withContext(Dispatchers.IO) { Attachments.readPickedFile(context, uri) }
                when (attachment) {
                    is PickedFile.Image -> vm.send("Co jest na tym obrazie? Opisz krótko po polsku.", attachment.base64, "Obraz: ${attachment.name}")
                    is PickedFile.Text -> vm.send(
                        "Przeanalizuj ten plik i streść go po polsku.\n\nPlik: ${attachment.name}\n---\n${attachment.content}",
                        null,
                        "Plik: ${attachment.name}" + if (attachment.truncated) " (skrócony)" else "",
                    )
                    is PickedFile.Unsupported -> vm.notice = attachment.reason
                }
                if (attachment !is PickedFile.Unsupported) nav.navigate(Routes.CHAT) { launchSingleTop = true }
            }
        }
    }

    if (micBlockedDialog) {
        AlertDialog(
            onDismissRequest = { micBlockedDialog = false },
            title = { Text("Mikrofon zablokowany") },
            text = {
                Text(
                    "Zgoda na mikrofon została odrzucona. Otwórz ustawienia aplikacji → Uprawnienia → Mikrofon " +
                        "i wybierz „Zezwalaj tylko podczas używania aplikacji”. Do tego czasu możesz pisać polecenia tekstem.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    micBlockedDialog = false
                    openSettings()
                }) { Text("Otwórz ustawienia") }
            },
            dismissButton = { TextButton(onClick = { micBlockedDialog = false }) { Text("Anuluj") } },
        )
    }

    return remember(vm) {
        AppActions(
            startVoice = {
                vm.onMicTapped {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) vm.startListening() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            takePhoto = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                pendingPhoto = uri
                try {
                    cameraLauncher.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    pendingPhoto = null
                    vm.notice = "Nie znaleziono aplikacji aparatu."
                }
            },
            pickFile = {
                try {
                    fileLauncher.launch(arrayOf("text/*", "image/*", "application/json"))
                } catch (e: ActivityNotFoundException) {
                    vm.notice = "Nie znaleziono aplikacji do wyboru plików."
                }
            },
            webSearch = { query ->
                val q = query.trim()
                if (q.isNotEmpty()) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
                    } catch (e: ActivityNotFoundException) {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))),
                            )
                        } catch (e2: ActivityNotFoundException) {
                            vm.notice = "Brak przeglądarki internetowej."
                        }
                    }
                }
            },
            requestPermission = { permission, callback ->
                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                    callback(true)
                } else {
                    permissionCallback = callback
                    permissionLauncher.launch(permission)
                }
            },
            openAppSettings = { openSettings() },
        )
    }
}
