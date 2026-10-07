/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full notices, conditions and disclaimer: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import com.google.gson.JsonArray
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness

internal object JsonCodec {
    private val parser = GsonBuilder().setStrictness(Strictness.STRICT).create()
    fun encode(value: Any?): String = toJson(value).toString()

    fun decode(text: String): Any? = try {
        fromJson(parser.fromJson(text, JsonElement::class.java)
            ?: throw ProtocolException("Empty JSON response from Odoo"))
    } catch (exception: RuntimeException) {
        throw ProtocolException("Invalid JSON response from Odoo", exception)
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> {
            require(value !is Double || value.isFinite()) { "Non-finite numbers cannot be sent" }
            require(value !is Float || value.isFinite()) { "Non-finite numbers cannot be sent" }
            JsonPrimitive(value)
        }
        is Map<*, *> -> JsonObject().apply {
            value.forEach { (key, item) ->
                require(key is String) { "Odoo dictionaries require string keys" }
                add(key, toJson(item))
            }
        }
        is Iterable<*> -> JsonArray().apply { value.forEach { add(toJson(it)) } }
        is Array<*> -> toJson(value.toList())
        is IntArray -> toJson(value.toList())
        is LongArray -> toJson(value.toList())
        else -> throw IllegalArgumentException("Unsupported Odoo value type: ${value.javaClass.name}")
    }

    private fun fromJson(value: JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject.entrySet().associate { it.key to fromJson(it.value) }
        value.isJsonArray -> value.asJsonArray.map(::fromJson)
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isString -> value.asString
        else -> value.asString.toLongOrNull()?.let {
            if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it
        } ?: value.asBigDecimal
    }
}
