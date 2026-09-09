package com.daydreamvr.upnp.net

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hardened XML parsing for everything `:upnp` reads off the network. A hostile or
 * compromised MediaServer is the most realistic attack surface in the whole app
 * (ARCHITECTURE.md §16.1), so:
 *
 *  - DOCTYPE declarations are rejected outright (kills billion-laughs immediately);
 *  - external general / parameter entities are disabled (kills XXE / SSRF);
 *  - `FEATURE_SECURE_PROCESSING` is on;
 *  - the entity resolver is neutered as a belt-and-braces measure.
 *
 * The plan's original "XmlPullParser" note is met with a JDK DOM parser: it keeps
 * the module dependency-light and Android-free, and DIDL-Lite (namespaced,
 * repeated `<res>`, mixed default/prefixed namespaces) is far easier over a DOM.
 */
object SafeXml {

    /** Thrown when the hardened parser rejects a document (DOCTYPE, entity, etc.). */
    class RejectedXmlException(message: String, cause: Throwable?) : Exception(message, cause)

    private val factory: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = true
            isValidating = false
        }

    /**
     * Parses [xml] into a DOM [Document]. Throws [RejectedXmlException] for a
     * document the hardening rejects (DOCTYPE / entity payloads) and
     * [org.xml.sax.SAXException] for ordinary malformed XML.
     */
    @Synchronized
    fun parse(xml: String): Document {
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return try {
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: SAXParseException) {
            val message = e.message ?: ""
            if (message.contains("DOCTYPE", ignoreCase = true) ||
                message.contains("entity", ignoreCase = true)
            ) {
                throw RejectedXmlException("XML rejected by hardened parser: $message", e)
            }
            throw e
        }
    }

    // ---- Tiny namespace-agnostic DOM helpers ------------------------------------

    /** Local name with any prefix stripped (works whether or not the doc declares namespaces). */
    fun localName(node: Node): String =
        (node.localName ?: node.nodeName).substringAfterLast(':')

    fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element) out += n
        }
        return out
    }

    /** Direct child elements whose local name equals [name]. */
    fun Element.childrenNamed(name: String): List<Element> =
        childElements().filter { localName(it) == name }

    fun Element.firstChildNamed(name: String): Element? =
        childElements().firstOrNull { localName(it) == name }

    /** Trimmed text of the first direct child element named [name], or null. */
    fun Element.childText(name: String): String? =
        firstChildNamed(name)?.textContent?.trim()?.ifEmpty { null }

    /** All descendants (any depth) with the given local name. */
    fun Element.descendantsNamed(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            for (c in e.childElements()) {
                if (localName(c) == name) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }

    fun Element.attr(name: String): String? =
        getAttribute(name).ifEmpty { null }
            ?: attributes.let { atts ->
                (0 until atts.length)
                    .map { atts.item(it) }
                    .firstOrNull { localName(it) == name }
                    ?.nodeValue
            }
}
