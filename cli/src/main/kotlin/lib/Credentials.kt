package lib

import java.io.File

/**
 * Resolves the intervals.icu API key.
 *
 * Lookup order:
 *   1. `INTERVALS_API_KEY` environment variable;
 *   2. the secure store written by `connect` ([SecretStore]);
 *   3. `coach-credentials.env` in the coach's workspace (the original setup),
 *      found from `COACHING_WORKSPACE`, `CLAUDE_PROJECT_DIR` or the current directory.
 */
object Credentials {

    enum class Source(val label: String) {
        ENVIRONMENT("environment variable INTERVALS_API_KEY"),
        SECURE_STORE("secure store"),
        WORKSPACE_FILE("coach-credentials.env in the workspace")
    }

    data class Resolved(val key: String, val source: Source)

    fun resolve(): Resolved? {
        System.getenv("INTERVALS_API_KEY")?.takeIf { it.isNotBlank() }?.let {
            return Resolved(it.trim(), Source.ENVIRONMENT)
        }
        runCatching { SecretStore.read() }.getOrNull()?.let { return Resolved(it, Source.SECURE_STORE) }
        workspaceFileKey()?.let { return Resolved(it, Source.WORKSPACE_FILE) }
        return null
    }

    fun apiKeyOrNull(): String? = resolve()?.key

    fun requireApiKey(): String = apiKeyOrNull() ?: error(
        "Missing INTERVALS_API_KEY: run `coach connect`, export it as an environment variable, " +
            "or add it to coach-credentials.env in your workspace"
    )

    private fun workspaceFileKey(): String? {
        val candidates = buildList {
            System.getenv("COACHING_WORKSPACE")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            System.getenv("CLAUDE_PROJECT_DIR")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            add(File(System.getProperty("user.dir")))
        }
        for (start in candidates) {
            val root = generateSequence(start.absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "coach-preferences.md").exists() } ?: continue
            val envFile = File(root, "coach-credentials.env")
            if (!envFile.exists()) continue
            envFile.readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("INTERVALS_API_KEY=") }
                ?.substringAfter("=")
                ?.trim()?.trim('"')?.trim('\'')
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }
}
