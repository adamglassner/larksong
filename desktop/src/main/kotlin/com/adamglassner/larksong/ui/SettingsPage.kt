package com.adamglassner.larksong.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglassner.larksong.Platform
import com.adamglassner.larksong.data.ClockStyle
import com.adamglassner.larksong.data.Settings
import com.adamglassner.larksong.data.Store
import com.adamglassner.larksong.data.ThemeMode
import com.adamglassner.larksong.update.Updater
import kotlinx.coroutines.launch
import java.io.File

/** The running app's version (from the build). */
val APP_VERSION: String by lazy {
    runCatching {
        Thread.currentThread().contextClassLoader.getResourceAsStream("larksong-version.txt")?.bufferedReader()?.use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "dev"
}

/** "Ctrl+" on Windows and Linux, "⌘" on a Mac. */
val shortcutKey: String get() = if (Platform.isMac) "⌘" else "Ctrl+"

suspend fun checkForUpdate() = Updater.check(APP_VERSION, Platform.updateTagPrefix, Platform.updateAsset)

/** Runs a downloaded installer and closes Larksong so it can be replaced. */
fun installUpdate(file: File) {
    runCatching { Platform.openInstaller(file) }
        .onSuccess { quit -> if (quit) DesktopApp.exit() else Notices.show("Finish the install, then restart Larksong") }
        .onFailure { Notices.show("Couldn't start the installer. The package is in ${file.parent}") }
}

object DesktopApp {
    var exit: () -> Unit = {}
}

@Composable
fun SettingsPage(settings: Settings) {
    val p = LocalDb.current.p
    val scope = rememberCoroutineScope()
    val update by Updater.status.collectAsState()
    var addingCity by remember { mutableStateOf(false) }
    var restoreText by remember { mutableStateOf<String?>(null) }
    var aboutDialog by remember { mutableStateOf<String?>(null) }
    var autostart by remember { mutableStateOf(Platform.isAutostartOn()) }

    ScrollPage(maxWidth = 1000.dp) {
        PageHeader("Settings")

        SectionTitle("Appearance")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsCard("App style", "Now using ${styleName(settings.style)} · colors, fonts and the clock face", DbIcons.Clock, below = {
                StyleGroups(settings.style, cardWidth = 150.dp) { s -> Store.updateSettings { it.copy(style = s) } }
            })
            SettingsCard("App theme", "Light, dark, or follow your system setting", DbIcons.Sun) {
                val modes = listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.SYSTEM to "Use system setting")
                ComboBox(modes.first { it.first == settings.mode }.second, modes.map { it.second }) { i ->
                    Store.updateSettings { it.copy(mode = modes[i].first) }
                }
            }
        }

        SectionTitle("Time")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsCard("24-hour clock", if (settings.use24h) "Shows 18:30" else "Shows 6:30 PM", DbIcons.Clock) {
                FToggle(settings.use24h, { v -> Store.updateSettings { it.copy(use24h = v) } }, "24-hour clock")
            }
            SettingsCard("Show seconds", null, DbIcons.Stopwatch) {
                FToggle(settings.showSeconds, { v -> Store.updateSettings { it.copy(showSeconds = v) } }, "Show seconds")
            }
        }

        SectionTitle("Weather")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsCard("Temperature units", null, DbIcons.WxPartly) {
                ComboBox(if (settings.celsius) "Celsius (°C)" else "Fahrenheit (°F)", listOf("Fahrenheit (°F)", "Celsius (°C)")) { i ->
                    Store.updateSettings { it.copy(celsius = i == 1) }
                }
            }
            SettingsCard("Use my approximate location", "Based on your internet connection", DbIcons.Pin) {
                FToggle(settings.useGps, { v -> Store.updateSettings { it.copy(useGps = v) } }, "Use my approximate location")
            }
            SettingsCard("Saved cities", if (settings.cities.isEmpty()) "None yet" else "${settings.cities.size} saved", DbIcons.Pin, below = {
                settings.cities.forEach { city ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DText(city.name, 14.sp, p.text)
                            DText(city.region, 12.sp, p.muted)
                        }
                        FIconButton(DbIcons.Close, "Remove ${city.name}") { Store.updateSettings { it.copy(cities = it.cities - city) } }
                    }
                }
                Spacer(Modifier.height(4.dp))
                FButton("Add a city", icon = DbIcons.Plus) { addingCity = true }
            })
        }

        SectionTitle(Platform.name)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsCard(
                if (Platform.isWindows) "Start with Windows" else "Start when I sign in",
                if (Platform.canAutostart) "Opens quietly in the background when you sign in, so alarms are always ready"
                else "Available in the installed app",
                DbIcons.Bell,
            ) {
                FToggle(autostart, { v -> Platform.setAutostart(v); autostart = Platform.isAutostartOn() }, "Start when I sign in", enabled = Platform.canAutostart)
            }
            SettingsCard(
                "Closing the window",
                when {
                    Platform.isMac && Platform.hasTray -> "Larksong keeps running in the menu bar so alarms still ring. To quit, click its menu bar icon and choose Quit. A sleeping Mac can't ring an alarm."
                    Platform.hasTray -> "Larksong keeps running in ${Platform.trayPlace} so alarms still ring. To exit, right-click its icon and choose Quit. A sleeping ${Platform.computer} can't ring an alarm."
                    else -> "Closing minimizes Larksong so alarms still ring. A sleeping ${Platform.computer} can't ring an alarm."
                },
                DbIcons.Close,
            )
        }

        SectionTitle("Backup")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsCard("Back up alarms", "Saves alarms, bedtime and world clocks to a .json file", DbIcons.Download) {
                FButton("Back up...") {
                    val f = Platform.pickBackupFile(true, "larksong-alarms-${java.time.LocalDate.now()}.json")
                    if (f != null) {
                        val ok = runCatching { f.writeText(Store.exportBackup("Larksong $APP_VERSION for ${Platform.name}")) }.isSuccess
                        Notices.show(if (ok) "Alarms backed up to ${f.name}" else "The backup couldn't be saved there")
                    }
                }
            }
            SettingsCard("Restore alarms", "From a Larksong or Larksong Classic backup (Larksong Classic runs on Windows 95 to 11)", DbIcons.Alarm) {
                FButton("Restore...") {
                    val f = Platform.pickBackupFile(false, "")
                    if (f != null) {
                        val text = runCatching { f.readText() }.getOrNull()
                        if (text == null || Store.backupAlarmCount(text) < 0) Notices.show("That doesn't look like a Larksong backup")
                        else restoreText = text
                    }
                }
            }
        }

        SectionTitle("Updates")
        val dl = update as? Updater.Status.Downloading
        val progressRow: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? =
            if (dl != null) { { ProgressBar(dl.progress) } } else null
        SettingsCard(
            "Larksong $APP_VERSION",
            when (val s = update) {
                Updater.Status.Idle -> "Check GitHub for a newer version"
                Updater.Status.Checking -> "Checking for updates…"
                Updater.Status.UpToDate -> "You're up to date"
                is Updater.Status.Available -> "Version ${s.release.version} is available"
                is Updater.Status.Downloading -> "Downloading version ${s.release.version}… ${(s.progress * 100).toInt()}%"
                is Updater.Status.Ready -> "Version ${s.release.version} is ready to install."
                is Updater.Status.Failed -> s.message
            },
            DbIcons.Download,
            below = progressRow,
        ) {
            when (val s = update) {
                is Updater.Status.Available -> FButton("Download and install", accent = true) {
                    scope.launch {
                        val dir = File(System.getProperty("java.io.tmpdir"), "Larksong-update")
                        Updater.download(s.release, dir)?.let { installUpdate(it) }
                    }
                }
                is Updater.Status.Ready -> FButton("Install now", accent = true) { installUpdate(s.file) }
                Updater.Status.Checking, is Updater.Status.Downloading -> {}
                else -> FButton("Check for updates") { scope.launch { checkForUpdate() } }
            }
        }

        SectionTitle("About")
        SettingsCard(
            "Larksong for ${Platform.name}",
            "Version $APP_VERSION · Weather by Open-Meteo · Radio by radio-browser.info · Keyboard: ${shortcutKey}1–5 switch pages, ${shortcutKey}N new alarm, $shortcutKey, settings, Esc closes dialogs",
            DbIcons.Alarm,
        )
        Spacer(Modifier.height(4.dp))
        SettingsCard("Credits", "Created by Adam Glassner · The Glassnerds", DbIcons.Music, onClick = { aboutDialog = "credits" }) {
            Icon(DbIcons.Chevron, null, tint = p.muted, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(4.dp))
        SettingsCard("What's new", "Changes in each version of Larksong", DbIcons.Bell, onClick = { aboutDialog = "changes" }) {
            Icon(DbIcons.Chevron, null, tint = p.muted, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(1.dp))
    }

    when (aboutDialog) {
        "credits" -> ContentDialog(
            title = "Credits",
            onDismiss = { aboutDialog = null },
            primary = "What's new",
            onPrimary = { aboutDialog = "changes" },
            secondary = "Close",
            width = 600.dp,
        ) { CreditsContent() }
        "changes" -> ContentDialog(
            title = "What's new in Larksong",
            onDismiss = { aboutDialog = null },
            primary = "Credits",
            onPrimary = { aboutDialog = "credits" },
            secondary = "Close",
            width = 600.dp,
        ) { ChangelogContent() }
    }

    restoreText?.let { text ->
        val count = Store.backupAlarmCount(text)
        ContentDialog(
            title = "Restore $count alarm(s)?",
            onDismiss = { restoreText = null },
            primary = "Replace mine",
            onPrimary = {
                restoreText = null
                com.adamglassner.larksong.alarm.Scheduler.let { s -> Store.alarms.value.forEach { s.cancel(it.id) } }
                val n = runCatching { Store.importBackup(text, true) }.getOrDefault(-1)
                Notices.show(if (n >= 0) "Restored $n alarm(s)" else "That backup couldn't be read")
            },
            footerStart = {
                FButton("Add to mine") {
                    restoreText = null
                    val n = runCatching { Store.importBackup(text, false) }.getOrDefault(-1)
                    Notices.show(if (n >= 0) "Added $n alarm(s)" else "That backup couldn't be read")
                }
            },
        ) {
            DText(
                "Replace your alarms with the ones in this backup (this also restores bedtime and world clocks), or add them to the alarms you have.",
                14.sp, p.text,
            )
        }
    }

    if (addingCity) {
        CitySearchDialog(onDismiss = { addingCity = false }) { city ->
            addingCity = false
            Store.updateSettings { s -> if (s.cities.any { it.name == city.name && it.region == city.region }) s else s.copy(cities = s.cities + city) }
        }
    }
}
