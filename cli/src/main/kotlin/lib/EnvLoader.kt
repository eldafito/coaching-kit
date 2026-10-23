package lib

import java.io.File

/**
 * Resolves configuration values such as `INTERVALS_API_KEY`.
 *
 * Lookup order: process environment first, then `coach-credentials.env` at the
 * workspace root (simple KEY=VALUE lines, `#` comments allowed). The file is
 * optional when the environment already provides the value.
 */
object EnvLoader {

    private val cache = mutableMapOf<String, String>()
    private var loaded = false

    private val envFile: File get() = File(PathResolver.projectRoot, "coach-credentials.env")

    fun load(file: File = envFile) {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('"').trim('\'')
            cache[key] = value
        }
    }

    fun get(key: String): String =
        getOrNull(key) ?: error(
            "Missing $key: export it as an environment variable or add it to ${envFile.absolutePath}"
        )

    fun getOrNull(key: String): String? {
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it }
        load()
        return cache[key]?.takeIf { it.isNotBlank() }
    }
}
