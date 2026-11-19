package com.remotebrowser.app

import com.ngrok.Forwarder
import com.ngrok.Session
import java.net.URL

/**
 * Runs the ngrok tunnel inside the app (no Termux needed).
 * Keeps reconnecting until stop() is called.
 */
class Tunnel(private val onStatus: (String) -> Unit) {

    @Volatile private var running = false
    @Volatile private var session: Session? = null
    @Volatile private var forwarder: Forwarder.Endpoint? = null
    private var thread: Thread? = null

    fun start(token: String, domain: String, port: Int) {
        stop()
        running = true
        val t = Thread({
            while (running) {
                try {
                    onStatus("Tunnel: connecting...")
                    val s = Session.withAuthtoken(token).connect()
                    session = s
                    val builder = s.httpEndpoint()
                    if (domain.isNotEmpty()) builder.domain(domain)
                    val f = builder.forward(URL("http://127.0.0.1:$port"))
                    forwarder = f
                    onStatus("Online: " + f.url)
                    f.join() // blocks while the tunnel is up
                    if (running) onStatus("Tunnel dropped, reconnecting...")
                } catch (e: Throwable) {
                    var msg = e.message ?: e.toString()
                    if (msg.contains("runtime not initialized")) {
                        msg = "ngrok start-up: " + com.ngrok.Runtime.diagnose()
                    }
                    if (running) onStatus("Tunnel error: $msg (retrying)")
                }
                closeQuietly()
                if (running) {
                    try {
                        Thread.sleep(5000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }, "ngrok-tunnel")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        closeQuietly()
        thread?.interrupt()
        thread = null
    }

    private fun closeQuietly() {
        try {
            forwarder?.close()
        } catch (e: Throwable) {
        }
        try {
            session?.close()
        } catch (e: Throwable) {
        }
        forwarder = null
        session = null
    }
}
