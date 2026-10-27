package com.adamglassner.larksong

import java.awt.FileDialog
import java.awt.Frame
import java.awt.SystemTray
import java.io.File
import java.util.concurrent.TimeUnit

/** Everything that differs between Windows, macOS and Linux lives here. */
object Platform {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")
    val isMac = os.contains("mac")
    val isLinux = os.contains("linux")

    /** "Windows", "macOS" or "Linux", for labels. */
    val name: String get() = if (isWindows) "Windows" else if (isMac) "macOS" else "Linux"

    /** What to call the computer in messages ("PC" on Windows, "Mac" on macOS). */
    val computer: String get() = if (isWindows) "PC" else if (isMac) "Mac" else "computer"

    /** Where the notification-area icon lives, for messages. */
    val trayPlace: String get() = if (isMac) "the menu bar" else "the notification area"

    fun dataDir(): File {
        val home = System.getProperty("user.home")
        val base = when {
            isWindows -> System.getenv("APPDATA")?.let { File(it) }
            isMac -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
        } ?: File(home, ".config")
        val dir = File(base, "Larksong")
        if (!dir.exists()) {
            // Bring over alarms and settings from before the app was renamed (it was called Daybreak).
            val old = File(base, "Daybreak")
            if (old.isDirectory) runCatching { old.copyRecursively(dir) }
        }
        return dir.apply { mkdirs() }
    }

    /** The installed launcher (Larksong.exe, Larksong.app/Contents/MacOS/Larksong or /opt/larksong/bin/Larksong); null in a dev build. */
    private val appExe: String? get() = System.getProperty("jpackage.app-path")

    /** The app icon installed with the Linux package (a PNG in its lib folder), for notifications and autostart. */
    private val linuxIcon: String by lazy {
        appExe?.let { File(it).parentFile?.parentFile?.let { root -> File(root, "lib") } }
            ?.listFiles { f -> f.extension.equals("png", true) }?.firstOrNull()?.absolutePath ?: "alarm-clock"
    }

    /** Whether there's a notification-area icon to fall back on when the window closes. */
    val hasTray: Boolean by lazy { runCatching { SystemTray.isSupported() }.getOrDefault(false) }

