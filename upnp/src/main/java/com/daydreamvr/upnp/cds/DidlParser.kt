package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.DidlObject
import com.daydreamvr.upnp.model.Resource
import com.daydreamvr.upnp.model.Size
import com.daydreamvr.upnp.model.UpnpError
import com.daydreamvr.upnp.net.SafeXml
import com.daydreamvr.upnp.net.SafeXml.attr
import com.daydreamvr.upnp.net.SafeXml.childElements
import com.daydreamvr.upnp.net.SafeXml.childText
import com.daydreamvr.upnp.net.SafeXml.childrenNamed
import org.w3c.dom.Element
import java.net.URI

/**
 * DIDL-Lite parsing — the highest-value test target in the project
 * (ARCHITECTURE.md §9.5, PLAN.md §3.3). Handles:
 *
 *  - default-namespace and prefixed (`dc:`, `upnp:`) documents;
 *  - entity-escaped titles (`&amp;`, `&#39;`) — the DOM decodes one level for us;
 *  - `<Result>` payloads that arrive either XML-escaped or inside a CDATA section;
 *  - multiple `<res>` per item, and items with none;
 *  - `duration` in `H:MM:SS`, `HH:MM:SS`, `H:MM:SS.mmm` forms;
 *  - `resolution` ("1920x1080") and `size`.
 */
object DidlParser {

    fun parse(didlXml: String): Result<List<DidlObject>> = runCatching {
        val payload = normalizePayload(didlXml)
        val doc = try {
            SafeXml.parse(payload)
        } catch (e: Exception) {
            throw UpnpError.MalformedResponse("DIDL-Lite is not valid XML: ${e.message}", e)
        }
        val root = doc.documentElement
            ?: throw UpnpError.MalformedResponse("empty DIDL-Lite document")

        root.childElements().mapNotNull { el ->
            when (SafeXml.localName(el)) {
                "container" -> parseContainer(el)
                "item" -> parseItem(el)
                else -> null
            }
        }
    }

    /**
     * Normalises the text content of a SOAP `<Result>` element into raw DIDL XML.
     * If it already starts with `<` (CDATA form, or a DOM that decoded the escape
     * for us) it is returned trimmed; otherwise it is unescaped exactly once, so
     * a double-escaped entity (`&amp;amp;`) survives as `&amp;`.
     */
    fun unescapeResultPayload(soapResultText: String): String {
        val trimmed = soapResultText.trim()
        if (trimmed.startsWith("<")) return trimmed
        return unescapeXmlOnce(trimmed)
    }

    private fun normalizePayload(didlXml: String): String {
        val trimmed = didlXml.trim()
        return if (trimmed.startsWith("<")) trimmed else unescapeXmlOnce(trimmed)
    }

    private fun parseContainer(el: Element): DidlContainer = DidlContainer(
        id = el.attr("id").orEmpty(),
        parentId = el.attr("parentID").orEmpty(),
        title = el.childText("title").orEmpty(),
        upnpClass = el.childText("class").orEmpty(),
        childCount = el.attr("childCount")?.toIntOrNull(),
    )

    private fun parseItem(el: Element): DidlItem {
        val resources = el.childrenNamed("res").mapNotNull(::parseResource)
        return DidlItem(
            id = el.attr("id").orEmpty(),
            parentId = el.attr("parentID").orEmpty(),
            title = el.childText("title").orEmpty(),
            upnpClass = el.childText("class").orEmpty(),
            resources = resources,
            durationMs = resources.firstNotNullOfOrNull { it.durationMs }
                ?: parseDuration(el.childText("duration")),
            mimeType = resources.firstNotNullOfOrNull { it.mimeType },
            resolution = resources.firstNotNullOfOrNull { it.resolution },
            albumArtUri = el.childText("albumArtURI")?.let(::safeUri),
            sizeBytes = resources.firstNotNullOfOrNull { it.sizeBytes }
                ?: el.childText("size")?.toLongOrNull(),
        )
    }

    private fun parseResource(res: Element): Resource? {
        val uri = safeUri(res.textContent?.trim().orEmpty()) ?: return null
        return Resource(
            uri = uri,
            protocolInfo = res.attr("protocolInfo").orEmpty(),
            sizeBytes = res.attr("size")?.toLongOrNull(),
            durationMs = parseDuration(res.attr("duration")),
            resolution = parseResolution(res.attr("resolution")),
            bitrate = res.attr("bitrate")?.toIntOrNull(),
        )
    }

    /** Parses `[H]H:MM:SS[.fff]` into milliseconds. Returns null for anything else. */
    fun parseDuration(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secondsField = parts[2]
        val seconds = secondsField.substringBefore('.').toLongOrNull() ?: return null
        val fractionMs = secondsField.substringAfter('.', "").let { frac ->
            if (frac.isEmpty()) 0L else ("0.$frac".toDoubleOrNull()?.times(1000)?.toLong() ?: 0L)
        }
        if (minutes >= 60 || seconds >= 60) return null
        return ((hours * 3600 + minutes * 60 + seconds) * 1000) + fractionMs
    }

    fun parseResolution(raw: String?): Size? {
        val text = raw?.trim()?.lowercase() ?: return null
        val parts = text.split('x')
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return Size(w, h)
    }

    private fun safeUri(raw: String): URI? {
        if (raw.isEmpty()) return null
        runCatching { return URI(raw) }
        return runCatching { URI(raw.replace(" ", "%20")) }.getOrNull()
    }

    /**
     * Replaces the five predefined entities plus numeric character references in a
     * single left-to-right pass, so nothing is unescaped twice.
     */
    private fun unescapeXmlOnce(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                out.append(c)
                i++
                continue
            }
            when (val entity = text.substring(i + 1, semi)) {
                "amp" -> out.append('&')
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> {
                    val codePoint = when {
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.drop(2).toIntOrNull(16)
                        entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                        else -> null
                    }
                    if (codePoint != null) out.appendCodePoint(codePoint) else out.append(text, i, semi + 1)
                }
            }
            i = semi + 1
        }
        return out.toString()
    }
}
