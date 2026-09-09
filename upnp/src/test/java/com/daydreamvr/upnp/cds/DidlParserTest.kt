package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.DidlObject
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PLAN.md §3.3: for **each** of the five vendor fixtures — container/item counts,
 * entity-decoded titles, `upnp:class` filtering, every observed `duration` form,
 * `resolution`, `size`, missing-`res` items, multiple `<res>` per item, and
 * prefixed vs default-namespace documents.
 */
class DidlParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/didl/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun parse(name: String): List<DidlObject> =
        DidlParser.parse(fixture(name)).getOrThrow()

    private fun List<DidlObject>.containers() = filterIsInstance<DidlContainer>()
    private fun List<DidlObject>.items() = filterIsInstance<DidlItem>()
    private fun List<DidlObject>.item(title: String) = items().first { it.title == title }

    @Test
    fun allFiveFixturesParseWithoutError() {
        for (name in FIXTURES) {
            assertThat(DidlParser.parse(fixture(name)).isSuccess).isTrue()
        }
    }

    @Test
    fun gerbera_countsClassesEntitiesDurationsAndMultiRes() {
        val objects = parse("gerbera-sample.xml")

        assertThat(objects.containers()).hasSize(2)
        assertThat(objects.items()).hasSize(3)
        // Only object.item.videoItem* is playable — the imageItem is listed but not.
        assertThat(objects.items().filter { it.isPlayableVideo }).hasSize(2)

        assertThat(objects.containers().first { it.id == "64" }.title).isEqualTo("Films & Shorts")
        assertThat(objects.containers().first { it.id == "64" }.childCount).isEqualTo(12)

        val arrival = objects.item("Arrival (2016) & Friends") // &amp; decoded exactly once
        assertThat(arrival.upnpClass).isEqualTo("object.item.videoItem.movie")
        assertThat(arrival.durationMs).isEqualTo((1 * 3600 + 56 * 60 + 3) * 1000L) // 1:56:03.000
        assertThat(arrival.resolution?.width).isEqualTo(1920)
        assertThat(arrival.resolution?.height).isEqualTo(1080)
        assertThat(arrival.sizeBytes).isEqualTo(8_589_934_592L)
        assertThat(arrival.mimeType).isEqualTo("video/x-matroska")
        assertThat(arrival.resources).hasSize(2) // full file + JPEG_TN thumbnail
        assertThat(arrival.resources[1].isThumbnail).isTrue()

        assertThat(objects.item("Short Clip").durationMs).isEqualTo((45 * 60 + 12) * 1000L) // 0:45:12
    }

    @Test
    fun minidlna_filtersNonVideoAndParsesHmsAndUhdResolution() {
        val objects = parse("minidlna-sample.xml")

        assertThat(objects.containers()).hasSize(1)
        assertThat(objects.items()).hasSize(2)

        val beach = objects.item("Beach 360 VR")
        assertThat(beach.isPlayableVideo).isTrue()
        assertThat(beach.durationMs).isEqualTo((1 * 3600 + 2 * 60 + 3) * 1000L) // 01:02:03
        assertThat(beach.resolution).isEqualTo(com.daydreamvr.upnp.model.Size(3840, 2160))

        val song = objects.item("Rooftop Song")
        assertThat(song.isPlayableVideo).isFalse()
        assertThat(song.upnpClass).startsWith("object.item.audioItem")
    }

    @Test
    fun synology_missingResAndFractionalSecondsAndAlbumArt() {
        val objects = parse("synology-sample.xml")

        assertThat(objects.containers().single().title).isEqualTo("Movies & Shows")
        assertThat(objects.items()).hasSize(2)

        val missing = objects.item("No Media Here")
        assertThat(missing.isPlayableVideo).isTrue()
        assertThat(missing.resources).isEmpty()
        assertThat(missing.durationMs).isNull()

        val ok = objects.item("With Media & Sound")
        assertThat(ok.durationMs).isEqualTo(5_500L) // 0:00:05.500
        assertThat(ok.albumArtUri.toString()).isEqualTo("http://192.168.1.5:50002/thumb/ok.jpg")
        assertThat(ok.resolution).isEqualTo(com.daydreamvr.upnp.model.Size(1920, 800))
    }

    @Test
    fun plex_numericEntityInTitleAndTwoHourDuration() {
        val objects = parse("plex-sample.xml")

        assertThat(objects.containers()).isEmpty()
        val movie = objects.items().single()
        assertThat(movie.title).isEqualTo("It's a Wonderful Life") // &#39; decoded
        assertThat(movie.durationMs).isEqualTo((2 * 3600 + 5 * 60) * 1000L) // 2:05:00
        assertThat(movie.resolution).isEqualTo(com.daydreamvr.upnp.model.Size(720, 480))
    }

    @Test
    fun jellyfin_prefixedNamespaceDocumentAndMultiResOrdering() {
        val objects = parse("jellyfin-sample.xml")

        assertThat(objects.containers().single().title).isEqualTo("TV & Documentaries")
        val doc = objects.items().single()
        assertThat(doc.title).isEqualTo("Nature & Science '24")
        assertThat(doc.isPlayableVideo).isTrue()
        assertThat(doc.resources).hasSize(2)
        assertThat(doc.resources[0].resolution).isEqualTo(com.daydreamvr.upnp.model.Size(1920, 1080))
        assertThat(doc.resources[1].resolution).isEqualTo(com.daydreamvr.upnp.model.Size(854, 480))
        assertThat(doc.resources[1].uri.toString()).contains("quality=low")
        assertThat(doc.resources.all { it.durationMs == (52 * 60 + 19) * 1000L }).isTrue() // 0:52:19.000
    }

    @Test
    fun escapedResultAndCdataFormsParseIdentically() {
        val didl = fixture("plex-sample.xml").trim()
        val escaped = "<Result>" + escapeXml(didl) + "</Result>"
        val cdata = "<Result><![CDATA[$didl]]></Result>"

        val fromEscaped = DidlParser.parse(extractResultText(escaped)).getOrThrow()
        val fromCdata = DidlParser.parse(extractResultText(cdata)).getOrThrow()

        assertThat(fromEscaped).isEqualTo(fromCdata)
        assertThat(fromEscaped.filterIsInstance<DidlItem>().single().title)
            .isEqualTo("It's a Wonderful Life")
    }

    @Test
    fun doubleEscapedEntitySurvivesExactlyOneUnescape() {
        // "&amp;amp;" is the escaped form of the text "&amp;".
        val payload = "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;" +
            "&lt;item id=\"x\" parentID=\"0\"&gt;" +
            "&lt;dc:title&gt;Tom &amp;amp; Jerry&lt;/dc:title&gt;" +
            "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
            "&lt;/item&gt;&lt;/DIDL-Lite&gt;"

        val once = DidlParser.unescapeResultPayload(payload)
        assertThat(once).contains("Tom &amp; Jerry") // one level only, not "Tom & Jerry"

        val title = DidlParser.parse(once).getOrThrow().filterIsInstance<DidlItem>().single().title
        assertThat(title).isEqualTo("Tom & Jerry")
    }

    // Mirrors how ContentDirectoryClientImpl pulls <Result> out of the SOAP envelope:
    // the DOM decodes one level of escaping for us.
    private fun extractResultText(resultXml: String): String {
        val doc = com.daydreamvr.upnp.net.SafeXml.parse(resultXml)
        return DidlParser.unescapeResultPayload(doc.documentElement.textContent)
    }

    private fun escapeXml(s: String) = SoapEnvelope.escapeXml(s)

    private companion object {
        val FIXTURES = listOf(
            "gerbera-sample.xml",
            "minidlna-sample.xml",
            "synology-sample.xml",
            "plex-sample.xml",
            "jellyfin-sample.xml",
        )
    }
}
