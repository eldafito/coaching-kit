package lib

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stores the intervals.icu API key outside the coach's workspace.
 *
 * - macOS: the login Keychain, through `/usr/bin/security`. The key is passed
 *   on stdin (`security -i`), never as a command-line argument.
 * - Other systems: a file in [AppDirs.home] readable only by the user.
 */
object SecretStore {

    private const val SERVICE = "coaching-kit"
    private const val ACCOUNT = "intervals-api-key"
    private const val SECURITY = "/usr/bin/security"

    /** Keys are pasted by hand: accept only the characters intervals.icu uses. */
    private val KEY_FORMAT = Regex("^[A-Za-z0-9_-]{10,200}$")

    fun isValidFormat(key: String): Boolean = KEY_FORMAT.matches(key)

    private val useKeychain: Boolean
        get() = AppDirs.os == AppDirs.Os.MAC &&
            System.getenv("COACHING_KIT_HOME").isNullOrBlank() &&
            File(SECURITY).canExecute()

    private val file: File get() = File(AppDirs.home, "credentials.env")

    /** Human-readable name of where the key is stored. */
    val locationName: String
        get() = if (useKeychain) "macOS Keychain (item \"$SERVICE\")" else file.absolutePath

    fun read(): String? = if (useKeychain) readKeychain() else readFile()

    fun save(key: String) {
        require(isValidFormat(key)) { "This does not look like an intervals.icu API key." }
        if (useKeychain) saveKeychain(key) else AppDirs.writePrivateFile(file, "INTERVALS_API_KEY=$key\n")
    }

    fun delete() {
        if (useKeychain) {
            run(listOf(SECURITY, "delete-generic-password", "-s", SERVICE, "-a", ACCOUNT))
        } else {
            file.delete()
        }
    }

    private fun readFile(): String? {
        if (!file.exists()) return null
        return file.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("INTERVALS_API_KEY=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun readKeychain(): String? {
        val (code, out) = run(listOf(SECURITY, "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w"))
        return if (code == 0) out.trim().takeIf { it.isNotBlank() } else null
    }

    private fun saveKeychain(key: String) {
        // The key format is validated above, so it cannot break out of the quotes.
        val command = "add-generic-password -U -s \"$SERVICE\" -a \"$ACCOUNT\" -l \"Coaching Kit (intervals.icu)\" -w \"$key\"\n"
        val (code, out) = run(listOf(SECURITY, "-i"), stdin = command)
        check(code == 0) { "Could not save the key in the macOS Keychain: ${out.take(200)}" }
    }

    private fun run(cmd: List<String>, stdin: String? = null): Pair<Int, String> {
        val process = ProcessBuilder(cmd).redirectErrorStream(stdin == null).start()
        if (stdin != null) {
            process.outputStream.use { it.write(stdin.toByteArray()) }
        } else {
            process.outputStream.close()
        }
        val out = process.inputStream.bufferedReader().readText()
        if (stdin != null) process.errorStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "timeout"
        }
        return process.exitValue() to out
    }
}
