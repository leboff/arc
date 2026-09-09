package com.daydreamvr.upnp.ssdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SsdpMessageTest {

    @Test
    fun mSearchRequest_matchesGoldenBytesExactly_withTrailingBlankLine() {
        val golden = listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "MAN: \"ssdp:discover\"",
            "MX: 3",
            "ST: urn:schemas-upnp-org:device:MediaServer:1",
            "USER-AGENT: Android/16 UPnP/1.0 DaydreamVrPlayer/1.0",
        ).joinToString("\r\n", postfix = "\r\n\r\n")

        val bytes = SsdpMessage.buildSearchRequest("urn:schemas-upnp-org:device:MediaServer:1", mxSeconds = 3)

        assertThat(String(bytes, Charsets.US_ASCII)).isEqualTo(golden)
        assertThat(String(bytes, Charsets.US_ASCII)).endsWith("\r\n\r\n")
    }

    @Test
    fun parsesSearchResponse_headersUsnLocationAndMaxAge() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.50.10:49152/description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "USN: uuid:4d696e69-444c-164f-9d41-b827eb1a2b3c::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "SERVER: Linux/6.1 UPnP/1.0 Gerbera/1.12.1\r\n\r\n"

        val message = SsdpMessage.parse(raw, "192.168.50.10")

        assertThat(message).isNotNull()
        assertThat(message!!.kind).isEqualTo(SsdpMessage.Kind.SEARCH_RESPONSE)
        assertThat(message.location).isEqualTo("http://192.168.50.10:49152/description.xml")
        assertThat(message.udn).isEqualTo("uuid:4d696e69-444c-164f-9d41-b827eb1a2b3c")
        assertThat(message.maxAgeSeconds).isEqualTo(1800L)
        assertThat(message.server).contains("Gerbera")
        assertThat(message.remoteAddress).isEqualTo("192.168.50.10")
    }

    @Test
    fun parsesNotifyAliveAndByebye() {
        val alive = SsdpMessage.parse(
            "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNTS: ssdp:alive\r\n" +
                "NT: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                "USN: uuid:aaa::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                "LOCATION: http://192.168.50.11:8200/rootDesc.xml\r\n\r\n",
        )
        assertThat(alive!!.kind).isEqualTo(SsdpMessage.Kind.NOTIFY_ALIVE)
        assertThat(alive.target).isEqualTo("urn:schemas-upnp-org:device:MediaServer:1")

        val bye = SsdpMessage.parse(
            "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNTS: ssdp:byebye\r\n" +
                "USN: uuid:aaa::urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n",
        )
        assertThat(bye!!.kind).isEqualTo(SsdpMessage.Kind.NOTIFY_BYEBYE)
        assertThat(bye.isByeBye).isTrue()
        assertThat(bye.udn).isEqualTo("uuid:aaa")
    }

    @Test
    fun ignoresOurOwnMSearchAndGarbage() {
        assertThat(SsdpMessage.parse(String(SsdpMessage.buildSearchRequest("ssdp:all"), Charsets.US_ASCII)))
            .isNull()
        assertThat(SsdpMessage.parse("")).isNull()
        assertThat(SsdpMessage.parse("not an ssdp datagram at all")).isNull()
    }

    @Test
    fun headerLookupIsCaseInsensitive_viaUpperCasedKeys() {
        val message = SsdpMessage.parse(
            "HTTP/1.1 200 OK\r\nlocation: http://x/d.xml\r\nUsn: uuid:z::x\r\n\r\n",
        )
        assertThat(message!!.location).isEqualTo("http://x/d.xml")
        assertThat(message.udn).isEqualTo("uuid:z")
    }
}
