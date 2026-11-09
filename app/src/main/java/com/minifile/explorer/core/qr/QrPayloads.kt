package com.minifile.explorer.core.qr

import java.io.ByteArrayOutputStream

/** Payloads understood by the in-app QR scanner (spec §2.5). */
sealed class QrPayload {
    /** `sec`: "wpa2" (T:WPA), "wpa3" (T:SAE), "wep", "nopass". */
    data class Wifi(
        val ssid: String,
        val password: String,
        val sec: String,
        val hidden: Boolean = false,
    ) : QrPayload()

    data class Hotspot(
        val ssid: String,
        val password: String,
        val sec: String,
        val ip: String,
        val port: Int,
        val id: String?,
        val name: String?,
        val tok: String?,
    ) : QrPayload()

    data class Peer(val ip: String, val port: Int, val id: String?, val name: String?) : QrPayload()

    /** Plain HTTP(S) share URL; port defaults to 80 / 443. */
    data class Http(val host: String, val port: Int, val https: Boolean) : QrPayload()
}

object QrPayloads {
    private const val WIFI_PREFIX = "WIFI:"
    private const val MINIFILE_SCHEME = "minifile://"

    /** Backslash-escapes `\ ; , : "` for the `WIFI:` QR format. */
    fun escapeWifi(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** `WIFI:T:WPA;S:..;P:..;;` (`T:SAE` only for wpa3, `T:WEP` for wep, `T:nopass` when open). */
    fun buildWifi(ssid: String, password: String, sec: String): String {
        val s = sec.lowercase()
        if (s == "nopass" || password.isEmpty()) {
            return "WIFI:T:nopass;S:${escapeWifi(ssid)};;"
        }
        val t = when (s) {
            "wpa3" -> "SAE"
            "wep" -> "WEP"
            else -> "WPA"
        }
        return "WIFI:T:$t;S:${escapeWifi(ssid)};P:${escapeWifi(password)};;"
    }

    /**
     * Universal **Camera** `WIFI:` QR for a hotspot this device hosts (iPhone Camera / Android camera / Files).
     * Every WPA-family [sec] (`wpa2`, `wpa3t`, `wpa3`, `sae`, `wpa`…) is emitted as `T:WPA`: iOS Camera then
     * auto-negotiates WPA2/WPA3, whereas `T:SAE` makes it fail to join. `wep` → `T:WEP`; open / empty
     * [password] → `T:nopass`. The exact `sec` still travels in the `minifile://hotspot` URI and BLE JSON.
     */
    fun buildCameraWifi(ssid: String, password: String, sec: String): String {
        val s = sec.trim().lowercase()
        val qrSec = when {
            password.isEmpty() || s == "open" || s == "nopass" || s == "none" -> "nopass"
            s == "wep" -> "wep"
            else -> "wpa2"
        }
        return buildWifi(ssid, password, qrSec)
    }

    fun buildHotspotUri(h: QrPayload.Hotspot): String {
        val params = ArrayList<Pair<String, String>>()
        params += "v" to "1"
        params += "s" to h.ssid
        params += "p" to h.password
        params += "sec" to h.sec
        params += "ip" to h.ip
        params += "port" to h.port.toString()
        h.id?.let { params += "id" to it }
        h.name?.let { params += "n" to it }
        h.tok?.let { params += "tok" to it }
        return "${MINIFILE_SCHEME}hotspot?" + params.joinToString("&") { "${it.first}=${percentEncode(it.second)}" }
    }

    fun buildPeerUri(p: QrPayload.Peer): String {
        val params = ArrayList<Pair<String, String>>()
        params += "ip" to p.ip
        params += "port" to p.port.toString()
        p.id?.let { params += "id" to it }
        p.name?.let { params += "n" to it }
        return "${MINIFILE_SCHEME}peer?" + params.joinToString("&") { "${it.first}=${percentEncode(it.second)}" }
    }

    /** Parses any supported scanner payload; null when unrecognised or invalid. Never throws. */
    fun parse(text: String): QrPayload? {
        return try {
            val t = text.trim()
            when {
                t.startsWith(WIFI_PREFIX, ignoreCase = true) -> parseWifi(t.substring(WIFI_PREFIX.length))
                t.startsWith(MINIFILE_SCHEME, ignoreCase = true) -> parseMinifile(t.substring(MINIFILE_SCHEME.length))
                t.startsWith("http://", ignoreCase = true) -> parseHttp(t.substring(7), https = false)
                t.startsWith("https://", ignoreCase = true) -> parseHttp(t.substring(8), https = true)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ WIFI:

    private fun parseWifi(body: String): QrPayload.Wifi? {
        var ssid: String? = null
        var password = ""
        var type: String? = null
        var hidden = false
        for (field in splitUnescaped(body, ';')) {
            if (field.isEmpty()) continue
            val colon = indexOfUnescaped(field, ':')
            if (colon <= 0) continue
            val key = field.substring(0, colon).trim().uppercase()
            val rawValue = field.substring(colon + 1)
            when (key) {
                "S" -> ssid = unescapeWifiValue(rawValue)
                "P" -> password = unescapeWifiValue(rawValue)
                "T" -> type = unescapeWifiValue(rawValue).trim()
                "H" -> hidden = unescapeWifiValue(rawValue).trim().equals("true", ignoreCase = true)
            }
        }
        val s = ssid ?: return null
        if (s.isEmpty()) return null
        val sec = when (type?.uppercase()) {
            "WPA", "WPA2", "WPA/WPA2", "WPA2/WPA3" -> "wpa2"
            "SAE", "WPA3" -> "wpa3"
            "WEP" -> "wep"
            "NOPASS", "" -> "nopass"
            null -> if (password.isEmpty()) "nopass" else "wpa2"
            else -> if (password.isEmpty()) "nopass" else "wpa2"
        }
        return QrPayload.Wifi(ssid = s, password = if (sec == "nopass") "" else password, sec = sec, hidden = hidden)
    }

    private fun splitUnescaped(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cur.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c == sep) {
                out += cur.toString()
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun indexOfUnescaped(s: String, target: Char): Int {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == target) return i
            i++
        }
        return -1
    }

    /** Removes backslash escapes; strips one pair of unescaped surrounding double quotes. */
    private fun unescapeWifiValue(raw: String): String {
        var v = raw
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") && !endsWithEscapedChar(v)) {
            v = v.substring(1, v.length - 1)
        }
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                sb.append(v[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** True when the last char is preceded by an odd number of backslashes. */
    private fun endsWithEscapedChar(v: String): Boolean {
        var n = 0
        var i = v.length - 2
        while (i >= 0 && v[i] == '\\') {
            n++
            i--
        }
        return n % 2 == 1
    }

    // ------------------------------------------------------------------ minifile://

    private fun parseMinifile(rest: String): QrPayload? {
        val q = rest.indexOf('?')
        val host = (if (q >= 0) rest.substring(0, q) else rest).trimEnd('/').lowercase()
        val query = if (q >= 0) rest.substring(q + 1).substringBefore('#') else ""
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = percentDecode(if (eq >= 0) pair.substring(0, eq) else pair) ?: return null
            val v = percentDecode(if (eq >= 0) pair.substring(eq + 1) else "") ?: return null
            if (!params.containsKey(k)) params[k] = v
        }
        val ip = params["ip"]?.takeIf { it.isNotBlank() } ?: return null
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val id = params["id"]?.takeIf { it.isNotEmpty() }
        val name = params["n"]?.takeIf { it.isNotEmpty() }
        return when (host) {
            "hotspot" -> {
                val ssid = params["s"]?.takeIf { it.isNotEmpty() } ?: return null
                QrPayload.Hotspot(
                    ssid = ssid,
                    password = params["p"] ?: "",
                    sec = params["sec"]?.takeIf { it.isNotEmpty() } ?: "wpa2",
                    ip = ip,
                    port = port,
                    id = id,
                    name = name,
                    tok = params["tok"]?.takeIf { it.isNotEmpty() },
                )
            }
            "peer" -> QrPayload.Peer(ip = ip, port = port, id = id, name = name)
            else -> null
        }
    }

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    /** RFC 3986 percent-encoding (UTF-8, uppercase hex; space → %20). */
    internal fun percentEncode(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && isUnreserved(c)) {
                sb.append(c)
            } else {
                val v = b.toInt() and 0xFF
                sb.append('%').append("0123456789ABCDEF"[v ushr 4]).append("0123456789ABCDEF"[v and 0xF])
            }
        }
        return sb.toString()
    }

    /** Percent-decodes UTF-8 (`+` is kept literally); null when malformed. */
    internal fun percentDecode(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ http(s)://

    private fun parseHttp(rest: String, https: Boolean): QrPayload.Http? {
        var authority = rest
        for (stop in charArrayOf('/', '?', '#')) {
            val idx = authority.indexOf(stop)
            if (idx >= 0) authority = authority.substring(0, idx)
        }
        authority = authority.substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host: String
        var portStr: String? = null
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            host = authority.substring(1, end)
            val after = authority.substring(end + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(":")) return null
                portStr = after.substring(1)
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                portStr = authority.substring(colon + 1)
            } else {
                host = authority
            }
        }
        if (host.isEmpty()) return null
        val port = if (portStr.isNullOrEmpty()) {
            if (https) 443 else 80
        } else {
            portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return QrPayload.Http(host = host, port = port, https = https)
    }
}
