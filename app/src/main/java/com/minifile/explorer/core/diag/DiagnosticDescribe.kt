package com.minifile.explorer.core.diag

import com.minifile.explorer.core.peer.HotspotLinkMessage

/** Compact, secret-free one-line descriptions of protocol objects for diagnostic logs. */
object DiagnosticDescribe {
    /** BLE control message; the hotspot passphrase is never included and tokens are reduced to a 4-char prefix. */
    fun linkMessage(msg: HotspotLinkMessage): String = when (msg) {
        is HotspotLinkMessage.Hello ->
            "hello id=${msg.id.take(8)} name=${msg.name} os=${msg.os} ver=${msg.ver} files=${msg.files} " +
                "bytes=${msg.bytes} tok=${DiagnosticLogRedactor.tokenPrefix(msg.tok)} lan=${msg.lan} v=${msg.v}"
        is HotspotLinkMessage.Ack ->
            "ack id=${msg.id.take(8)} name=${msg.name} os=${msg.os} caps=${msg.caps} port=${msg.port} " +
                "lan=${msg.lan} psm=${msg.psm}"
        is HotspotLinkMessage.Decline -> "decline reason=${msg.reason}"
        is HotspotLinkMessage.Link -> "link mode=${msg.mode.wire}"
        is HotspotLinkMessage.Hotspot ->
            "hotspot ssid=${msg.ssid} sec=${msg.sec} ip=${msg.ip.ifEmpty { "?" }} port=${msg.port} " +
                "tok=${DiagnosticLogRedactor.tokenPrefix(msg.tok)} pass=${DiagnosticLogRedactor.REDACTED}"
        is HotspotLinkMessage.Joined -> "joined ip=${msg.ip}"
        is HotspotLinkMessage.Ready -> "ready ip=${msg.ip} port=${msg.port}"
        is HotspotLinkMessage.Done -> "done ok=${msg.ok}"
        is HotspotLinkMessage.Error -> "err code=${msg.code}"
    }
}
