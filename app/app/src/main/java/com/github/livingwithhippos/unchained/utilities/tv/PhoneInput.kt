package com.github.livingwithhippos.unchained.utilities.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.security.SecureRandom
import com.github.livingwithhippos.unchained.R
import com.github.livingwithhippos.unchained.authentication.LocalTokenServer
import com.github.livingwithhippos.unchained.utilities.extension.isTv
import com.github.livingwithhippos.unchained.utilities.extension.loadQrCode
import com.github.livingwithhippos.unchained.utilities.extension.showToast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

/**
 * Reusable, transport only wrapper around a [LocalTokenServer]. Typing with a TV remote is painful,
 * so on Android TV any field can offer to receive its value from another device on the same local
 * network (e.g. the user's phone). This controller owns the server lifecycle and delivers its
 * callbacks on the main thread; the actual PIN, QR code and address are shown by
 * [showPhoneInputDialog]. The security properties (PIN, one shot, bounded lifetime, LAN only bind,
 * security headers) all live in [LocalTokenServer] and are untouched here.
 *
 * @param pages localized texts used to build the served web pages
 * @param isValueValid decides whether a submitted value is acceptable
 * @param onValueReceived called on the main thread with the submitted value
 * @param onStopped called on the main thread when the server stops itself (timeout, too many wrong
 *   PINs or a value received). Not called by [stop].
 */
/**
 * Remembers which phones have already passed the PIN during this app session so they do not have to
 * type it again on every phone input (e.g. two searches in a row). A phone is identified by an
 * unguessable token minted after its first valid submission and stored here; the server sets that
 * token as a session cookie on the phone. The trust is dropped after [SESSION_TIMEOUT_MS] of
 * inactivity, and each use slides the expiry forward. Tokens are cryptographically strong and never
 * leave the local network, and every server still enforces its own one shot acceptance, short
 * lifetime and LAN only bind, so this does not weaken those protections. The PIN itself stays a
 * fresh random value per server.
 */
object PhoneInputSession {
    private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L
    private const val TOKEN_BYTES = 32
    // token -> expiry timestamp of every phone trusted this session
    private val trustedTokens = mutableMapOf<String, Long>()
    private val secureRandom = SecureRandom()

    /** True if [token] is a known, not yet expired trusted phone; refreshes its expiry */
    @Synchronized
    fun isTrusted(token: String?): Boolean {
        if (token == null) return false
        val now = System.currentTimeMillis()
        pruneExpired(now)
        val expiry = trustedTokens[token] ?: return false
        if (now > expiry) {
            trustedTokens.remove(token)
            return false
        }
        trustedTokens[token] = now + SESSION_TIMEOUT_MS
        return true
    }

    /** Mint, remember and return a new trust token for a phone that just passed the PIN */
    @Synchronized
    fun grantTrust(): String {
        val now = System.currentTimeMillis()
        pruneExpired(now)
        val token = generateToken()
        trustedTokens[token] = now + SESSION_TIMEOUT_MS
        return token
    }

