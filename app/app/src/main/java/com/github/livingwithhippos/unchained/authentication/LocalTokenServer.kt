package com.github.livingwithhippos.unchained.authentication

import java.io.BufferedReader
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * Minimal, temporary HTTP server used on Android TV to receive the private Real-Debrid token from
 * another device on the same local network (e.g. the user's phone), since typing it with a remote
 * is painful. It serves a single form page and accepts one valid token submission, after which it
 * stops itself. It must also be stopped when the authentication screen is left.
 *
 * The token travels in plain http on the local network, which is acceptable for a server that only
 * runs while the login screen is open: TLS would require a self signed certificate that phone
 * browsers refuse to open.
 *
 * @param pages localized texts used to build the served web pages
 * @param onTokenReceived called with the submitted token, from a background thread
 */
class LocalTokenServer(
    private val pages: Pages,
    private val onTokenReceived: (String) -> Unit,
) {

    /** Localized texts for the served pages */
    data class Pages(
        val title: String,
        val tokenLabel: String,
        val submitLabel: String,
        val successMessage: String,
        val errorMessage: String,
    )

    private var serverSocket: ServerSocket? = null

    /**
     * Bind the first free port in [PORT_RANGE] on the local network interface and start serving.
     *
     * @return the reachable http address, or null if no local network address or free port was
     *   found
     */
    fun start(): String? {
        val address = findSiteLocalAddress() ?: return null
        val socket = bindFirstFreePort(address) ?: return null
        serverSocket = socket
        thread(isDaemon = true, name = "unchained-token-server") { serve(socket) }
        return "http://${address.hostAddress}:${socket.localPort}"
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Timber.w(e, "Error closing the token server socket")
        }
        serverSocket = null
    }

    private fun serve(socket: ServerSocket) {
        try {
            while (!socket.isClosed) {
                val tokenReceived = socket.accept().use { client -> handleClient(client) }
                // a valid token was received, only one submission is accepted
                if (tokenReceived) stop()
            }
        } catch (e: IOException) {
            // the server socket was closed, the serving thread ends
            Timber.d("Token server stopped: ${e.message}")
        }
    }

    /**
     * Serve a single http request: the form page on GET, the form processing on POST.
     *
     * @return true if a valid token was submitted
     */
    private fun handleClient(client: Socket): Boolean {
        return try {
            client.soTimeout = CLIENT_TIMEOUT_MS
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return false
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: return false
                if (line.isBlank()) break
                if (line.startsWith("content-length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            var tokenReceived = false
            val page =
                if (requestLine.startsWith("POST", ignoreCase = true)) {
                    val token = readToken(reader, contentLength)
                    if (token != null) {
                        tokenReceived = true
                        onTokenReceived(token)
                        messagePage(pages.successMessage)
                    } else {
                        messagePage(pages.errorMessage)
                    }
                } else {
                    formPage()
                }

            val body = page.toByteArray(Charsets.UTF_8)
            val headers =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(headers.toByteArray(Charsets.UTF_8))
                write(body)
                flush()
            }
            tokenReceived
        } catch (e: IOException) {
            Timber.w(e, "Error handling a token server request")
            false
        }
    }

    /** Extract the token field from an url encoded form body, if it looks like a valid token */
    private fun readToken(reader: BufferedReader, contentLength: Int): String? {
        if (contentLength <= 0) return null
        val buffer = CharArray(contentLength.coerceAtMost(MAX_BODY_LENGTH))
        var read = 0
        while (read < buffer.size) {
            val r = reader.read(buffer, read, buffer.size - read)
            if (r == -1) break
            read += r
        }
        val token =
            String(buffer, 0, read)
                .split('&')
                .firstOrNull { it.startsWith("token=") }
                ?.substringAfter('=')
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.trim()
        // same minimum length checked by the manual token field
        return if (token != null && token.length >= MIN_TOKEN_LENGTH) token else null
    }

    /** Find the local network (site local) ipv4 address of this device, if any */
    private fun findSiteLocalAddress(): InetAddress? =
        try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        } catch (e: Exception) {
            Timber.w(e, "Error looking for the local network address")
            null
        }

    /** Bind the first free port of [PORT_RANGE], only on [address], not on all the interfaces */
    private fun bindFirstFreePort(address: InetAddress): ServerSocket? {
        for (port in PORT_RANGE) {
            try {
                return ServerSocket(port, BACKLOG, address)
            } catch (e: IOException) {
                // port already in use, try the next one
            }
        }
        Timber.w("No free port found for the token server in $PORT_RANGE")
        return null
    }

    private fun formPage(): String =
        """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${pages.title.escapeHtml()}</title>
        <style>$PAGE_STYLE</style></head>
        <body><h2>${pages.title.escapeHtml()}</h2>
        <form method="post" action="/">
        <label for="token">${pages.tokenLabel.escapeHtml()}</label>
        <input type="text" id="token" name="token" autocomplete="off" autofocus>
        <button type="submit">${pages.submitLabel.escapeHtml()}</button>
        </form></body></html>
        """
            .trimIndent()

    private fun messagePage(message: String): String =
        """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${pages.title.escapeHtml()}</title>
        <style>$PAGE_STYLE</style></head>
        <body><h2>${pages.title.escapeHtml()}</h2>
        <p>${message.escapeHtml()}</p></body></html>
        """
            .trimIndent()

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        // predictable ports, easy to type manually if the qr code cannot be scanned
        private val PORT_RANGE = 8080..8100
        private const val BACKLOG = 4
        private const val CLIENT_TIMEOUT_MS = 5000
        private const val MAX_BODY_LENGTH = 10_000
        // same minimum used by AuthenticationFragment for the manual input
        private const val MIN_TOKEN_LENGTH = 40
        private const val PAGE_STYLE =
            "body{font-family:sans-serif;margin:8vh auto;max-width:26em;padding:0 1em;" +
                "background:#121212;color:#eee}" +
                "input,button{font-size:1.1em;width:100%;box-sizing:border-box;margin-top:1em;" +
                "padding:0.6em;border-radius:8px;border:1px solid #666;background:#1e1e1e;color:#eee}" +
                "button{background:#7b5cd6;color:#fff;border:none}"
    }
}
