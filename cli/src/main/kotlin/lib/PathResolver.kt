package lib

import java.io.File

/**
 * Resolves canonical paths inside the coach's workspace.
 *
 * The workspace root is the directory containing `coach-preferences.md`.
 * It is taken from `COACHING_WORKSPACE` if set, otherwise found by walking up
 * from the current working directory.
 */
object PathResolver {

    private const val MARKER = "coach-preferences.md"

    val projectRoot: File by lazy {
        System.getenv("COACHING_WORKSPACE")?.takeIf { it.isNotBlank() }?.let { path ->
            val dir = File(path).absoluteFile
            require(File(dir, MARKER).exists()) { "COACHING_WORKSPACE=$path does not contain $MARKER" }
            return@lazy dir
        }
        val cwd = File(System.getProperty("user.dir")).absoluteFile
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, MARKER).exists() }
            ?: error(
                "Cannot locate the coaching workspace from ${cwd.absolutePath}: " +
                    "no $MARKER found here or in any parent directory. " +
                    "Run the setup skill first, or set COACHING_WORKSPACE."
            )
    }

    fun athleteDir(name: String): File =
        File(projectRoot, "athletes/$name").also {
            require(it.exists()) { "Athlete directory not found: ${it.absolutePath}" }
        }

    fun athleteSnapshotDir(name: String): File =
        File(athleteDir(name), "data-snapshots").also { it.mkdirs() }

    fun athleteCurrentState(name: String): File =
        File(athleteDir(name), "current-state.md")

    fun athleteProfile(name: String): File =
        File(athleteDir(name), "profile.md")
}
