/* Kotlin adaptation of Odoo's odoo-client-lib. SPDX-License-Identifier: BSD-3-Clause.
 * Original copyright holders and full license: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

/** Shared user defaults and app overrides; each request receives its own snapshot. */
internal class OdooContext(initial: Map<String, Any?> = emptyMap()) {
    private var defaults: Map<String, Any?>? = null
    private var overrides = copy(initial)

    @Synchronized fun server(): Map<String, Any?>? = defaults?.let(::copy)
    @Synchronized fun setServer(values: Map<String, Any?>) { defaults = copy(values) }
    @Synchronized fun update(values: Map<String, Any?>) { overrides = overrides + copy(values) }
    @Synchronized fun merged(values: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        copy(defaults.orEmpty() + overrides + values)

    private fun copy(values: Map<String, Any?>): Map<String, Any?> = values.mapValues { (_, value) -> copyValue(value) }
    private fun copyValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { it.key to copyValue(it.value) }
        is List<*> -> value.map(::copyValue)
        else -> value
    }
}
