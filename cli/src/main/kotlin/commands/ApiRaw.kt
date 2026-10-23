package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.required
import kotlinx.coroutines.runBlocking
import lib.EnvLoader
import lib.IntervalsApi
import java.io.File

/**
 * Raw access to the intervals.icu API for reads and one-off writes.
 * The API key is resolved inside the CLI, so it never appears on a command line,
 * in shell history or in saved tool permissions.
 */
private fun normalizeApiPath(path: String): String {
    val p = if (path.startsWith("/")) path else "/$path"
    return if (p.startsWith("/api/")) p else "/api/v1$p"
}

class ApiGet : Subcommand("get", "GET an intervals.icu API path and print the raw JSON") {

    private val path by argument(
        ArgType.String,
        description = "API path, e.g. /athlete/i123/events/456 or /athlete/i123/wellness?oldest=2026-01-01&newest=2026-01-07"
    )

    override fun execute() = runBlocking {
        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        try {
            println(api.getRaw(normalizeApiPath(path)))
        } finally {
            api.close()
        }
    }
}

class ApiPut : Subcommand("put", "PUT a JSON body to an intervals.icu API path and print the response") {

    private val path by argument(
        ArgType.String,
        description = "API path, e.g. /athlete/i123/sport-settings/456"
    )

    private val bodyFile by option(
        ArgType.String,
        fullName = "body-file",
        description = "File containing the JSON body"
    ).required()

    override fun execute() = runBlocking {
        val body = File(bodyFile).also { require(it.exists()) { "Body file not found: ${it.absolutePath}" } }.readText()
        val api = IntervalsApi(EnvLoader.get("INTERVALS_API_KEY"))
        try {
            println(api.putJson(normalizeApiPath(path), body))
        } finally {
            api.close()
        }
    }
}
