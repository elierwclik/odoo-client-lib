/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full notices, conditions and disclaimer: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64

internal object XmlRpcCodec {
    fun encode(method: String, args: List<Any?>): String {
        val output = StringWriter()
        val xml = XmlPullParserFactory.newInstance().newSerializer()
        xml.setOutput(output)
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "methodCall")
        xml.element("methodName", method)
        xml.startTag(null, "params")
        args.forEach {
            xml.startTag(null, "param")
            writeValue(xml, it)
            xml.endTag(null, "param")
        }
        xml.endTag(null, "params")
        xml.endTag(null, "methodCall")
        xml.endDocument()
        return output.toString()
    }

    private fun XmlSerializer.element(name: String, value: String) {
        startTag(null, name); text(value); endTag(null, name)
    }

    private fun writeValue(xml: XmlSerializer, value: Any?) {
        when (value) {
            is Array<*> -> return writeValue(xml, value.toList())
            is IntArray -> return writeValue(xml, value.toList())
            is LongArray -> return writeValue(xml, value.toList())
        }
        xml.startTag(null, "value")
        when (value) {
            null -> { xml.startTag(null, "nil"); xml.endTag(null, "nil") }
            is Boolean -> xml.element("boolean", if (value) "1" else "0")
            is Byte, is Short, is Int -> xml.element("int", value.toString())
            is Long -> xml.element("i8", value.toString())
            is Number -> {
                require(value.toDouble().isFinite()) { "Non-finite numbers cannot be sent" }
                xml.element("double", value.toString())
            }
            is String -> xml.element("string", value)
            is ByteArray -> xml.element("base64", Base64.getEncoder().encodeToString(value))
            is Map<*, *> -> {
                xml.startTag(null, "struct")
                value.forEach { (key, item) ->
                    require(key is String) { "Odoo dictionaries require string keys" }
                    xml.startTag(null, "member")
                    xml.element("name", key)
                    writeValue(xml, item)
                    xml.endTag(null, "member")
                }
                xml.endTag(null, "struct")
            }
            is Iterable<*> -> {
                xml.startTag(null, "array"); xml.startTag(null, "data")
                value.forEach { writeValue(xml, it) }
                xml.endTag(null, "data"); xml.endTag(null, "array")
            }
            else -> throw IllegalArgumentException("Unsupported XML-RPC value type: ${value.javaClass.name}")
        }
        xml.endTag(null, "value")
    }

    private data class Node(val name: String, val text: String, val children: List<Node>) {
        fun child(name: String): Node = children.singleOrNull { it.name == name }
            ?: throw ProtocolException("Malformed XML-RPC $name element")
    }

    fun decode(body: String): Any? = try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(body))
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            rejectDtd(event)
            event = parser.nextToken()
        }
        if (event != XmlPullParser.START_TAG) throw ProtocolException("Empty XML-RPC response")
        val root = readNode(parser, 0)
        while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
            rejectDtd(parser.eventType)
            if (parser.eventType == XmlPullParser.START_TAG || !parser.text.orEmpty().isBlank()) {
                throw ProtocolException("Trailing XML-RPC content")
            }
        }
        if (root.name != "methodResponse") throw ProtocolException("Invalid XML-RPC response root")
        root.children.singleOrNull { it.name == "fault" }?.let {
            val fault = readValue(it.child("value")) as? Map<*, *>
                ?: throw ProtocolException("Invalid XML-RPC fault")
            throw RemoteException((fault["faultCode"] as? Number)?.toInt(), fault)
        }
        readValue(root.child("params").child("param").child("value"))
    } catch (exception: OdooException) {
        throw exception
    } catch (exception: Exception) {
        throw ProtocolException("Invalid XML-RPC response from Odoo", exception)
    }

    private fun rejectDtd(event: Int) {
        if (event == XmlPullParser.DOCDECL) throw ProtocolException("XML document types are not allowed")
    }

    private fun readNode(parser: XmlPullParser, depth: Int): Node {
        if (depth > 64) throw ProtocolException("XML-RPC nesting limit exceeded")
        val name = parser.name
        val text = StringBuilder()
        val children = mutableListOf<Node>()
        while (true) {
            when (val event = parser.nextToken()) {
                XmlPullParser.START_TAG -> children.add(readNode(parser, depth + 1))
                XmlPullParser.END_TAG -> return Node(name, text.toString(), children)
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE -> text.append(parser.text)
                XmlPullParser.ENTITY_REF -> text.append(parser.text ?: throw ProtocolException("Unknown XML entity"))
                XmlPullParser.END_DOCUMENT -> throw ProtocolException("Incomplete XML-RPC response")
                else -> rejectDtd(event)
            }
        }
    }

    private fun readValue(value: Node): Any? {
        if (value.name != "value") throw ProtocolException("Expected XML-RPC value")
        if (value.children.isEmpty()) return value.text
        val type = value.children.singleOrNull() ?: throw ProtocolException("Ambiguous XML-RPC value")
        return when (type.name) {
            "string", "dateTime.iso8601" -> type.text
            "int", "i4" -> type.text.trim().toInt()
            "i8" -> type.text.trim().toLong()
            "double" -> type.text.trim().toDouble().also {
                if (!it.isFinite()) throw ProtocolException("Non-finite XML-RPC number")
            }
            "boolean" -> when (type.text.trim()) {
                "1" -> true
                "0" -> false
                else -> throw ProtocolException("Invalid XML-RPC boolean")
            }
            "nil" -> null
            "base64" -> Base64.getMimeDecoder().decode(type.text)
            "array" -> type.child("data").children.map(::readValue)
            "struct" -> type.children.associate {
                if (it.name != "member") throw ProtocolException("Invalid XML-RPC struct")
                it.child("name").text to readValue(it.child("value"))
            }
            else -> throw ProtocolException("Unsupported XML-RPC response type: ${type.name}")
        }
    }
}
