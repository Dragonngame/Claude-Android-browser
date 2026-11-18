package com.remotebrowser.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Tiny HTTP server for the MCP "Streamable HTTP" transport.
 * Listens only on 127.0.0.1 (ngrok in Termux forwards to it).
 * Requests must use the path /<secret>/mcp, everything else gets 404.
 */
class McpServer(
    private val port: Int,
    private val secret: () -> String,
    private val handler: McpHandler,
    private val onError: (String) -> Unit
) : Thread("mcp-server") {

    @Volatile private var running = true
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    override fun run() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            socket = ss
            while (running) {
                val client = ss.accept()
                pool.execute { serve(client) }
            }
        } catch (e: Exception) {
            if (running) onError("Server error: ${e.message}")
        }
    }

    fun shutdown() {
        running = false
        try {
            socket?.close()
        } catch (ignored: Exception) {
        }
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 30000
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val first = lines[0].split(" ")
                if (first.size < 2) return
                val method = first[0].uppercase()
                val path = first[1].substringBefore("?")
                val headers = HashMap<String, String>()
                for (l in lines.drop(1)) {
                    val k = l.indexOf(':')
                    if (k > 0) headers[l.substring(0, k).trim().lowercase()] = l.substring(k + 1).trim()
                }
                if (headers["expect"]?.lowercase() == "100-continue") {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
                val body = readBody(input, headers)

                val s = secret()
                if (path != "/$s/mcp" && path != "/$s") {
                    send(out, 404, "text/plain", "Not found")
                    return
                }

                when (method) {
                    "POST" -> handlePost(out, String(body, Charsets.UTF_8).trim())
                    "GET" -> send(out, 405, "text/plain", "Method not allowed", "Allow: POST, DELETE")
                    "DELETE" -> send(out, 200, null, "")
                    "OPTIONS" -> send(out, 204, null, "")
                    else -> send(out, 405, "text/plain", "Method not allowed")
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun handlePost(out: OutputStream, text: String) {
        val replies = JSONArray()
        val batch = text.startsWith("[")
        try {
            if (batch) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    handler.handle(arr.getJSONObject(i))?.let { replies.put(it) }
                }
            } else {
                handler.handle(JSONObject(text))?.let { replies.put(it) }
            }
        } catch (e: org.json.JSONException) {
            val err = JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                .put("error", JSONObject().put("code", -32700).put("message", "Parse error"))
            send(out, 400, "application/json", err.toString())
            return
        }
        if (replies.length() == 0) {
            send(out, 202, null, "")
        } else {
            val body = if (batch) replies.toString() else replies.getJSONObject(0).toString()
            send(out, 200, "application/json", body)
        }
    }

    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            state = when (b) {
                '\r'.code -> if (state == 2) 3 else 1
                '\n'.code -> if (state == 1) 2 else if (state == 3) 4 else 0
                else -> 0
            }
            if (state == 4) return String(buf.toByteArray(), Charsets.ISO_8859_1).trimEnd()
            if (buf.size() > 65536) return null
        }
    }

    private fun readLine(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (buf.size() > 0) String(buf.toByteArray(), Charsets.ISO_8859_1) else null
            if (b == '\n'.code) return String(buf.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
            buf.write(b)
        }
    }

    private fun readExact(inp: InputStream, n: Int): ByteArray {
        val data = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(data, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) data else data.copyOf(off)
    }

    private fun readBody(inp: InputStream, h: Map<String, String>): ByteArray {
        val te = h["transfer-encoding"]?.lowercase()
        if (te != null && te.contains("chunked")) {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(inp) ?: break
                val size = sizeLine.substringBefore(";").trim().toIntOrNull(16) ?: break
                if (size == 0) {
                    readLine(inp)
                    break
                }
                out.write(readExact(inp, size))
                readLine(inp)
            }
            return out.toByteArray()
        }
        val len = h["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ByteArray(0)
        return readExact(inp, minOf(len, 10_000_000))
    }

    private fun send(out: OutputStream, code: Int, type: String?, body: String, extra: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n")
        if (extra != null) sb.append(extra).append("\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }
}
