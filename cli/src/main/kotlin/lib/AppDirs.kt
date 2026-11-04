package lib

import java.io.File

/**
 * Per-user directories for the CLI's own state (never athlete files: those live
 * in the coach's workspace).
 *
 * - macOS:   ~/Library/Application Support/coaching-kit
 * - Windows: %APPDATA%\coaching-kit
 * - Linux:   $XDG_CONFIG_HOME/coaching-kit (default ~/.config/coaching-kit)
 *
 * `COACHING_KIT_HOME` overrides the location (used by tests).
 */
object AppDirs {

    enum class Os { MAC, WINDOWS, LINUX }

    val os: Os by lazy {
        val name = System.getProperty("os.name").lowercase()
        when {
            name.contains("mac") || name.contains("darwin") -> Os.MAC
            name.contains("win") -> Os.WINDOWS
            else -> Os.LINUX
        }
    }

    val home: File by lazy {
        System.getenv("COACHING_KIT_HOME")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
        val userHome = System.getProperty("user.home")
        when (os) {
            Os.MAC -> File(userHome, "Library/Application Support/coaching-kit")
            Os.WINDOWS -> File(System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming", "coaching-kit")
            Os.LINUX -> File(
                System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$userHome/.config",
                "coaching-kit"
            )
        }
    }

    /** Creates [dir] if needed, readable only by the current user where the OS allows it. */
    fun ensurePrivateDir(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        dir.setReadable(false, false); dir.setReadable(true, true)
        dir.setWritable(false, false); dir.setWritable(true, true)
        dir.setExecutable(false, false); dir.setExecutable(true, true)
        return dir
    }

    /** Writes [text] to [file] so that only the current user can read it. */
    fun writePrivateFile(file: File, text: String) {
        ensurePrivateDir(file.parentFile)
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    val draftsDir: File get() = ensurePrivateDir(File(home, "drafts"))
}
