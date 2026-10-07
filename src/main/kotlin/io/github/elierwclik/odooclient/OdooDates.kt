/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full notices, conditions and disclaimer: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Odoo datetime strings represent UTC, without an explicit zone. Null/empty means absent. */
object OdooDates {
    private val datetime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    fun strToDate(value: String?): LocalDate? = value?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    fun strToTime(value: String?): LocalTime? = value?.takeIf { it.isNotEmpty() }?.let {
        LocalTime.parse(it.substringBefore('.'), time)
    }
    fun strToDatetime(value: String?): LocalDateTime? = value?.takeIf { it.isNotEmpty() }?.let {
        LocalDateTime.parse(it.substringBefore('.'), datetime)
    }
    fun dateToStr(value: LocalDate?): String? = value?.format(DateTimeFormatter.ISO_LOCAL_DATE)
    fun timeToStr(value: LocalTime?): String? = value?.format(time)
    fun datetimeToStr(value: LocalDateTime?): String? = value?.format(datetime)
    fun toInstant(value: String): Instant = requireNotNull(strToDatetime(value)).toInstant(ZoneOffset.UTC)
    fun fromInstant(value: Instant): String = datetime.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC))
}
