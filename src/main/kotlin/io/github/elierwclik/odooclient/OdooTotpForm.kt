/* Kotlin adaptation of Odoo's odoo-client-lib. SPDX-License-Identifier: BSD-3-Clause.
 * Original copyright holders and full license: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

/** Reads only the standard Odoo TOTP form's inputs; no HTML is executed. */
internal object OdooTotpForm {
    private val inputs = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attributes = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun csrfToken(html: String): String? {
        val fields = inputs.findAll(html).map { input ->
            attributes.findAll(input.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
        }.toList()
        if (fields.none { it["name"] == "totp_token" }) return null
        return fields.firstOrNull { it["name"] == "csrf_token" }?.get("value")
            ?.replace("&quot;", "\"")?.replace("&#39;", "'")?.replace("&apos;", "'")
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() }
    }
}