    private fun pruneExpired(now: Long) {
        val iterator = trustedTokens.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < now) iterator.remove()
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class PhoneInputController(
    private val pages: LocalTokenServer.Pages,
    private val isValueValid: (String) -> Boolean,
    private val onValueReceived: (String) -> Unit,
    private val onStopped: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: LocalTokenServer? = null

    /** The reachable http address of the running server, or null while it is not running */
    var address: String? = null
        private set

    /** The PIN the phone must submit, available once [start] succeeded */
    val pin: String?
        get() = server?.pin

    val isRunning: Boolean
        get() = server != null

    /**
     * Start the server. Returns the reachable address, or null if no local network address or free
     * port was found. Callbacks are marshalled onto the main thread.
     */
    fun start(): String? {
        val newServer =
            LocalTokenServer(
                pages = pages,
                isValueValid = isValueValid,
                onValueReceived = { value -> mainHandler.post { onValueReceived(value) } },
                onStopped = {
                    mainHandler.post {
                        server = null
                        onStopped()
                    }
                },
                // a phone that already passed the PIN this session skips it on later uses
                isTrusted = { token -> PhoneInputSession.isTrusted(token) },
                grantTrust = { PhoneInputSession.grantTrust() },
            )
        val started = newServer.start()
        if (started != null) {
            server = newServer
            address = started
        }
        return started
    }

    /** Stop the server, if running. Idempotent and safe to call more than once. */
    fun stop() {
        server?.stop()
        server = null
    }
}

/**
 * Show a dialog with the address, PIN and a QR code of a freshly started [PhoneInputController], so
 * the user can send [fieldLabel]'s value from a phone instead of typing it with the remote. The
 * value fills the field through [onValueReceived]; the dialog dismisses itself when a value arrives
 * or when the server stops. Dismissing the dialog without waiting for a submission stops the server
 * too. TV only affordances call this; the caller is expected to have gated on [Context.isTv].
 */
fun showPhoneInputDialog(
    context: Context,
    scope: CoroutineScope,
    fieldLabel: String,
    linkUrl: String? = null,
    linkLabel: String? = null,
    errorMessage: String = context.getString(R.string.phone_input_invalid),
    isValueValid: (String) -> Boolean = { it.isNotBlank() },
    onValueReceived: (String) -> Unit,
) {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_phone_input, null)
    val addressView = view.findViewById<TextView>(R.id.tvPhoneInputAddress)
    val pinView = view.findViewById<TextView>(R.id.tvPhoneInputPin)
    val qrView = view.findViewById<ImageView>(R.id.ivPhoneInputQrCode)

    var dialog: AlertDialog? = null

    val controller =
        PhoneInputController(
            pages =
                LocalTokenServer.Pages(
                    title = context.getString(R.string.app_name),
                    fieldLabel = fieldLabel,
                    pinLabel = context.getString(R.string.token_web_pin_label),
                    submitLabel = context.getString(R.string.send),
                    successMessage = context.getString(R.string.value_web_received),
                    errorMessage = errorMessage,
                    wrongPinMessage = context.getString(R.string.token_web_wrong_pin),
                    linkUrl = linkUrl,
                    linkLabel = linkLabel,
                ),
            isValueValid = isValueValid,
            onValueReceived = { value ->
                onValueReceived(value)
                dialog?.dismiss()
            },
            onStopped = {
                // the server stopped itself: close the panel if it is still shown
                dialog?.dismiss()
            },
        )

    val address = controller.start()
    if (address == null) {
        context.showToast(R.string.phone_input_unavailable)
        Timber.w("The phone input server could not be started")
        return
    }

    addressView.text = context.getString(R.string.send_value_from_phone_format, address)
    pinView.text = context.getString(R.string.token_server_pin_format, controller.pin)

    dialog =
        MaterialAlertDialogBuilder(context)
            .setTitle(fieldLabel)
            .setView(view)
            .setNegativeButton(R.string.close) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                // dismissing without waiting for a submission must also stop the server
                controller.stop()
            }
            .create()
    dialog.show()
    // load the QR only once the dialog view is attached, otherwise loadQrCode skips it
    qrView.loadQrCode(address, scope)
}

/**
 * On Android TV, add a QR code start icon to this field that opens [showPhoneInputDialog] so its
 * value can be sent from a phone. Does nothing on phones, so it is always safe to call. By default
 * the received value fills this field's [TextInputLayout.getEditText] and the served form label is
 * this field's hint.
 */
fun TextInputLayout.enablePhoneInput(
    scope: CoroutineScope,
    fieldLabel: String = hint?.toString() ?: context.getString(R.string.app_name),
    linkUrl: String? = null,
    linkLabel: String? = null,
    errorMessage: String = context.getString(R.string.phone_input_invalid),
    isValueValid: (String) -> Boolean = { it.isNotBlank() },
    onValueReceived: (String) -> Unit = { value -> editText?.setText(value) },
) {
    if (!context.isTv()) return
    setStartIconDrawable(R.drawable.icon_qr_code)
    startIconContentDescription = context.getString(R.string.type_from_phone)
    isStartIconVisible = true
    setStartIconOnClickListener {
        showPhoneInputDialog(
            context = context,
            scope = scope,
            fieldLabel = fieldLabel,
            linkUrl = linkUrl,
            linkLabel = linkLabel,
            errorMessage = errorMessage,
            isValueValid = isValueValid,
            onValueReceived = onValueReceived,
        )
    }
}
