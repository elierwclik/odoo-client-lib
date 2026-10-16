/* Example for the Kotlin adaptation of Odoo's odoo-client-lib.
 * Original library copyrights: Stephane Wirtel; Nicolas Vanhoren (2011);
 * OpenERP s.a. (2011); Nicolas Seinlet (2018); Odoo s.a. (2018).
 * SPDX-License-Identifier: BSD-3-Clause. Full license: LICENSE-ODOO.txt at the project root.
 */
package example.odoo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.elierwclik.odooclient.AuthenticationException
import io.github.elierwclik.odooclient.InvalidTwoFactorCodeException
import io.github.elierwclik.odooclient.OdooClient
import io.github.elierwclik.odooclient.OdooException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.IOException
import java.net.URISyntaxException

/** Minimal Android example: authenticate with password/TOTP or an RPC API key, then display ten contacts. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        // Keep the form clear of status/navigation bars on devices with edge-to-edge rendering.
        @Suppress("DEPRECATION")
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            layout.setPadding(padding + insets.systemWindowInsetLeft, padding + insets.systemWindowInsetTop,
                padding + insets.systemWindowInsetRight, padding + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        fun field(hintId: Int, type: Int): EditText = EditText(this).apply {
            hint = getString(hintId)
            inputType = type
            isSingleLine = true
            // Credentials and server details stay in memory; no saved instance state or storage.
            isSaveEnabled = false
            layout.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val url = field(R.string.server_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val database = field(R.string.database, InputType.TYPE_CLASS_TEXT)
        val login = field(R.string.login, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field(R.string.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val apiKey = CheckBox(this).apply { setText(R.string.use_api_key); layout.addView(this) }
        val load = Button(this).apply { setText(R.string.load_contacts); layout.addView(this) }
        val result = TextView(this).apply {
            setText(R.string.instructions)
            setTextIsSelectable(true)
            setPadding(0, padding, 0, padding)
            layout.addView(this)
        }

        load.setOnClickListener {
            val serverUrl = url.text.toString().trim()
            val db = database.text.toString().trim()
            val user = login.text.toString().trim()
            val secret = password.text.toString()
            val useApiKey = apiKey.isChecked
            if (listOf(serverUrl, db, user, secret).any { it.isBlank() }) {
                result.setText(R.string.missing_fields)
                return@setOnClickListener
            }

            load.isEnabled = false
            result.setText(R.string.loading)
            scope.launch {
                try {
                    val client = if (useApiKey) {
                        OdooClient.rpc(serverUrl, db, user, secret).also { it.checkLogin() }
                    } else {
                        OdooClient.authenticate(
                            baseUrl = serverUrl,
                            database = db,
                            login = user,
                            password = secret,
                            onTwoFactorCode = { requestTwoFactorCode() },
                        )
                    }
                    val uid = client.userId
                    val contacts = client.getModel("res.partner").searchRead(
                        fields = listOf("name", "email"),
                        limit = 10,
                        order = "name asc",
                    )
                    result.text = if (contacts.isEmpty()) getString(R.string.no_contacts) else {
                        getString(R.string.connected_as, uid) + "\n\n" + contacts.joinToString("\n\n") { record ->
                            // An empty email field in Odoo may contain false.
                            listOfNotNull(record["name"] as? String, record["email"] as? String).joinToString("\n")
                        }
                    }
                } catch (error: CancellationException) {
                    if (!isFinishing && !isDestroyed) result.setText(R.string.login_cancelled)
                    throw error
                } catch (_: InvalidTwoFactorCodeException) {
                    result.setText(R.string.invalid_two_factor_code)
                } catch (_: AuthenticationException) {
                    result.setText(R.string.invalid_login)
                } catch (_: OdooException) {
                    result.setText(R.string.odoo_error)
                } catch (_: IOException) {
                    result.setText(R.string.network_error)
                } catch (_: IllegalArgumentException) {
                    result.setText(R.string.invalid_url)
                } catch (_: URISyntaxException) {
                    result.setText(R.string.invalid_url)
                } finally {
                    load.isEnabled = true
                }
            }
        }
    }

    /** Suspends login while the user reads the code from their authenticator app. */
    private suspend fun requestTwoFactorCode(): String = suspendCancellableCoroutine { continuation ->
        val code = EditText(this).apply {
            hint = getString(R.string.two_factor_code)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.two_factor_title)
            .setMessage(R.string.two_factor_instructions)
            .setView(code)
            .setPositiveButton(R.string.verify_code, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> continuation.cancel() }
            .setOnCancelListener { continuation.cancel() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = code.text.toString().trim()
                if (!value.matches(Regex("[0-9]{6}"))) {
                    code.error = getString(R.string.two_factor_code_format)
                } else if (continuation.isActive) {
                    continuation.resume(value)
                    code.text.clear()
                    dialog.dismiss()
                }
            }
        }
        continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
        if (continuation.isActive) dialog.show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
