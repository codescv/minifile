package com.minifile.explorer.core.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadsTest {

    private val hotspotVector = "minifile://hotspot?v=1&s=AndroidShare_7781&p=k2Jd9xQ1&sec=wpa2&ip=192.168.197.1" +
        "&port=8080&id=ab12cd&n=Pixel%208&tok=0123456789abcdef0123456789abcdef"

    @Test
    fun specWifiVector() {
        val p = QrPayloads.parse("WIFI:T:WPA;S:Android\\;Share_1234;P:p\\:a\\\\s\\;s;;") as QrPayload.Wifi
        assertEquals("Android;Share_1234", p.ssid)
        assertEquals("p:a\\s;s", p.password)
        assertEquals("wpa2", p.sec)
        assertEquals(false, p.hidden)
    }

    @Test
    fun escapeAndBuildWifi() {
        assertEquals("a\\\\b\\;c\\,d\\:e\\\"f", QrPayloads.escapeWifi("a\\b;c,d:e\"f"))
        assertEquals(
            "WIFI:T:WPA;S:Android\\;Share_1234;P:p\\:a\\\\s\\;s;;",
            QrPayloads.buildWifi("Android;Share_1234", "p:a\\s;s", "wpa2"),
        )
        assertEquals("WIFI:T:SAE;S:Net;P:pw123456;;", QrPayloads.buildWifi("Net", "pw123456", "wpa3"))
        assertEquals("WIFI:T:WPA;S:Net;P:pw123456;;", QrPayloads.buildWifi("Net", "pw123456", "wpa3t"))
        assertEquals("WIFI:T:nopass;S:Open;;", QrPayloads.buildWifi("Open", "", "wpa2"))
        assertEquals("WIFI:T:nopass;S:Open;;", QrPayloads.buildWifi("Open", "ignored", "nopass"))
    }

    @Test
    fun cameraWifi_alwaysWpaForWpa2AndWpa3_soIosCameraJoins() {
        for (sec in listOf("wpa2", "wpa3", "wpa3t", "sae", "SAE", "WPA3", "wpa", "unknown")) {
            assertEquals(
                "sec=$sec",
                "WIFI:T:WPA;S:AndroidShare_1;P:pw123456;;",
                QrPayloads.buildCameraWifi("AndroidShare_1", "pw123456", sec),
            )
        }
        assertEquals("WIFI:T:WEP;S:Old;P:12345;;", QrPayloads.buildCameraWifi("Old", "12345", "wep"))
        assertEquals("WIFI:T:nopass;S:Open;;", QrPayloads.buildCameraWifi("Open", "", "wpa3"))
        assertEquals("WIFI:T:nopass;S:Open;;", QrPayloads.buildCameraWifi("Open", "x", "open"))
        // Still parses back as a WPA network with the same credentials.
        assertEquals(
            QrPayload.Wifi("热点;1", "p:w", "wpa2"),
            QrPayloads.parse(QrPayloads.buildCameraWifi("热点;1", "p:w", "wpa3")),
        )
    }

    @Test
    fun hotspotUri_keepsExactSec() {
        for (sec in listOf("wpa2", "wpa3t", "wpa3")) {
            val h = QrPayload.Hotspot("S", "pw123456", sec, "192.168.43.1", 8080, null, null, "0123456789abcdef0123456789abcdef")
            assertEquals(sec, (QrPayloads.parse(QrPayloads.buildHotspotUri(h)) as QrPayload.Hotspot).sec)
        }
    }

    @Test
    fun wifiBuildParseRoundTrip() {
        val ssids = listOf("Plain", "我的热点", "semi;colon,comma:colon\"quote\\back", " spaced ")
        for (s in ssids) {
            for (sec in listOf("wpa2", "wpa3", "wep")) {
                val pw = "p;w:\\\"x,1"
                val p = QrPayloads.parse(QrPayloads.buildWifi(s, pw, sec)) as QrPayload.Wifi
                assertEquals(QrPayload.Wifi(s, pw, sec, false), p)
            }
            assertEquals(QrPayload.Wifi(s, "", "nopass"), QrPayloads.parse(QrPayloads.buildWifi(s, "", "nopass")))
        }
    }

    @Test
    fun wifiParsingVariants() {
        assertEquals(
            QrPayload.Wifi("Home", "secret99", "wpa2", hidden = true),
            QrPayloads.parse("wifi:S:Home;H:true;P:secret99;T:WPA2;;"),
        )
        assertEquals(QrPayload.Wifi("Home", "secret99", "wpa3"), QrPayloads.parse("WIFI:P:secret99;S:Home;T:SAE;;"))
        assertEquals(QrPayload.Wifi("Home", "12345", "wep"), QrPayloads.parse("WIFI:T:WEP;S:Home;P:12345;;"))
        assertEquals(QrPayload.Wifi("Cafe", "", "nopass"), QrPayloads.parse("WIFI:T:nopass;S:Cafe;;"))
        assertEquals(QrPayload.Wifi("Cafe", "", "nopass"), QrPayloads.parse("WIFI:S:Cafe;;"))
        assertEquals(QrPayload.Wifi("Cafe", "pw", "wpa2"), QrPayloads.parse("WIFI:S:Cafe;P:pw;;"))
        assertEquals(QrPayload.Wifi("Quoted", "pw", "wpa2"), QrPayloads.parse("WIFI:T:WPA;S:\"Quoted\";P:pw;;"))
        assertEquals(QrPayload.Wifi("Home", "pw", "wpa2", false), QrPayloads.parse("  WIFI:T:WPA;S:Home;P:pw;H:false;;\n"))
        assertNull(QrPayloads.parse("WIFI:T:WPA;P:nossid;;"))
        assertNull(QrPayloads.parse("WIFI:"))
    }

    @Test
    fun specHotspotVectorParsesAndRebuildsIdentically() {
        val h = QrPayloads.parse(hotspotVector) as QrPayload.Hotspot
        assertEquals(
            QrPayload.Hotspot(
                ssid = "AndroidShare_7781",
                password = "k2Jd9xQ1",
                sec = "wpa2",
                ip = "192.168.197.1",
                port = 8080,
                id = "ab12cd",
                name = "Pixel 8",
                tok = "0123456789abcdef0123456789abcdef",
            ),
            h,
        )
        assertEquals(hotspotVector, QrPayloads.buildHotspotUri(h))
    }

    @Test
    fun hotspotUriEncodingAndOptionalFields() {
        val h = QrPayload.Hotspot("热点 & co", "p&w=1+2%", "wpa3t", "10.0.0.1", 8081, null, null, null)
        val uri = QrPayloads.buildHotspotUri(h)
        assertEquals(
            "minifile://hotspot?v=1&s=%E7%83%AD%E7%82%B9%20%26%20co&p=p%26w%3D1%2B2%25&sec=wpa3t&ip=10.0.0.1&port=8081",
            uri,
        )
        assertEquals(h, QrPayloads.parse(uri))
        assertNull(QrPayloads.parse("minifile://hotspot?v=1&s=x&ip=10.0.0.1"))
        assertNull(QrPayloads.parse("minifile://hotspot?v=1&s=x&ip=10.0.0.1&port=70000"))
        assertNull(QrPayloads.parse("minifile://hotspot?v=1&ip=10.0.0.1&port=80"))
        assertNull(QrPayloads.parse("minifile://hotspot?v=1&s=%ZZ&ip=10.0.0.1&port=80"))
    }

    @Test
    fun peerUri() {
        val p = QrPayload.Peer("172.20.10.1", 8080, "ab12cd", "iPhone 15")
        val uri = QrPayloads.buildPeerUri(p)
        assertEquals("minifile://peer?ip=172.20.10.1&port=8080&id=ab12cd&n=iPhone%2015", uri)
        assertEquals(p, QrPayloads.parse(uri))
        assertEquals(QrPayload.Peer("172.20.10.1", 9000, null, null), QrPayloads.parse("MINIFILE://peer?ip=172.20.10.1&port=9000"))
        assertEquals("minifile://peer?ip=1.2.3.4&port=1", QrPayloads.buildPeerUri(QrPayload.Peer("1.2.3.4", 1, null, null)))
        assertNull(QrPayloads.parse("minifile://peer?port=8080"))
        assertNull(QrPayloads.parse("minifile://other?ip=1.2.3.4&port=8080"))
    }

    @Test
    fun httpUrls() {
        assertEquals(QrPayload.Http("192.168.1.20", 8080, false), QrPayloads.parse("http://192.168.1.20:8080/"))
        assertEquals(QrPayload.Http("192.168.1.20", 80, false), QrPayloads.parse("HTTP://192.168.1.20/files?x=1"))
        assertEquals(QrPayload.Http("host.local", 443, true), QrPayloads.parse("https://host.local"))
        assertEquals(QrPayload.Http("fe80::1", 8080, false), QrPayloads.parse("http://[fe80::1]:8080/"))
        assertNull(QrPayloads.parse("http://:8080/"))
        assertNull(QrPayloads.parse("http://host:notaport/"))
        assertNull(QrPayloads.parse("ftp://host/"))
        assertNull(QrPayloads.parse("hello world"))
        assertNull(QrPayloads.parse(""))
    }
}
