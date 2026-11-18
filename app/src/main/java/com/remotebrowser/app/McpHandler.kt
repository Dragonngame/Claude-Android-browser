package com.remotebrowser.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeoutException

/** JSON-RPC side of MCP: initialize, tools/list, tools/call. */
class McpHandler(private val b: BrowserController, private val log: (String) -> Unit) {

    private val lock = Any()

    companion object {
        const val INSTRUCTIONS =
            "These tools control a real web browser inside the Remote Browser app on the user's Android phone. " +
                "Typical flow: open_url, then get_page to read text or find_elements to get numbered buttons/links/fields, " +
                "then click_element or type_text. Call find_elements again after the page changes. " +
                "Use screenshot when the layout matters, and tap with screenshot coordinates as a last resort. " +
                "The user may be logged in to sites in this browser, so only do what the user asked."
    }

    fun handle(msg: JSONObject): JSONObject? {
        val method = msg.optString("method", "")
        // Notifications (no id) and client responses get no reply.
        if (method.isEmpty() || !msg.has("id")) return null
        val id = msg.get("id")
        return try {
            val result: JSONObject = when (method) {
                "initialize" -> {
                    val asked = msg.optJSONObject("params")?.optString("protocolVersion", "") ?: ""
                    JSONObject()
                        .put("protocolVersion", asked.ifEmpty { "2025-06-18" })
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject().put("name", "phone-browser").put("version", "1.0"))
                        .put("instructions", INSTRUCTIONS)
                }
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", toolList())
                "tools/call" -> synchronized(lock) { callTool(msg.optJSONObject("params") ?: JSONObject()) }
                else -> return rpcError(id, -32601, "Method not found: $method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (e: Exception) {
            rpcError(id, -32603, e.toString())
        }
    }

    private fun rpcError(id: Any, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun callTool(p: JSONObject): JSONObject {
        val name = p.optString("name")
        val a = p.optJSONObject("arguments") ?: JSONObject()
        val url = a.optString("url")
        log(if (url.isNotEmpty()) "$name $url" else name)
        return try {
            if (name == "screenshot") {
                val (data, info) = b.screenshot()
                JSONObject().put(
                    "content", JSONArray()
                        .put(JSONObject().put("type", "image").put("data", data).put("mimeType", "image/jpeg"))
                        .put(JSONObject().put("type", "text").put("text", info))
                ).put("isError", false)
            } else {
                val out = when (name) {
                    "open_url" -> b.openUrl(a.getString("url"))
                    "get_page" -> b.getPage(
                        a.optInt("offset", 0).coerceAtLeast(0),
                        a.optInt("max_chars", 12000).coerceIn(500, 50000)
                    )
                    "find_elements" -> b.findElements(a.optBoolean("visible_only", false))
                    "click_element" -> b.clickElement(a.getInt("index"))
                    "type_text" -> b.typeText(
                        a.getString("text"),
                        if (a.has("index") && !a.isNull("index")) a.getInt("index") else null,
                        a.optBoolean("clear", false),
                        a.optBoolean("submit", false)
                    )
                    "press_key" -> b.pressKey(a.getString("key"))
                    "scroll" -> {
                        val amt = a.optDouble("amount", 1.0)
                        b.scroll(a.optString("direction", "down") != "up", if (amt.isNaN()) 1.0 else amt.coerceIn(0.1, 10.0))
                    }
                    "tap" -> b.tap(a.getDouble("x"), a.getDouble("y"))
                    "go_back" -> b.goBack()
                    "run_js" -> b.runJs(a.getString("code"))
                    "wait" -> b.waitSeconds(a.optDouble("seconds", 2.0))
                    else -> throw IllegalArgumentException("Unknown tool: $name")
                }
                textResult(out, false)
            }
        } catch (e: TimeoutException) {
            textResult("Timed out. The Remote Browser app has to be open on the phone's screen.", true)
        } catch (e: Exception) {
            textResult("Error: ${e.message ?: e.toString()}", true)
        }
    }

    private fun textResult(t: String, isError: Boolean): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", t)))
            .put("isError", isError)

    private fun prop(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()): JSONObject =
        JSONObject().put("name", name).put("description", desc).put(
            "inputSchema",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))
        )

    private fun toolList(): JSONArray = JSONArray()
        .put(
            tool(
                "open_url", "Open a web address (or search words) in the phone browser and wait for it to load.",
                JSONObject().put("url", prop("string", "Full URL, a domain like example.com, or search words")),
                listOf("url")
            )
        )
        .put(
            tool(
                "get_page", "Read the current page's title, URL and visible text. Long pages are split; use offset to read further.",
                JSONObject()
                    .put("offset", prop("integer", "Character offset to start from (default 0)"))
                    .put("max_chars", prop("integer", "Max characters to return (default 12000)"))
            )
        )
        .put(
            tool(
                "find_elements",
                "List the buttons, links and input fields on the page with numbers. Use the numbers with click_element and type_text. " +
                    "Numbers change when the page changes, so call this again after navigating.",
                JSONObject().put("visible_only", prop("boolean", "Only list elements that are on screen right now (default false)"))
            )
        )
        .put(
            tool(
                "click_element", "Tap an element by its number from find_elements, then wait for any page load.",
                JSONObject().put("index", prop("integer", "Element number from find_elements")),
                listOf("index")
            )
        )
        .put(
            tool(
                "type_text",
                "Type text into a field. Pass index to tap that field first, or leave it out to type into the field that already has focus.",
                JSONObject()
                    .put("text", prop("string", "Text to type"))
                    .put("index", prop("integer", "Field number from find_elements"))
                    .put("clear", prop("boolean", "Replace what is already in the field (default false)"))
                    .put("submit", prop("boolean", "Press Enter afterwards (default false)")),
                listOf("text")
            )
        )
        .put(
            tool(
                "press_key", "Press a key in the page: enter, tab, backspace, escape, space, up, down, left, right.",
                JSONObject().put("key", prop("string", "Key name")),
                listOf("key")
            )
        )
        .put(
            tool(
                "scroll", "Scroll the page up or down.",
                JSONObject()
                    .put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("down", "up"))))
                    .put("amount", prop("number", "How many screens to scroll (default 1)"))
            )
        )
        .put(tool("screenshot", "Take a screenshot of what the phone browser shows right now."))
        .put(
            tool(
                "tap", "Tap a point given in the pixel coordinates of the last screenshot. Use when find_elements can't reach something.",
                JSONObject()
                    .put("x", prop("number", "X in screenshot pixels"))
                    .put("y", prop("number", "Y in screenshot pixels")),
                listOf("x", "y")
            )
        )
        .put(tool("go_back", "Go back to the previous page."))
        .put(
            tool(
                "run_js", "Run JavaScript in the current page. Write a function body and use return to send a value back.",
                JSONObject().put("code", prop("string", "JavaScript function body")),
                listOf("code")
            )
        )
        .put(
            tool(
                "wait", "Wait a few seconds (max 30) for the page to finish something.",
                JSONObject().put("seconds", prop("number", "Seconds to wait"))
            )
        )
}
