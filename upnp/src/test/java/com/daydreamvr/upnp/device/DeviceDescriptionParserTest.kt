package com.daydreamvr.upnp.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.URI

/**
 * PLAN.md §3.3 — control-URL resolution across the four real cases. Documented
 * precedence: an absolute `controlURL` wins; otherwise `URLBase` wins when present
 * and absolute (even on a different port); otherwise resolve against LOCATION.
 */
class DeviceDescriptionParserTest {

    private fun description(urlBase: String?, controlUrl: String): String = buildString {
        append("<?xml version=\"1.0\"?>")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion>")
        if (urlBase != null) append("<URLBase>").append(urlBase).append("</URLBase>")
        append("<device>")
        append("<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>")
        append("<friendlyName>Reference Server</friendlyName>")
        append("<manufacturer>Test</manufacturer>")
        append("<modelName>RefBox</modelName>")
        append("<UDN>uuid:11111111-2222-3333-4444-555555555555</UDN>")
        append("<serviceList><service>")
        append("<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>")
        append("<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>")
        append("<controlURL>").append(controlUrl).append("</controlURL>")
        append("<eventSubURL>/evt/cd</eventSubURL>")
        append("<SCPDURL>/cd.xml</SCPDURL>")
        append("</service></serviceList>")
        append("</device></root>")
    }

    private fun parseControlUrl(location: String, urlBase: String?, controlUrl: String): URI =
        DeviceDescriptionParser.parse(description(urlBase, controlUrl), URI(location)).getOrThrow().controlUrl

    @Test
    fun absoluteControlUrl_isUsedVerbatim() {
        val url = parseControlUrl(
            location = "http://192.168.50.10:49152/description.xml",
            urlBase = null,
            controlUrl = "http://192.168.50.10:49152/upnp/control/ContentDirectory",
        )
        assertThat(url).isEqualTo(URI("http://192.168.50.10:49152/upnp/control/ContentDirectory"))
    }

    @Test
    fun relativeControlUrl_resolvedAgainstUrlBaseWhenPresent() {
        val url = parseControlUrl(
            location = "http://192.168.50.10:49152/description.xml",
            urlBase = "http://192.168.50.10:49152/",
            controlUrl = "cd/control",
        )
        assertThat(url).isEqualTo(URI("http://192.168.50.10:49152/cd/control"))
    }

    @Test
    fun relativeControlUrl_resolvedAgainstLocationWhenNoUrlBase() {
        val url = parseControlUrl(
            location = "http://192.168.1.42:2870/dev/desc.xml",
            urlBase = null,
            controlUrl = "/ctl/ContentDir",
        )
        assertThat(url).isEqualTo(URI("http://192.168.1.42:2870/ctl/ContentDir"))
    }

    @Test
    fun urlBaseOnDifferentPort_winsOverLocation_theSynologyCase() {
        val url = parseControlUrl(
            location = "http://192.168.50.5:52323/ContentDirectory.xml",
            urlBase = "http://192.168.50.5:5000/",
            controlUrl = "/upnp/control/ContentDirectory",
        )
        assertThat(url).isEqualTo(URI("http://192.168.50.5:5000/upnp/control/ContentDirectory"))
    }

    @Test
    fun extractsFriendlyNameManufacturerAndNormalizesUdn() {
        val server = DeviceDescriptionParser.parse(
            description(urlBase = null, controlUrl = "/ctl"),
            URI("http://host:8200/rootDesc.xml"),
        ).getOrThrow()

        assertThat(server.friendlyName).isEqualTo("Reference Server")
        assertThat(server.manufacturer).isEqualTo("Test")
        assertThat(server.udn).isEqualTo("uuid:11111111-2222-3333-4444-555555555555")
        assertThat(server.contentDirectoryServiceType)
            .isEqualTo("urn:schemas-upnp-org:service:ContentDirectory:1")
    }

    @Test
    fun nonMediaServerXml_isRejected() {
        val printer = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>" +
            "<deviceType>urn:schemas-upnp-org:device:Printer:1</deviceType>" +
            "<UDN>uuid:printer</UDN><friendlyName>Office Printer</friendlyName>" +
            "<serviceList></serviceList></device></root>"
        assertThat(DeviceDescriptionParser.parse(printer, URI("http://host/desc.xml")).isFailure).isTrue()
    }
}
