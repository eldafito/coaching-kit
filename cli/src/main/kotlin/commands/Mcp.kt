package commands

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import lib.BuildInfo
import lib.ConnectPage
import lib.SecretStore
import mcp.CoachTools
import mcp.McpServer
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/** Runs Coaching Kit as an MCP server on stdin/stdout (started by Claude, not by hand). */
class McpCommand : Subcommand("mcp", "Run as an MCP server over stdio (used by Claude Desktop / Claude Code)") {

    override fun execute() {
        // stdout carries the protocol only: anything else printed by mistake goes to stderr.
        val protocolOut = PrintStream(FileOutputStream(FileDescriptor.out), false, Charsets.UTF_8)
        System.setOut(System.err)
        val server = McpServer(
            name = "coaching-kit",
            version = BuildInfo.VERSION,
            instructions = CoachTools.INSTRUCTIONS,
            tools = CoachTools().all()
        )
        server.run(System.`in`.bufferedReader(Charsets.UTF_8), protocolOut)
    }
}

/** Opens the local page where the coach pastes their API key, and waits until it is saved. */
class ConnectCommand : Subcommand("connect", "Connect to intervals.icu: paste your API key in a browser page, stored securely") {

    private val language by option(
        ArgType.String, fullName = "language",
        description = "Page language: en or it"
    ).default("en")

    private val noBrowser by option(
        ArgType.Boolean, fullName = "no-browser",
        description = "Print the page address instead of opening the browser"
    ).default(false)

    private val forget by option(
        ArgType.Boolean, fullName = "forget",
        description = "Remove the saved API key instead"
    ).default(false)

    override fun execute() {
        if (forget) {
            SecretStore.delete()
            println("Removed the saved API key (${SecretStore.locationName}).")
            return
        }
        val page = ConnectPage(language)
        val url = page.start()
        if (noBrowser || !ConnectPage.openInBrowser(url)) println("Open this page in your browser:")
        println(url)
        println("Waiting for the key (up to ${ConnectPage.TIMEOUT_MINUTES} minutes)...")
        page.await(ConnectPage.TIMEOUT_MINUTES, TimeUnit.MINUTES)
        val connected = page.connected
        page.stop()
        if (connected != null) {
            println("Connected as ${connected.name ?: "?"} (${connected.athleteId ?: "?"}). Key saved in ${SecretStore.locationName}.")
        } else {
            System.err.println("Timed out: no key was saved.")
            kotlin.system.exitProcess(1)
        }
    }
}
