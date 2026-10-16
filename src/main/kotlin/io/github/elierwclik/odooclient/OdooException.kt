/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full notices, conditions and disclaimer: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import java.io.IOException

open class OdooException(message: String, cause: Throwable? = null) : IOException(message, cause)
open class AuthenticationException(message: String = "Odoo authentication failed") : OdooException(message)
class TwoFactorRequiredException : AuthenticationException("A two-factor authentication code is required")
class InvalidTwoFactorCodeException : AuthenticationException("The two-factor authentication code was rejected")
class HttpException(val statusCode: Int) : OdooException("Odoo returned HTTP $statusCode")
class RemoteException(val code: Int?, val error: Any?) : OdooException("Odoo remote call failed (code=$code)")
class ProtocolException(message: String, cause: Throwable? = null) : OdooException(message, cause)
