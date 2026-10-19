package se.jptv.phone

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Liten webbsida på TV:n som mobilen öppnar via QR-koden, så att långa adresser kan klistras in
 * från mobilen istället för att skrivas med fjärren. Körs bara medan QR-koden visas.
 */
class PhoneInputServer(
    private val currentValues: () -> Pair<String?, String?>,
    private val onSubmit: (m3u: String?, epg: String?) -> Unit,
) {
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    val port: Int get() = server?.localPort ?: 0

    /** Startar servern och returnerar adressen mobilen ska öppna, eller null om nätverk saknas. */
    fun start(): String? {
        val ip = localIp() ?: return null
        val socket = try {
            ServerSocket(PREFERRED_PORT)
        } catch (e: Exception) {
            ServerSocket(0)
        }
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handle(client) }, "jptv-phone-client").apply { isDaemon = true }.start()
            }
        }, "jptv-phone-server").apply { isDaemon = true }.start()
        return "http://$ip:${socket.localPort}/"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val method = requestLine.substringBefore(' ')
            val body = if (method == "POST" && contentLength in 1..100_000) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val html = if (method == "POST") {
                val form = body.split('&').associate { part ->
                    val k = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                    val v = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8").trim()
                    k to v
                }
                val m3u = form["m3u"]?.takeIf { it.isNotEmpty() }
                val epg = form["epg"]
                main.post { onSubmit(m3u, epg) }
                page("<h2>Klart!</h2><p>Adresserna är skickade till TV:n. Du kan stänga sidan.</p>")
            } else {
                val (m3u, epg) = currentValues()
                page(
                    """
                    <h2>JPTV</h2>
                    <form method="post">
                      <label>M3U-lista (eller Xtream get.php-länk)</label>
                      <input name="m3u" value="${esc(m3u)}" placeholder="http://…" autocapitalize="off" autocorrect="off">
                      <label>TV-guide (XMLTV) – lämna tomt för automatisk</label>
                      <input name="epg" value="${esc(epg)}" placeholder="http://…/xmltv.php" autocapitalize="off" autocorrect="off">
                      <button type="submit">Skicka till TV:n</button>
                    </form>
                    """.trimIndent(),
                )
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
    }

    private fun esc(v: String?): String =
        (v ?: "").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(content: String) = """
        <!doctype html><html lang="sv"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>JPTV</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0e1116;color:#eceff4;margin:0;padding:24px;max-width:640px}
          h2{color:#f2a541}
          label{display:block;margin:18px 0 6px;color:#9aa4b2}
          input{width:100%;box-sizing:border-box;padding:14px;font-size:16px;border-radius:8px;border:1px solid #222935;background:#181d25;color:#eceff4}
          button{margin-top:24px;width:100%;padding:16px;font-size:17px;border:0;border-radius:8px;background:#f2a541;color:#1a1206;font-weight:600}
        </style></head><body>$content</body></html>
    """.trimIndent()

    companion object {
        const val PREFERRED_PORT = 8765

        /** TV:ns adress på hemmanätet (Wi-Fi eller Ethernet). */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }

        /** QR-kod som bild. */
        fun qrBitmap(text: String, size: Int = 480): Bitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
