/* Odoo Client Library Kotlin adaptation. SPDX-License-Identifier: BSD-3-Clause.
 * Original copyrights and full license are in META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

class CodecTest {
    @Test fun jsonRoundTripsOdooTypesAndKeepsIntegerPrecision() {
        val value = mapOf("text" to "España <&> \"quoted\"", "false" to false, "null" to null,
            "list" to listOf(7, 9_007_199_254_740_993L, mapOf("active" to true)))
        assertEquals(value, JsonCodec.decode(JsonCodec.encode(value)))
    }

    @Test fun jsonRejectsUnsupportedAndInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { JsonCodec.encode(mapOf(1 to "invalid")) }
        assertThrows(IllegalArgumentException::class.java) { JsonCodec.encode(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { JsonCodec.encode(java.util.Date()) }
        assertThrows(ProtocolException::class.java) { JsonCodec.decode("{bad json}") }
        assertThrows(ProtocolException::class.java) { JsonCodec.decode("") }
        assertThrows(ProtocolException::class.java) { JsonCodec.decode("{} {}")} 
    }

    @Test fun xmlEncodesEscapedStringsAndNestedCommands() {
        val body = XmlRpcCodec.encode("execute_kw", listOf(mapOf("name" to "A<&>\"", "commands" to listOf(listOf(6, 0, listOf(1, 2))))))
        assertTrue(body.contains("A&lt;&amp;&gt;"))
        assertTrue(body.contains("<methodName>execute_kw</methodName>"))
        assertTrue(body.contains("<name>commands</name>"))
    }

    @Test fun xmlArraysProduceExactlyOneValuePerParam() {
        for (array in listOf(arrayOf(1, 2), intArrayOf(1, 2), longArrayOf(1, 2))) {
            val body = XmlRpcCodec.encode("test", listOf(array))
            assertFalse(body.contains("<value />"))
            assertFalse(body.contains("<value></value>"))
            assertTrue(body.contains("<param><value><array>"))
        }
    }

    @Test fun xmlDecodesStructArraysNilAndBase64() {
        val body = result("""<struct>
            <member><name>id</name><value><int>3</int></value></member>
            <member><name>name</name><value><string> A&amp;B </string></value></member>
            <member><name>items</name><value><array><data>
            <value><boolean>0</boolean></value><value><nil/></value>
            <value><i8>9007199254740993</i8></value><value><double>1.25</double></value>
            </data></array></value></member>
            </struct>""")
        assertEquals(mapOf("id" to 3, "name" to " A&B ", "items" to listOf(false, null, 9_007_199_254_740_993L, 1.25)), XmlRpcCodec.decode(body))
        assertArrayEquals(byteArrayOf(1, 2, 3), XmlRpcCodec.decode(result("<base64>AQID</base64>")) as ByteArray)
        assertEquals(" plain ", XmlRpcCodec.decode(result(" plain ")))
    }

    @Test fun xmlFaultRetainsCodeAndMessage() {
        val body = """<methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>2</int></value></member>
            <member><name>faultString</name><value><string>Access denied</string></value></member>
            </struct></value></fault></methodResponse>"""
        val error = assertThrows(RemoteException::class.java) { XmlRpcCodec.decode(body) }
        assertEquals(2, error.code)
        assertEquals("Access denied", (error.error as Map<*, *>)["faultString"])
    }

    @Test fun xmlRejectsDtdAndMalformedResponses() {
        val body = "<!DOCTYPE methodResponse [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" + result("<string>&xxe;</string>")
        assertThrows(ProtocolException::class.java) { XmlRpcCodec.decode(body) }
        assertThrows(ProtocolException::class.java) { XmlRpcCodec.decode("<html/>") }
        assertThrows(ProtocolException::class.java) { XmlRpcCodec.decode(result("<boolean>2</boolean>")) }
        assertThrows(ProtocolException::class.java) { XmlRpcCodec.decode(result("<unknown/>")) }
        assertThrows(ProtocolException::class.java) { XmlRpcCodec.decode("<methodResponse>") }
    }

    @Test fun datesUseUtcAndMatchOriginalFractionHandling() {
        val instant = Instant.parse("2026-10-07T14:30:15Z")
        assertEquals(instant, OdooDates.toInstant("2026-10-07 14:30:15.987"))
        assertEquals("2026-10-07 14:30:15", OdooDates.fromInstant(instant))
        assertEquals(LocalDate.of(2026, 10, 7), OdooDates.strToDate("2026-10-07"))
        assertEquals(LocalTime.of(14, 30, 15), OdooDates.strToTime("14:30:15.123"))
        assertEquals("14:30:00", OdooDates.timeToStr(LocalTime.of(14, 30)))
        assertNull(OdooDates.strToDate(""))
        assertNull(OdooDates.datetimeToStr(null))
        assertThrows(DateTimeParseException::class.java) { OdooDates.strToDate("2026-02-30") }
    }

    private fun result(value: String): String = "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"
}
