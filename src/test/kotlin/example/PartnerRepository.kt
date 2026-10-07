/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full license and attribution: LICENSE and NOTICE.txt at the repository root.
 */
package example

import io.github.elierwclik.odooclient.OdooClient

/** Create a client with user-supplied credentials, then inject it into your repository. */
class PartnerRepository(private val client: OdooClient) {
    data class Partner(val id: Int, val name: String, val email: String?)

    suspend fun findCompanies(query: String): List<Partner> = client.getModel("res.partner")
        .searchRead(
            domain = listOf(listOf("is_company", "=", true), listOf("name", "ilike", query)),
            fields = listOf("name", "email"),
            limit = 20,
            order = "name asc",
        )
        .map { row ->
            Partner(
                id = (row.getValue("id") as Number).toInt(),
                name = row["name"] as String,
                email = row["email"] as? String, // Odoo can return false for an empty field.
            )
        }
}
