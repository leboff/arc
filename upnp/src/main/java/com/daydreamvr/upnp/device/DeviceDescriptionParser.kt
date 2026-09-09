package com.daydreamvr.upnp.device

import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.UpnpError
import com.daydreamvr.upnp.net.SafeXml
import com.daydreamvr.upnp.net.SafeXml.childText
import com.daydreamvr.upnp.net.SafeXml.childrenNamed
import com.daydreamvr.upnp.net.SafeXml.descendantsNamed
import com.daydreamvr.upnp.net.SafeXml.firstChildNamed
import org.w3c.dom.Element
import java.net.URI

/**
 * Parses a UPnP device description document into a [MediaServer].
 *
 * Control-URL resolution is the classic "works with Gerbera, 404s on Synology"
 * bug (ARCHITECTURE.md §9.3). Documented precedence, pinned by
 * `DeviceDescriptionParserTest`:
 *
 *  1. an absolute `<controlURL>` is used verbatim;
 *  2. otherwise, if `<URLBase>` is present **and absolute**, resolve against it
 *     (even when it points at a different host/port than the LOCATION);
 *  3. otherwise resolve against the LOCATION URL.
 */
object DeviceDescriptionParser {

    private val CONTENT_DIRECTORY = Regex("""urn:schemas-upnp-org:service:ContentDirectory:\d+""")
    private val MEDIA_SERVER = Regex("""urn:schemas-upnp-org:device:MediaServer:\d+""")

    fun parse(xml: String, locationUrl: URI): Result<MediaServer> = runCatching {
        val doc = try {
            SafeXml.parse(xml)
        } catch (e: Exception) {
            throw UpnpError.MalformedResponse("device description is not valid XML: ${e.message}", e)
        }
        val root = doc.documentElement
            ?: throw UpnpError.MalformedResponse("empty device description")

        val urlBase = root.firstChildNamed("URLBase")?.textContent?.trim()?.ifEmpty { null }
            ?: run {
                // Some servers put <URLBase> under <root> late; descendantsNamed covers odd nesting.
                root.descendantsNamed("URLBase").firstOrNull()?.textContent?.trim()?.ifEmpty { null }
            }
        val base: URI? = urlBase?.let { runCatching { URI(it) }.getOrNull() }

        val device = findMediaServerDevice(root)
            ?: findDeviceWithContentDirectory(root)
            ?: throw UpnpError.NotAMediaServer("no MediaServer device / ContentDirectory service in description")

        val service = contentDirectoryServiceOf(device)
            ?: throw UpnpError.NotAMediaServer("device has no ContentDirectory service")

        val controlUrlRaw = service.childText("controlURL")
            ?: throw UpnpError.MalformedResponse("ContentDirectory service has no controlURL")
        val serviceType = service.childText("serviceType")
            ?: "urn:schemas-upnp-org:service:ContentDirectory:1"

        val udn = device.childText("UDN")?.let { normalizeUdn(it) }
            ?: throw UpnpError.MalformedResponse("device has no UDN")

        MediaServer(
            udn = udn,
            friendlyName = device.childText("friendlyName") ?: udn,
            manufacturer = device.childText("manufacturer"),
            modelName = device.childText("modelName"),
            descriptionUrl = locationUrl,
            controlUrl = resolveUrl(controlUrlRaw, base, locationUrl),
            contentDirectoryServiceType = serviceType,
            iconUrl = firstIconUrl(device, base, locationUrl),
            lastSeenEpochMs = 0L,
        )
    }

    /** Public so `MediaServerDirectory` can resolve `eventSubURL` / icons the same way. */
    fun resolveUrl(reference: String, urlBase: URI?, location: URI): URI {
        val ref = reference.trim()
        val refUri = runCatching { URI(ref) }.getOrNull()
        if (refUri != null && refUri.isAbsolute) return refUri
        if (urlBase != null && urlBase.isAbsolute) return urlBase.resolve(ref)
        return location.resolve(ref)
    }

    private fun normalizeUdn(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("uuid:", ignoreCase = true)) t else "uuid:$t"
    }

    private fun allDevices(root: Element): List<Element> = root.descendantsNamed("device")

    private fun findMediaServerDevice(root: Element): Element? =
        allDevices(root).firstOrNull { device ->
            device.childText("deviceType")?.let { MEDIA_SERVER.containsMatchIn(it) } == true
        }

    private fun findDeviceWithContentDirectory(root: Element): Element? =
        allDevices(root).firstOrNull { contentDirectoryServiceOf(it) != null }

    private fun contentDirectoryServiceOf(device: Element): Element? {
        val serviceList = device.firstChildNamed("serviceList") ?: return null
        return serviceList.childrenNamed("service").firstOrNull { service ->
            service.childText("serviceType")?.let { CONTENT_DIRECTORY.containsMatchIn(it) } == true
        }
    }

    private fun firstIconUrl(device: Element, base: URI?, location: URI): URI? {
        val iconList = device.firstChildNamed("iconList") ?: return null
        val icon = iconList.childrenNamed("icon").maxByOrNull { icon ->
            (icon.childText("width")?.toIntOrNull() ?: 0)
        } ?: return null
        val url = icon.childText("url") ?: return null
        return runCatching { resolveUrl(url, base, location) }.getOrNull()
    }
}
