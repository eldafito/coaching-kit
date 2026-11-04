package lib

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A one-time local web page where the coach pastes their intervals.icu API key.
 *
 * The key goes from the browser straight to this process on 127.0.0.1, is checked
 * against intervals.icu and saved in [SecretStore]. It never passes through the
 * chat, a command line or a file in the workspace. The page lives under a random
 * path, answers only to loopback Host headers and shuts down after use or after
 * [TIMEOUT_MINUTES].
 */
class ConnectPage(language: String = "en") {

    data class Connected(val name: String?, val athleteId: String?)

    private val t = if (language.lowercase().startsWith("it")) Strings.IT else Strings.EN
    private val token = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val done = CountDownLatch(1)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "connect-page-timer").apply { isDaemon = true } }

    @Volatile
    var connected: Connected? = null
        private set

    val url: String get() = "http://127.0.0.1:${server.address.port}/connect/$token"

    fun start(): String {
        server.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "connect-page").apply { isDaemon = true } }
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } finally {
                ex.close()
            }
        }
        server.start()
        scheduler.schedule({ stop() }, TIMEOUT_MINUTES, TimeUnit.MINUTES)
        return url
    }

    /** Waits until the key is saved. Returns false on timeout. */
    fun await(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)

    fun stop() {
        runCatching { server.stop(0) }
        scheduler.shutdownNow()
        done.countDown()
    }

    private fun handle(ex: HttpExchange) {
        val host = ex.requestHeaders.getFirst("Host").orEmpty()
        if (!host.startsWith("127.0.0.1:") && !host.startsWith("localhost:")) return send(ex, 403, page(t.title, "<p>Forbidden</p>"))
        if (ex.requestURI.path != "/connect/$token") return send(ex, 404, page(t.title, "<p>Not found</p>"))
        if (connected != null) return send(ex, 200, successPage())
        when (ex.requestMethod) {
            "GET" -> send(ex, 200, formPage(error = null))
            "POST" -> {
                val body = ex.requestBody.readNBytes(4096).toString(Charsets.UTF_8)
                val key = body.split("&")
                    .map { it.split("=", limit = 2) }
                    .firstOrNull { it.size == 2 && it[0] == "key" }
                    ?.let { URLDecoder.decode(it[1], Charsets.UTF_8).trim() }
                    .orEmpty()
                val error = submit(key)
                if (error != null) {
                    send(ex, 200, formPage(error))
                } else {
                    send(ex, 200, successPage())
                    done.countDown()
                    scheduler.schedule({ stop() }, 5, TimeUnit.SECONDS)
                }
            }
            else -> send(ex, 405, page(t.title, "<p>Method not allowed</p>"))
        }
    }

    /** Returns an error message for the coach, or null when the key works and is saved. */
    private fun submit(key: String): String? {
        if (!SecretStore.isValidFormat(key)) return t.badFormat
        val profile = try {
            runBlocking {
                val api = IntervalsApi(key)
                try {
                    api.getRaw("/api/v1/athlete/0")
                } finally {
                    api.close()
                }
            }
        } catch (e: IntervalsApiException) {
            return if (e.status == 401 || e.status == 403) t.rejected else "${t.unreachable} (${e.status})"
        } catch (e: Exception) {
            return t.unreachable
        }
        return try {
            SecretStore.save(key)
            val obj = Json.parseToJsonElement(profile) as? JsonObject
            connected = Connected(
                name = obj?.get("name")?.jsonPrimitive?.contentOrNull,
                athleteId = obj?.get("id")?.jsonPrimitive?.contentOrNull
            )
            null
        } catch (e: Exception) {
            "${t.saveFailed}: ${e.message?.take(200)}"
        }
    }

    private fun send(ex: HttpExchange, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        ex.responseHeaders.add("X-Frame-Options", "DENY")
        ex.responseHeaders.add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun formPage(error: String?): String = page(
        t.title,
        """
        <h1>${t.title}</h1>
        <p>${t.intro}</p>
        <ol>
          <li>${t.step1}</li>
          <li>${t.step2}</li>
          <li>${t.step3}</li>
        </ol>
        ${if (error != null) "<p class=\"error\">${esc(error)}</p>" else ""}
        <form method="post" action="/connect/$token">
          <label for="key">${t.label}</label>
          <input id="key" name="key" type="password" autocomplete="off" spellcheck="false" autofocus required>
          <button type="submit">${t.button}</button>
        </form>
        <p class="note">${t.privacy.replace("{store}", esc(SecretStore.locationName))}</p>
        """
    )

    private fun successPage(): String {
        val who = connected?.name?.let { esc(it) } ?: ""
        return page(
            t.title,
            """
            <h1>${t.successTitle}</h1>
            <p>${t.success.replace("{name}", who)}</p>
            <p><strong>${t.back}</strong></p>
            """
        )
    }

    private fun page(title: String, body: String) = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${esc(title)}</title>
        <style>
          body { font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; max-width: 560px; margin: 48px auto; padding: 0 20px; color: #1f2328; }
          h1 { font-size: 24px; }
          ol { padding-left: 20px; }
          a { color: #0969da; }
          label { display: block; font-weight: 600; margin-top: 24px; }
          input { width: 100%; box-sizing: border-box; font-size: 16px; padding: 10px; margin: 8px 0 16px; border: 1px solid #d0d7de; border-radius: 6px; }
          button { font-size: 16px; padding: 10px 20px; border: 0; border-radius: 6px; background: #1f883d; color: white; cursor: pointer; }
          .error { background: #ffebe9; border: 1px solid #ff818266; padding: 10px; border-radius: 6px; }
          .note { color: #59636e; font-size: 14px; margin-top: 32px; }
        </style></head>
        <body>$body</body></html>
    """.trimIndent()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private class Strings(
        val title: String,
        val intro: String,
        val step1: String,
        val step2: String,
        val step3: String,
        val label: String,
        val button: String,
        val privacy: String,
        val badFormat: String,
        val rejected: String,
        val unreachable: String,
        val saveFailed: String,
        val successTitle: String,
        val success: String,
        val back: String
    ) {
        companion object {
            val EN = Strings(
                title = "Connect Coaching Kit to intervals.icu",
                intro = "Coaching Kit needs your intervals.icu API key to read your athletes' data and upload the workouts you approve.",
                step1 = "Open <a href=\"https://intervals.icu/settings\" target=\"_blank\" rel=\"noopener\">intervals.icu → Settings</a> (log in with your coach account).",
                step2 = "Scroll down to <strong>Developer Settings</strong> and show or generate the <strong>API key</strong>.",
                step3 = "Copy the key and paste it below.",
                label = "API key",
                button = "Connect",
                privacy = "The key stays on this computer ({store}). It is not sent to Claude and is not written in your coaching folder.",
                badFormat = "This does not look like an API key: copy it again from intervals.icu, without spaces.",
                rejected = "intervals.icu did not accept this key. Check you copied the whole key, from the account you coach with.",
                unreachable = "Could not reach intervals.icu. Check your internet connection and try again.",
                saveFailed = "The key works but could not be saved",
                successTitle = "Connected ✓",
                success = "Coaching Kit is connected to intervals.icu as <strong>{name}</strong>.",
                back = "You can close this page and go back to Claude."
            )
            val IT = Strings(
                title = "Collega Coaching Kit a intervals.icu",
                intro = "Coaching Kit ha bisogno della tua API key di intervals.icu per leggere i dati dei tuoi atleti e caricare gli allenamenti che approvi.",
                step1 = "Apri <a href=\"https://intervals.icu/settings\" target=\"_blank\" rel=\"noopener\">intervals.icu → Settings</a> (accedi con il tuo account da allenatore).",
                step2 = "Scorri fino a <strong>Developer Settings</strong> e mostra o genera la <strong>API key</strong>.",
                step3 = "Copia la chiave e incollala qui sotto.",
                label = "API key",
                button = "Collega",
                privacy = "La chiave resta su questo computer ({store}). Non viene inviata a Claude e non viene scritta nella tua cartella di lavoro.",
                badFormat = "Questa non sembra una API key: copiala di nuovo da intervals.icu, senza spazi.",
                rejected = "intervals.icu non ha accettato questa chiave. Controlla di averla copiata tutta, dall'account con cui alleni.",
                unreachable = "Impossibile raggiungere intervals.icu. Controlla la connessione a internet e riprova.",
                saveFailed = "La chiave funziona ma non è stato possibile salvarla",
                successTitle = "Collegato ✓",
                success = "Coaching Kit è collegato a intervals.icu come <strong>{name}</strong>.",
                back = "Puoi chiudere questa pagina e tornare su Claude."
            )
        }
    }

    companion object {
        const val TIMEOUT_MINUTES = 15L

        /** Opens [url] in the default browser. Returns false if it could not. */
        fun openInBrowser(url: String): Boolean = try {
            val cmd = when (AppDirs.os) {
                AppDirs.Os.MAC -> listOf("/usr/bin/open", url)
                AppDirs.Os.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                AppDirs.Os.LINUX -> listOf("xdg-open", url)
            }
            ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }
}
