package mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.PrintStream

/** A tool exposed over MCP. [handler] receives the call arguments. */
class Tool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val destructive: Boolean = false,
    val openWorld: Boolean = true,
    val handler: suspend (JsonObject) -> ToolResult
)

data class ToolResult(val text: String, val isError: Boolean = false)

/**
 * Minimal Model Context Protocol server over stdio (newline-delimited JSON-RPC 2.0),
 * exposing tools only. Requests are handled one at a time, in order.
 *
 * Hand-written on purpose: it keeps the dependency set (and the GraalVM native
 * image configuration) unchanged.
 */
class McpServer(
    private val name: String,
    private val version: String,
    private val instructions: String,
    tools: List<Tool>
) {
    private val tools = tools.associateBy { it.name }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun run(input: BufferedReader, output: PrintStream) {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            val message = try {
                json.parseToJsonElement(line)
            } catch (_: Exception) {
                write(output, error(JsonNull, -32700, "Parse error"))
                continue
            }
            val reply: JsonElement? = when (message) {
                is JsonArray -> message.mapNotNull { (it as? JsonObject)?.let(::dispatch) }
                    .takeIf { it.isNotEmpty() }?.let { JsonArray(it) }
                is JsonObject -> dispatch(message)
                else -> error(JsonNull, -32600, "Invalid Request")
            }
            if (reply != null) write(output, reply)
        }
    }

    private fun write(output: PrintStream, element: JsonElement) {
        synchronized(output) {
            output.print(element.toString())
            output.print("\n")
            output.flush()
        }
    }

    /** Returns the response to send, or null for notifications and responses. */
    private fun dispatch(msg: JsonObject): JsonObject? {
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull ?: return null
        val id = msg["id"] ?: return null // notification: nothing to answer
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(tools.values.map(::describe))) })
                "tools/call" -> callTool(id, params)
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            error(id, -32603, "Internal error: ${e.message?.take(300)}")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
            put("serverInfo", buildJsonObject {
                put("name", name)
                put("title", "Coaching Kit")
                put("version", this@McpServer.version)
            })
            put("instructions", instructions)
        }
    }

    private fun describe(tool: Tool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("title", tool.title)
        put("description", tool.description)
        put("inputSchema", tool.inputSchema)
        put("annotations", buildJsonObject {
            put("title", tool.title)
            put("readOnlyHint", tool.readOnly)
            put("destructiveHint", tool.destructive)
            put("openWorldHint", tool.openWorld)
        })
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val tool = tools[name] ?: return error(id, -32602, "Unknown tool: $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val outcome = try {
            runBlocking { tool.handler(args) }
        } catch (e: Exception) {
            ToolResult(Errors.explain(e), isError = true)
        }
        return result(id, buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", outcome.text)
                })
            })
            put("isError", outcome.isError)
        })
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    companion object {
        /** Newest first: offered when the client asks for a version we do not know. */
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    }
}
