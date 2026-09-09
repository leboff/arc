package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.PageRequest
import com.daydreamvr.upnp.model.UpnpError
import com.daydreamvr.upnp.net.HttpTransport
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.URI

class ContentDirectoryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ContentDirectoryClientImpl

    private val serviceType = "urn:schemas-upnp-org:service:ContentDirectory:1"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ContentDirectoryClientImpl(HttpTransport.okHttpDefault())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun mediaServer() = MediaServer(
        udn = "uuid:test",
        friendlyName = "Test",
        manufacturer = null,
        modelName = null,
        descriptionUrl = URI(server.url("/desc.xml").toString()),
        controlUrl = URI(server.url("/ctl/ContentDirectory").toString()),
        contentDirectoryServiceType = serviceType,
    )

    private fun didl(vararg items: String) = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        items.forEach { append(it) }
        append("</DIDL-Lite>")
    }

    private fun videoItem(id: String, title: String) =
        "<item id=\"$id\" parentID=\"0\"><dc:title>$title</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/mp4:*\">http://h/$id.mp4</res></item>"

    private fun browseEnvelope(resultDidl: String, numberReturned: Int, totalMatches: Int) =
        "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Body><u:BrowseResponse xmlns:u=\"$serviceType\">" +
            "<Result>" + SoapEnvelope.escapeXml(resultDidl) + "</Result>" +
            "<NumberReturned>$numberReturned</NumberReturned>" +
            "<TotalMatches>$totalMatches</TotalMatches>" +
            "<UpdateID>1</UpdateID>" +
            "</u:BrowseResponse></s:Body></s:Envelope>"

    @Test
    fun happyPathBrowse_parsesItemsAndAssertsRequestShape() = runTest {
        server.enqueue(
            MockResponse().setBody(
                browseEnvelope(didl(videoItem("a", "Alpha"), videoItem("b", "Beta")), 2, 2),
            ),
        )

        val result = client.browse(mediaServer(), "0", PageRequest(0, 200)).getOrThrow()

        assertThat(result.items.map { it.title }).containsExactly("Alpha", "Beta")
        assertThat(result.totalMatches).isEqualTo(2)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.getHeader("SOAPACTION")).isEqualTo("\"$serviceType#Browse\"")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\r\n") // CRLF envelope
        assertThat(body).contains("<ObjectID>0</ObjectID>")
        assertThat(body).contains("<RequestedCount>200</RequestedCount>")
    }

    @Test
    fun pagination_terminatesOnZeroNumberReturned_evenWithTotalMatchesZero() = runTest {
        repeat(3) { page ->
            server.enqueue(
                MockResponse().setBody(
                    browseEnvelope(didl(videoItem("p$page", "Item $page")), numberReturned = 1, totalMatches = 0),
                ),
            )
        }
        server.enqueue(MockResponse().setBody(browseEnvelope(didl(), numberReturned = 0, totalMatches = 0)))

        val all = client.browseAll(mediaServer(), "0", pageSize = 1).getOrThrow()

        assertThat(all.items).hasSize(3)
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun upnpFault701_mapsToNoSuchObject() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                    "<s:Body><s:Fault><detail><UPnPError " +
                    "xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
                    "<errorCode>701</errorCode><errorDescription>No such object</errorDescription>" +
                    "</UPnPError></detail></s:Fault></s:Body></s:Envelope>",
            ),
        )

        val error = client.browse(mediaServer(), "999").exceptionOrNull()
        assertThat(error).isInstanceOf(UpnpError.NoSuchObject::class.java)
    }

    @Test
    fun malformedXml_returnsFailureAndDoesNotThrow() = runTest {
        server.enqueue(MockResponse().setBody("<s:Envelope><s:Body><u:BrowseResponse><Result>&lt;DIDL"))
        val result = client.browse(mediaServer(), "0")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun oversizeBody_isReportedAsResponseTooLarge() = runTest {
        val huge = "x".repeat((HttpTransport.MAX_RESULT_BYTES + 1024).toInt())
        server.enqueue(MockResponse().setBody(huge))
        val error = client.browse(mediaServer(), "0").exceptionOrNull()
        assertThat(error).isInstanceOf(UpnpError.ResponseTooLarge::class.java)
    }
}
