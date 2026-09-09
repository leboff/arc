package com.daydreamvr.upnp.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.system.measureTimeMillis

/** ARCHITECTURE.md §16.1 / PLAN.md §3.3 — the hardened parser closes XXE and
 * billion-laughs from a hostile or compromised MediaServer. */
class SafeXmlTest {

    @Test
    fun documentWithDoctype_isRejected() {
        val doc = "<?xml version=\"1.0\"?><!DOCTYPE root [ <!ELEMENT root ANY> ]><root/>"
        val failure = runCatching { SafeXml.parse(doc) }.exceptionOrNull()
        assertThat(failure).isNotNull()
    }

    @Test
    fun xxePayloadReferencingLocalFile_doesNotResolve() {
        val xxe = "<?xml version=\"1.0\"?>" +
            "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>" +
            "<foo>&xxe;</foo>"
        val result = runCatching { SafeXml.parse(xxe) }
        assertThat(result.isFailure).isTrue()
        // Even if some parser tolerated it, the entity must never have expanded.
        result.getOrNull()?.let { doc ->
            assertThat(doc.documentElement.textContent).doesNotContain("root:")
        }
    }

    @Test
    fun billionLaughsPayload_failsFast() {
        val lols = buildString {
            append("<?xml version=\"1.0\"?>")
            append("<!DOCTYPE lolz [ <!ENTITY lol \"lol\">")
            for (i in 1..9) {
                append("<!ENTITY lol$i \"")
                repeat(10) { append(if (i == 1) "&lol;" else "&lol${i - 1};") }
                append("\">")
            }
            append(" ]><lolz>&lol9;</lolz>")
        }
        val elapsed = measureTimeMillis {
            assertThat(runCatching { SafeXml.parse(lols) }.isFailure).isTrue()
        }
        assertThat(elapsed).isLessThan(500L)
    }

    @Test
    fun ordinaryDocumentStillParses() {
        val doc = SafeXml.parse("<a><b>hi</b><b>there</b></a>")
        assertThat(doc.documentElement.getElementsByTagName("b").length).isEqualTo(2)
    }
}