    // ---------- start at sign-in ----------

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val autostartFile: File
        get() {
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".config")
            return File(config, "autostart/larksong.desktop")
        }

    /** macOS: a login item (a LaunchAgent in ~/Library/LaunchAgents). */
    private val launchAgent: File
        get() = File(System.getProperty("user.home"), "Library/LaunchAgents/com.adamglassner.larksong.plist")

    val canAutostart: Boolean get() = appExe != null

    private fun run(vararg cmd: String): Int = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() else -1
    }.getOrDefault(-1)

    fun isAutostartOn(): Boolean = when {
        !canAutostart -> false
        isWindows -> run("reg", "query", RUN_KEY, "/v", "Larksong") == 0
        isMac -> launchAgent.isFile
        else -> autostartFile.isFile
    }

    fun setAutostart(on: Boolean) {
        val exe = appExe ?: return
        if (isWindows) {
            if (on) run("reg", "add", RUN_KEY, "/v", "Larksong", "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f")
            else run("reg", "delete", RUN_KEY, "/v", "Larksong", "/f")
        } else if (isMac) {
            val f = launchAgent
            if (on) {
                runCatching {
                    f.parentFile.mkdirs()
                    f.writeText(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                            <key>Label</key><string>com.adamglassner.larksong</string>
                            <key>ProgramArguments</key>
                            <array><string>${xml(exe)}</string><string>--tray</string></array>
                            <key>RunAtLoad</key><true/>
                        </dict>
                        </plist>
                        """.trimIndent() + "\n",
                    )
                }
            } else {
                f.delete()
            }
        } else {
            val f = autostartFile
            if (on) {
                runCatching {
                    f.parentFile.mkdirs()
                    f.writeText(
                        """
                        [Desktop Entry]
                        Type=Application
                        Name=Larksong
                        Comment=Alarm clock
                        Exec="$exe" --tray
                        Icon=$linuxIcon
                        X-GNOME-Autostart-enabled=true
                        """.trimIndent() + "\n",
                    )
                }
            } else {
                f.delete()
            }
        }
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------- notifications (macOS, and Linux without a tray icon) ----------

    /** On macOS, notifications go through Notification Center; the tray's own balloons don't show there. */
    val prefersSystemNotifications: Boolean get() = isMac

    fun notifyDesktop(title: String, text: String): Boolean = when {
        isMac -> {
            fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            run("osascript", "-e", "display notification ${q(text)} with title ${q(title)}") == 0
        }
        isLinux -> run("notify-send", "--app-name=Larksong", "--icon=$linuxIcon", title, text) == 0
        else -> false
    }

    // ---------- sounds ----------

    /** Native "Open" dialog for an audio file. Returns the chosen path or null. */
    fun pickAudioFile(): File? {
        val dialog = FileDialog(null as Frame?, "Choose an alarm sound", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".mp3") || it.endsWith(".wav") || it.endsWith(".aif") || it.endsWith(".aiff") || it.endsWith(".au") } }
        if (isWindows) dialog.file = "*.mp3;*.wav;*.aif;*.aiff;*.au"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        return File(dir, name)
    }

    /** Native Save/Open dialog for a backup file. */
    fun pickBackupFile(save: Boolean, suggestedName: String): File? {
        val dialog = FileDialog(null as Frame?, if (save) "Back up alarms" else "Restore alarms", if (save) FileDialog.SAVE else FileDialog.LOAD)
        if (save) dialog.file = suggestedName
        else {
            dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".json") }
            if (isWindows) dialog.file = "*.json"
        }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        val f = File(dir, name)
        return if (save && !name.lowercase().endsWith(".json")) File(dir, "$name.json") else f
    }

    val systemSoundsLabel: String get() = if (isWindows) "Windows sounds" else if (isMac) "macOS sounds" else "System sounds"

    /** The system's own WAV sounds: C:\Windows\Media, or /usr/share/sounds on Linux. */
    fun systemSounds(): List<File> {
        if (isMac) {
            return File("/System/Library/Sounds").listFiles { f -> f.isFile && f.extension.lowercase() in setOf("aiff", "aif", "wav") }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
        if (isWindows) {
            val media = File(System.getenv("WINDIR") ?: "C:\\Windows", "Media")
            return media.listFiles { f -> f.isFile && f.extension.equals("wav", true) }
                ?.sortedWith(compareBy({ !it.name.startsWith("Alarm", true) }, { it.name.lowercase() }))
                ?: emptyList()
        }
        val root = File("/usr/share/sounds")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(4)
            .filter { it.isFile && it.extension.equals("wav", true) }
            .take(200)
            .sortedWith(compareBy({ !it.name.contains("alarm", true) }, { it.name.lowercase() }))
            .toList()
    }

    // ---------- updates ----------

    /** Which package this machine uses: ".exe" on Windows, ".deb" on Debian/Ubuntu, ".rpm" otherwise. */
    val updateAsset: String by lazy {
        when {
            isWindows -> ".exe"
            isMac -> ".dmg"
            File("/etc/debian_version").exists() || File("/usr/bin/dpkg").exists() -> ".deb"
            else -> ".rpm"
        }
    }
    val updateTagPrefix: String get() = if (isWindows) "windows-v" else if (isMac) "macos-v" else "linux-v"

    /** Starts installing a downloaded update. Returns true if Larksong should quit now. */
    fun openInstaller(file: File): Boolean {
        if (isWindows) {
            ProcessBuilder(file.absolutePath).start()
            return true
        }
        if (isMac) {
            // Opens the disk image in Finder; drag Larksong into Applications to replace this one.
            ProcessBuilder("open", file.absolutePath).start()
            return true
        }
        // Hand the package to the desktop's software installer (GNOME Software, Discover, …).
        ProcessBuilder("xdg-open", file.absolutePath).start()
        return false
    }
}
