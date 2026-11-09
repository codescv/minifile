package com.minifile.explorer.core.peer

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import java.net.URLEncoder

/**
 * Wire format of the open Hotspot Direct announce (spec §2.4), sent by a phone that joined another phone's hotspot
 * (e.g. via the system Camera `WIFI:` QR) so the hosting phone lists it under "Joined devices" with a Send button.
 *
 * `POST http://<gateway>:<hostPort>/api/peer/announce?port=<myPort>&id=<myId>&name=<myName>&os=android&tok=`
 * with the same fields mirrored in a small JSON body (`Content-Type: application/json`) so hosts that parse either
 * the query (Android) or the body (iOS) accept it.
 */
object PeerAnnounceWire {
    const val PATH = "/api/peer/announce"

    fun url(hostIp: String, hostPort: Int, myId: String, myName: String, myPort: Int, os: String = "android", tok: String = ""): String {
        val hostPart = if (hostIp.contains(':')) "[$hostIp]" else hostIp
        return buildString(256) {
            append("http://").append(hostPart).append(':').append(hostPort).append(PATH)
            append("?port=").append(myPort)
            append("&id=").append(enc(myId))
            append("&name=").append(enc(PeerPathPolicy.sanitizeDisplayName(myName, 64).ifEmpty { "Files" }))
            append("&os=").append(enc(os))
            append("&tok=").append(enc(tok))
        }
    }

    fun body(myId: String, myName: String, myPort: Int, os: String = "android", tok: String = ""): String =
        MiniJson.stringify(
            MiniJson.obj(
                "id" to JsonValue.Str(myId),
                "name" to JsonValue.Str(PeerPathPolicy.sanitizeDisplayName(myName, 64).ifEmpty { "Files" }),
                "ip" to JsonValue.Str(""),
                "port" to JsonValue.Num(myPort),
                "os" to JsonValue.Str(os),
                "tok" to JsonValue.Str(tok),
            ),
        )

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}
