package io.github.cedar17.zjuconnect

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class ConnectionPhase {
    DISCONNECTED,
    RESTORING_SESSION,
    FETCHING_AUTH_METHODS,
    AUTHENTICATING,
    AWAITING_CREDENTIALS,
    AWAITING_PHONE,
    AWAITING_SMS,
    AWAITING_TOKEN,
    AWAITING_CAPTCHA,
    PREPARING_VPN_PERMISSION,
    ESTABLISHING_VPN,
    RECOVERING_VPN,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

data class CaptchaPoint(
    val x: Int,
    val y: Int,
)

data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val internalCode: String = "",
    val diagnosticStage: String = "",
    val diagnosticCause: String = "",
    val diagnosticDurationMillis: Long = 0,
    val rememberedUsername: String = "",
    val username: String = "",
    val password: String = "",
    val phone: String = "",
    val smsCode: String = "",
    val token: String = "",
    val challengeKind: String = "",
    val phoneNumbers: List<String> = emptyList(),
    val captchaImage: ByteArray? = null,
    val captchaWidth: Int = 0,
    val captchaHeight: Int = 0,
    val captchaPoints: List<CaptchaPoint> = emptyList(),
)

sealed interface ConnectionEffect {
    val attemptId: Long

    data class RequestVpnPermission(override val attemptId: Long) : ConnectionEffect

    data class StartVpnService(override val attemptId: Long) : ConnectionEffect

    data class StopVpnService(override val attemptId: Long) : ConnectionEffect
}

internal enum class CredentialOrigin {
    USER_INPUT,
    SAVED_STORE,
}

/** Holds only the credential accepted by the current auth attempt and its source. */
internal class PendingCredential(
    val credential: StoredCredential,
    val origin: CredentialOrigin,
)

internal enum class VpnPermissionContinuation {
    PREPARE_REUSABLE_RESULT,
    START_AUTHENTICATED_VPN,
}

internal data class PendingVpnPermission(
    val attemptId: Long,
    val continuation: VpnPermissionContinuation,
)

/** Maps a tap on the displayed bitmap back to the original server image. */
object CaptchaCoordinateMapper {
    fun toImagePoint(
        tapX: Float,
        tapY: Float,
        displayedWidth: Float,
        displayedHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): CaptchaPoint? {
        if (displayedWidth <= 0f || displayedHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
            return null
        }
        if (tapX !in 0f..displayedWidth || tapY !in 0f..displayedHeight) {
            return null
        }
        return CaptchaPoint(
            x = ((tapX / displayedWidth) * imageWidth).roundToInt().coerceIn(0, imageWidth - 1),
            y = ((tapY / displayedHeight) * imageHeight).roundToInt().coerceIn(0, imageHeight - 1),
        )
    }
}

internal class ConnectionAttemptTracker {
    private var sequence = 0L

    var activeAttemptId: Long = 0L
        private set

    fun begin(): Long {
        sequence += 1
        activeAttemptId = sequence
        return activeAttemptId
    }

    fun invalidate(): Long = begin()

    fun accepts(attemptId: Long): Boolean = attemptId == activeAttemptId
}

internal fun savedCredentialMatchesAccount(credential: StoredCredential, rememberedUsername: String): Boolean =
    rememberedUsername.isBlank() || credential.username == rememberedUsername

internal fun selectAutomaticAuthMethod(methods: List<GoAuthMethod>): GoAuthMethod? {
    methods.firstOrNull {
        it.authType == "auth/psw" && it.loginDomain == "Radius"
    }?.let { return it }

    val supported = methods.filter { it.authType in SUPPORTED_AUTH_TYPES }
    return supported.singleOrNull()
}

internal class AccountStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readUsername(): String = preferences.getString(KEY_USERNAME, "").orEmpty()

    fun writeUsername(username: String): Boolean {
        if (username.isBlank()) return true
        return preferences.edit().putString(KEY_USERNAME, username).commit()
    }

    fun clear(): Boolean = preferences.edit().remove(KEY_USERNAME).commit()

    private companion object {
        const val PREFERENCES_NAME = "connection_preferences"
        const val KEY_USERNAME = "last_authenticated_username"
    }
}

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val bridge = GoCoreBridge()
    private val sessionStore = AuthSessionStore(application)
    private val credentialStore = SavedCredentialStore(application)
    private val accountStore = AccountStore(application)
    private val deviceIdentityProvider = DeviceIdentityProvider(application)
    private val rememberedUsername = accountStore.readUsername()
    private val attempts = ConnectionAttemptTracker()
    private val _state = MutableStateFlow(
        ConnectionUiState(
            rememberedUsername = rememberedUsername,
            username = rememberedUsername,
        ),
    )
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private val effectChannel = Channel<ConnectionEffect>(Channel.BUFFERED)
    val effects: Flow<ConnectionEffect> = effectChannel.receiveAsFlow()

    private var restoringStoredSession = false
    private var activeDeviceID = ""
    private var pendingCredential: PendingCredential? = null
    private var savedCredentialAttempted = false
    private var pendingVpnPermission: PendingVpnPermission? = null

    init {
        // Observing an in-process service state is local-only. Session files and
        // the Go authentication bridge are deliberately untouched until connect.
        viewModelScope.launch {
            RealVpnStateStore.state.collect(::consumeVpnState)
        }
        viewModelScope.launch {
            state
                .distinctUntilChanged { previous, current ->
                    previous.phase == current.phase &&
                        previous.internalCode == current.internalCode &&
                        previous.diagnosticStage == current.diagnosticStage &&
                        previous.diagnosticCause == current.diagnosticCause &&
                        previous.diagnosticDurationMillis == current.diagnosticDurationMillis
                }
                .collect { current ->
                    RedactedDiagnostics.recordConnectionState(
                        context = appContext,
                        phase = current.phase,
                        code = current.internalCode,
                        stage = current.diagnosticStage,
                        cause = current.diagnosticCause,
                        durationMillis = current.diagnosticDurationMillis,
                    )
                }
        }
    }

    fun onPrimaryAction() {
        val phase = _state.value.phase
        if (isVpnDisconnectablePhase(phase)) {
            cancelConnection()
            return
        }
        when (phase) {
            ConnectionPhase.DISCONNECTED -> beginConnection()
            ConnectionPhase.ERROR -> {
                if (_state.value.internalCode == "accountSwitchClearFailed") {
                    retryAccountSwitchClear()
                } else {
                    beginConnection()
                }
            }
            ConnectionPhase.AWAITING_CREDENTIALS -> submitCredentials()
            ConnectionPhase.AWAITING_PHONE -> submitPhone()
            ConnectionPhase.AWAITING_SMS -> submitSmsCode()
            ConnectionPhase.AWAITING_TOKEN -> submitToken()
            ConnectionPhase.AWAITING_CAPTCHA -> submitCaptcha()
            else -> Unit
        }
    }

    fun updateUsername(username: String) = _state.update { it.copy(username = username) }

    fun updatePassword(password: String) = _state.update { it.copy(password = password) }

    fun updatePhone(phone: String) = _state.update { it.copy(phone = phone) }

    fun updateSmsCode(code: String) = _state.update { it.copy(smsCode = code) }

    fun updateToken(token: String) = _state.update { it.copy(token = token) }

    fun addCaptchaTap(tapX: Float, tapY: Float, displayedWidth: Float, displayedHeight: Float) {
        val current = _state.value
        val point = CaptchaCoordinateMapper.toImagePoint(
            tapX = tapX,
            tapY = tapY,
            displayedWidth = displayedWidth,
            displayedHeight = displayedHeight,
            imageWidth = current.captchaWidth,
            imageHeight = current.captchaHeight,
        ) ?: return
        _state.update { it.copy(captchaPoints = it.captchaPoints + point) }
    }

    fun clearCaptchaPoints() = _state.update { it.copy(captchaPoints = emptyList()) }

    fun switchAccount() {
        if (!canSwitchAccount(_state.value)) return

        beginAccountSwitch()
    }

    private fun retryAccountSwitchClear() {
        if (!shouldRetryAccountSwitchClear(_state.value)) {
            return
        }
        beginAccountSwitch()
    }

    private fun beginAccountSwitch() {
        val attemptId = attempts.invalidate()
        pendingVpnPermission = null
        restoringStoredSession = false
        pendingCredential = null
        savedCredentialAttempted = true
        activeDeviceID = deviceIdentityProvider.read().orEmpty()
        bridge.discardPreparedRealVpn()
        bridge.cancelAuthentication()
        invalidateReusableAuthentication()
        recordAuthenticationRecovery(
            source = AuthenticationRecoverySource.PERSISTED_SESSION,
            outcome = AuthenticationRecoveryOutcome.INVALIDATED,
            cause = AuthenticationInvalidationCause.ACCOUNT_SWITCH,
        )
        _state.update(::accountSwitchPendingState)

        viewModelScope.launch {
            val cleared = clearAllAuthenticationForAccountSwitch()
            if (!attempts.accepts(attemptId)) return@launch
            if (!cleared) {
                showError("accountSwitchClearFailed")
                return@launch
            }
            if (activeDeviceID.isEmpty()) {
                showError("deviceIdentityUnavailable")
                return@launch
            }
            startFreshAuthentication(attemptId)
        }
    }

    fun cancelConnection() {
        val currentPhase = _state.value.phase
        val vpnMayBeRunning = currentPhase in setOf(
            ConnectionPhase.ESTABLISHING_VPN,
            ConnectionPhase.RECOVERING_VPN,
            ConnectionPhase.CONNECTED,
            ConnectionPhase.DISCONNECTING,
        )
        val stopAttemptId = attempts.invalidate()
        pendingVpnPermission = null
        restoringStoredSession = false
        pendingCredential = null
        bridge.discardPreparedRealVpn()

        // A normal disconnect is the boundary for the next one-tap reconnect,
        // not an account logout. Only an account switch or an explicit server
        // rejection invalidates the reusable result.
        if (currentPhase in AUTHENTICATION_PHASES) {
            bridge.cancelAuthentication()
        }

        if (vpnMayBeRunning) {
            _state.update {
                it.withoutSensitiveInputs().copy(
                    phase = ConnectionPhase.DISCONNECTING,
                    internalCode = "",
                )
            }
            effectChannel.trySend(ConnectionEffect.StopVpnService(stopAttemptId))
        } else {
            RealVpnStateStore.reset()
            _state.update {
                it.withoutSensitiveInputs().copy(
                    phase = ConnectionPhase.DISCONNECTED,
                    internalCode = "",
                )
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val pending = pendingVpnPermission ?: return
        pendingVpnPermission = null
        if (!attempts.accepts(pending.attemptId) || _state.value.phase != ConnectionPhase.PREPARING_VPN_PERMISSION) {
            return
        }
        if (!granted) {
            showError("vpnPermissionDenied")
            return
        }
        if (pending.continuation == VpnPermissionContinuation.PREPARE_REUSABLE_RESULT) {
            _state.update {
                it.copy(
                    phase = ConnectionPhase.ESTABLISHING_VPN,
                    internalCode = "",
                )
            }
            prepareReusableVpn(pending.attemptId)
            return
        }
        _state.update {
            it.copy(
                phase = ConnectionPhase.ESTABLISHING_VPN,
                internalCode = "",
            )
        }
        effectChannel.trySend(ConnectionEffect.StartVpnService(pending.attemptId))
    }

    fun canHandleEffect(effect: ConnectionEffect): Boolean {
        if (!attempts.accepts(effect.attemptId)) return false
        return when (effect) {
            is ConnectionEffect.RequestVpnPermission ->
                _state.value.phase == ConnectionPhase.PREPARING_VPN_PERMISSION
            is ConnectionEffect.StartVpnService ->
                _state.value.phase == ConnectionPhase.ESTABLISHING_VPN
            is ConnectionEffect.StopVpnService ->
                _state.value.phase == ConnectionPhase.DISCONNECTING
        }
    }

    fun onVpnServiceDispatchFailed(effect: ConnectionEffect) {
        if (!canHandleEffect(effect)) return
        if (effect is ConnectionEffect.StartVpnService) {
            bridge.discardPreparedRealVpn()
        }
        showError(
            if (effect is ConnectionEffect.StopVpnService) {
                "vpnStopDispatchFailed"
            } else {
                "vpnStartDispatchFailed"
            },
        )
    }

    private fun beginConnection() {
        val attemptId = attempts.begin()
        pendingVpnPermission = null
        restoringStoredSession = false
        pendingCredential = null
        savedCredentialAttempted = false
        RealVpnService.prepareForForegroundAuthentication()
        bridge.discardPreparedRealVpn()
        bridge.cancelAuthentication()
        activeDeviceID = deviceIdentityProvider.read().orEmpty()
        if (activeDeviceID.isEmpty()) {
            showError("deviceIdentityUnavailable")
            return
        }
        RealVpnStateStore.reset()
        _state.update {
            it.withoutSensitiveInputs().copy(
                phase = ConnectionPhase.RESTORING_SESSION,
                internalCode = "",
            )
        }
        if (bridge.hasReusableAuthenticatedResult()) {
            recordAuthenticationRecovery(
                source = AuthenticationRecoverySource.REUSABLE_RESULT,
                outcome = AuthenticationRecoveryOutcome.SELECTED,
            )
            requestVpnPermission(
                attemptId = attemptId,
                continuation = VpnPermissionContinuation.PREPARE_REUSABLE_RESULT,
            )
        } else {
            recordAuthenticationRecovery(
                source = AuthenticationRecoverySource.PERSISTED_SESSION,
                outcome = AuthenticationRecoveryOutcome.SELECTED,
            )
            restoreStoredSessionOrAuthenticate(attemptId)
        }
    }

    private fun prepareReusableVpn(attemptId: Long) {
        viewModelScope.launch {
            val config = try {
                withContext(Dispatchers.IO) { bridge.prepareRealVpn() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                GoVpnPrepared(
                    state = "error",
                    code = "vpnSetupFailed",
                    message = "Unable to prepare the reusable authenticated VPN",
                    stage = "prepare.reusable",
                    cause = "unexpected",
                )
            }

            if (!attempts.accepts(attemptId)) {
                bridge.discardPreparedRealVpn()
                return@launch
            }

            if (config.state == "prepared" && config.address.isNotBlank() && config.routes.isNotEmpty()) {
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.REUSABLE_RESULT,
                    outcome = AuthenticationRecoveryOutcome.AUTHENTICATED,
                )
                _state.update {
                    it.copy(
                        phase = ConnectionPhase.ESTABLISHING_VPN,
                        internalCode = "",
                    )
                }
                effectChannel.trySend(ConnectionEffect.StartVpnService(attemptId))
                return@launch
            }

            val code = config.code.ifBlank { "vpnSetupFailed" }
            if (
                config.state == "error" &&
                shouldFallbackFromReusableAuthentication(code, config.cause)
            ) {
                invalidateReusableAuthentication()
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.REUSABLE_RESULT,
                    outcome = AuthenticationRecoveryOutcome.REJECTED,
                    cause = AuthenticationInvalidationCause.REUSABLE_RESULT_REJECTED,
                )
                bridge.discardPreparedRealVpn()
                _state.update {
                    it.withoutSensitiveInputs().copy(
                        phase = ConnectionPhase.RESTORING_SESSION,
                        internalCode = "",
                    )
                }
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.PERSISTED_SESSION,
                    outcome = AuthenticationRecoveryOutcome.SELECTED,
                )
                restoreStoredSessionOrAuthenticate(attemptId)
                return@launch
            }

            bridge.discardPreparedRealVpn()
            showError(
                code = code,
                diagnosticStage = config.stage,
                diagnosticCause = config.cause,
            )
        }
    }

    private fun restoreStoredSessionOrAuthenticate(attemptId: Long) {
        restoringStoredSession = true
        viewModelScope.launch {
            val snapshot = try {
                withContext(Dispatchers.IO) { sessionStore.read() }
            } catch (_: InvalidStoredAuthenticationSession) {
                restoringStoredSession = false
                if (attempts.accepts(attemptId)) {
                    val cleared = clearStoredSession()
                    recordAuthenticationRecovery(
                        source = AuthenticationRecoverySource.PERSISTED_SESSION,
                        outcome = AuthenticationRecoveryOutcome.INVALIDATED,
                        cause = AuthenticationInvalidationCause.INVALID_STORED_SESSION,
                    )
                    if (cleared) {
                        startFreshAuthentication(attemptId)
                    } else {
                        showError("sessionStoreUnavailable")
                    }
                }
                return@launch
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                restoringStoredSession = false
                if (attempts.accepts(attemptId)) {
                    showError("sessionStoreUnavailable")
                }
                return@launch
            }

            if (!attempts.accepts(attemptId)) {
                snapshot?.fill(0)
                return@launch
            }
            if (snapshot == null) {
                restoringStoredSession = false
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.PERSISTED_SESSION,
                    outcome = AuthenticationRecoveryOutcome.UNAVAILABLE,
                )
                startFreshAuthentication(attemptId)
                return@launch
            }

            try {
                consumeAuthEvent(
                    attemptId,
                    bridge.resumeAuthentication(snapshot, activeDeviceID) { event -> consumeAuthEvent(attemptId, event) },
                )
            } finally {
                snapshot.fill(0)
            }
        }
    }

    private fun startFreshAuthentication(attemptId: Long) {
        if (!attempts.accepts(attemptId)) return
        restoringStoredSession = false
        _state.update {
            it.withoutSensitiveInputs().copy(
                phase = ConnectionPhase.FETCHING_AUTH_METHODS,
                internalCode = "",
            )
        }
        consumeAuthEvent(
            attemptId,
            bridge.startAuthentication(activeDeviceID) { event -> consumeAuthEvent(attemptId, event) },
        )
    }

    private fun submitCredentials() {
        val current = _state.value
        if (current.username.isBlank() || current.password.isBlank()) return
        val credential = StoredCredential(current.username, current.password)
        pendingCredential = PendingCredential(credential, CredentialOrigin.USER_INPUT)
        submitCredential(attempts.activeAttemptId, credential)
    }

    private fun submitCredential(attemptId: Long, credential: StoredCredential) {
        _state.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                internalCode = "",
                password = "",
            )
        }
        submit(
            attemptId,
            JSONObject()
                .put("action", "submitCredentials")
                .put("username", credential.username)
                .put("password", credential.password),
        )
    }

    private fun submitPhone() {
        val phone = _state.value.phone
        if (phone.isBlank()) return
        val attemptId = attempts.activeAttemptId
        _state.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                internalCode = "",
                phone = "",
            )
        }
        submit(attemptId, JSONObject().put("action", "submitPhone").put("phone", phone))
    }

    private fun submitSmsCode() {
        val code = _state.value.smsCode
        if (code.isBlank()) return
        val attemptId = attempts.activeAttemptId
        _state.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                internalCode = "",
                smsCode = "",
            )
        }
        submit(attemptId, JSONObject().put("action", "submitSmsCode").put("smsCode", code))
    }

    private fun submitToken() {
        val token = _state.value.token
        if (token.isBlank()) return
        val attemptId = attempts.activeAttemptId
        _state.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                internalCode = "",
                token = "",
            )
        }
        submit(attemptId, JSONObject().put("action", "submitToken").put("token", token))
    }

    private fun submitCaptcha() {
        val current = _state.value
        if (current.captchaPoints.isEmpty()) return
        val coordinates = JSONArray().also { values ->
            current.captchaPoints.forEach { point ->
                values.put(JSONArray().put(point.x).put(point.y))
            }
        }
        val attemptId = attempts.activeAttemptId
        _state.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                internalCode = "",
                captchaPoints = emptyList(),
                captchaImage = null,
            )
        }
        submit(
            attemptId,
            JSONObject()
                .put("action", "submitCaptcha")
                .put(
                    "captcha",
                    JSONObject()
                        .put("coordinates", coordinates)
                        .put("width", current.captchaWidth)
                        .put("height", current.captchaHeight)
                        .toString(),
                ),
        )
    }

    private fun submit(attemptId: Long, response: JSONObject) {
        consumeAuthEvent(
            attemptId,
            bridge.submitAuthentication(response) { event -> consumeAuthEvent(attemptId, event) },
        )
    }

    private fun consumeAuthEvent(attemptId: Long, event: GoAuthEvent) {
        viewModelScope.launch {
            if (!attempts.accepts(attemptId)) return@launch
            when (event.type) {
                "authMethodsReady" -> {
                    if (event.code == "sessionExpired") {
                        recordAuthenticationRecovery(
                            source = AuthenticationRecoverySource.PERSISTED_SESSION_STALE,
                            outcome = AuthenticationRecoveryOutcome.REAUTHENTICATING,
                        )
                    }
                    restoringStoredSession = false
                    handleAuthMethods(attemptId, event.authMethods)
                }
                "credentialsRequired" -> handleCredentialsRequired(attemptId, event)
                "phoneRequired" -> showServerChallenge {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_PHONE,
                        internalCode = "",
                    )
                }
                "smsRequired" -> showServerChallenge {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_SMS,
                        internalCode = "",
                        phoneNumbers = event.phoneNumbers.ifEmpty { it.phoneNumbers },
                        smsCode = "",
                    )
                }
                "tokenRequired" -> showServerChallenge {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_TOKEN,
                        internalCode = "",
                        token = "",
                        challengeKind = event.challengeKind,
                    )
                }
                "captchaRequired" -> showServerChallenge {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_CAPTCHA,
                        internalCode = "",
                        captchaImage = bridge.pendingCaptchaImage().takeIf(ByteArray::isNotEmpty),
                        captchaWidth = event.captchaWidth,
                        captchaHeight = event.captchaHeight,
                        captchaPoints = emptyList(),
                    )
                }
                "authenticated" -> persistAuthenticatedSession(attemptId, event)
                "sessionInvalid" -> {
                    recordAuthenticationDiagnostic(event)
                    handleStoredSessionFailure(attemptId, event)
                }
                "error" -> {
                    if (restoringStoredSession &&
                        isDefinitivelyInvalidStoredSession(event.type, event.code)
                    ) {
                        handleStoredSessionFailure(attemptId, event)
                    } else {
                        restoringStoredSession = false
                        showError(
                            code = event.code,
                            diagnosticStage = event.stage,
                            diagnosticCause = event.cause,
                            diagnosticDurationMillis = event.durationMillis,
                        )
                    }
                }
                "cancelled" -> {
                    if (_state.value.phase != ConnectionPhase.DISCONNECTED) {
                        _state.update {
                            it.withoutSensitiveInputs().copy(
                                phase = ConnectionPhase.DISCONNECTED,
                                internalCode = "",
                            )
                        }
                    }
                }
                "authenticationStarted", "retryStarted" -> {
                    if (_state.value.phase !in AUTH_INPUT_PHASES) {
                        _state.update {
                        it.copy(
                            phase = ConnectionPhase.FETCHING_AUTH_METHODS,
                        )
                        }
                    }
                }
                "sessionRestoreStarted" -> {
                    Unit
                }
                "responseAccepted" -> {
                    if (_state.value.phase !in AUTH_INPUT_PHASES) {
                        _state.update {
                            it.copy(
                                phase = ConnectionPhase.AUTHENTICATING,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleCredentialsRequired(attemptId: Long, event: GoAuthEvent) {
        if (!attempts.accepts(attemptId)) return
        if (isCredentialExplicitlyRejected(event.code)) {
            val cleared = clearSavedCredential()
            recordAuthenticationRecovery(
                source = AuthenticationRecoverySource.SAVED_CREDENTIALS,
                outcome = AuthenticationRecoveryOutcome.REJECTED,
                cause = AuthenticationInvalidationCause.CREDENTIALS_REJECTED,
            )
            if (!cleared) {
                showError("credentialStoreUnavailable")
                return
            }
            pendingCredential = null
            savedCredentialAttempted = true
            _state.update {
                it.copy(
                    phase = ConnectionPhase.AWAITING_CREDENTIALS,
                    internalCode = "",
                    password = "",
                )
            }
            return
        }

        if (!savedCredentialAttempted) {
            savedCredentialAttempted = true
            val credential = try {
                withContext(Dispatchers.IO) { credentialStore.read() }
            } catch (_: InvalidStoredCredential) {
                null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            val remembered = _state.value.rememberedUsername
            if (credential != null && savedCredentialMatchesAccount(credential, remembered)) {
                pendingCredential = PendingCredential(credential, CredentialOrigin.SAVED_STORE)
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.SAVED_CREDENTIALS,
                    outcome = AuthenticationRecoveryOutcome.SUBMITTED,
                )
                _state.update {
                    it.copy(
                        username = credential.username,
                        phase = ConnectionPhase.AUTHENTICATING,
                        internalCode = "",
                    )
                }
                submitCredential(attemptId, credential)
                return
            }
        }

        _state.update {
            it.copy(
                phase = ConnectionPhase.AWAITING_CREDENTIALS,
                internalCode = "",
                password = "",
            )
        }
    }

    private fun handleAuthMethods(attemptId: Long, methods: List<GoAuthMethod>) {
        val method = selectAutomaticAuthMethod(methods)
        if (method == null) {
            bridge.cancelAuthentication()
            showError("unsupportedAuthMethod")
            return
        }
        _state.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                internalCode = "",
            )
        }
        submit(
            attemptId,
            JSONObject()
                .put("action", "selectMethod")
                .put("authType", method.authType)
                .put("loginDomain", method.loginDomain),
        )
    }

    private suspend fun handleStoredSessionFailure(attemptId: Long, event: GoAuthEvent) {
        if (!attempts.accepts(attemptId)) return
        if (isDefinitivelyInvalidStoredSession(event.type, event.code)) {
            val cleared = clearStoredSession()
            recordAuthenticationRecovery(
                source = AuthenticationRecoverySource.PERSISTED_SESSION,
                outcome = AuthenticationRecoveryOutcome.INVALIDATED,
                cause = AuthenticationInvalidationCause.INVALID_STORED_SESSION,
            )
            restoringStoredSession = false
            savedCredentialAttempted = false
            if (cleared) {
                startFreshAuthentication(attemptId)
            } else {
                showError("sessionStoreUnavailable")
            }
            return
        }
        restoringStoredSession = false
        showError(event.code)
    }

    private suspend fun persistAuthenticatedSession(attemptId: Long, event: GoAuthEvent) {
        if (!attempts.accepts(attemptId)) return
        val credentialToSave = pendingCredential?.credential
        val snapshot = bridge.exportAuthenticatedSession()
        if (snapshot.isNotEmpty()) {
            try {
                withContext(Dispatchers.IO) { sessionStore.write(snapshot) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                snapshot.fill(0)
            }
        }
        val authenticatedUsername = event.username.ifBlank { _state.value.username }
        try {
            withContext(Dispatchers.IO) { accountStore.writeUsername(authenticatedUsername) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
        if (credentialToSave != null) {
            try {
                withContext(Dispatchers.IO) { credentialStore.write(credentialToSave) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
        if (!attempts.accepts(attemptId)) return

        val authenticatedSource = when {
            restoringStoredSession -> AuthenticationRecoverySource.PERSISTED_SESSION_AUTHENTICATED
            pendingCredential?.origin == CredentialOrigin.SAVED_STORE ->
                AuthenticationRecoverySource.SAVED_CREDENTIALS
            else -> null
        }
        authenticatedSource?.let { source ->
            recordAuthenticationRecovery(
                source = source,
                outcome = AuthenticationRecoveryOutcome.AUTHENTICATED,
            )
        }
        restoringStoredSession = false
        pendingCredential = null
        _state.update {
            it.withoutSensitiveInputs().copy(
                rememberedUsername = authenticatedUsername,
                username = authenticatedUsername,
            )
        }
        requestVpnPermission(
            attemptId = attemptId,
            continuation = VpnPermissionContinuation.START_AUTHENTICATED_VPN,
        )
    }

    private fun requestVpnPermission(
        attemptId: Long,
        continuation: VpnPermissionContinuation,
    ) {
        if (!attempts.accepts(attemptId)) return
        pendingVpnPermission = PendingVpnPermission(attemptId, continuation)
        _state.update {
            it.copy(
                phase = ConnectionPhase.PREPARING_VPN_PERMISSION,
                internalCode = "",
            )
        }
        effectChannel.trySend(ConnectionEffect.RequestVpnPermission(attemptId))
    }

    private fun consumeVpnState(vpnState: RealVpnUiState) {
        when (vpnState.state) {
            "preparing", "attaching", "starting" -> {
                if (_state.value.phase != ConnectionPhase.DISCONNECTING) {
                    _state.update {
                        it.copy(
                            phase = ConnectionPhase.ESTABLISHING_VPN,
                            internalCode = "",
                        )
                    }
                }
            }
            "recovering" -> {
                if (_state.value.phase != ConnectionPhase.DISCONNECTING) {
                    _state.update {
                        it.copy(
                            phase = ConnectionPhase.RECOVERING_VPN,
                            internalCode = "",
                        )
                    }
                }
            }
            "waitingForNetwork" -> {
                if (_state.value.phase != ConnectionPhase.DISCONNECTING) {
                    _state.update {
                        it.copy(
                            phase = ConnectionPhase.RECOVERING_VPN,
                            internalCode = "",
                        )
                    }
                }
            }
            "waitingForAuthentication" -> {
                if (_state.value.phase != ConnectionPhase.DISCONNECTING) {
                    showError("alwaysOnAuthenticationRequired")
                }
            }
            "alwaysOnDisconnectBlocked" -> {
                _state.update {
                    it.withoutSensitiveInputs().copy(
                        phase = ConnectionPhase.CONNECTED,
                        internalCode = ALWAYS_ON_DISCONNECT_BLOCKED_CODE,
                    )
                }
            }
            "active" -> _state.update {
                it.withoutSensitiveInputs().copy(
                    phase = ConnectionPhase.CONNECTED,
                    internalCode = "",
                )
            }
            "stopping" -> _state.update {
                it.copy(
                    phase = ConnectionPhase.DISCONNECTING,
                    internalCode = "",
                )
            }
            "stopped" -> {
                if (_state.value.phase in VPN_PHASES) {
                    _state.update {
                        it.withoutSensitiveInputs().copy(
                            phase = ConnectionPhase.DISCONNECTED,
                            internalCode = "",
                        )
                    }
                }
            }
            "error" -> {
                if (_state.value.phase !in AUTHENTICATION_PHASES) {
                    if (vpnState.code == "vpnSessionInvalid") {
                        invalidateReusableAuthentication()
                        recordAuthenticationRecovery(
                            source = AuthenticationRecoverySource.REUSABLE_RESULT,
                            outcome = AuthenticationRecoveryOutcome.INVALIDATED,
                            cause = AuthenticationInvalidationCause.REUSABLE_RESULT_REJECTED,
                        )
                    }
                    showError(vpnState.code)
                }
            }
        }
    }

    private fun recordAuthenticationDiagnostic(event: GoAuthEvent) {
        RedactedDiagnostics.recordConnectionState(
            context = appContext,
            phase = ConnectionPhase.ERROR,
            code = event.code,
            stage = event.stage,
            cause = event.cause,
            durationMillis = event.durationMillis,
        )
    }

    private fun showServerChallenge(transform: (ConnectionUiState) -> ConnectionUiState) {
        recordAuthenticationRecovery(
            source = AuthenticationRecoverySource.SERVER_CHALLENGE,
            outcome = AuthenticationRecoveryOutcome.WAITING_FOR_USER,
        )
        _state.update(transform)
    }

    private fun recordAuthenticationRecovery(
        source: AuthenticationRecoverySource,
        outcome: AuthenticationRecoveryOutcome,
        cause: AuthenticationInvalidationCause? = null,
    ) {
        RedactedDiagnostics.recordAuthenticationRecovery(
            context = appContext,
            source = source,
            outcome = outcome,
            cause = cause,
        )
    }

    private fun invalidateReusableAuthentication() {
        bridge.clearAuthenticatedResult()
    }

    private suspend fun clearStoredSession(): Boolean = withContext(Dispatchers.IO) {
        tryClear(sessionStore::clear)
    }

    private suspend fun clearSavedCredential(): Boolean = withContext(Dispatchers.IO) {
        tryClear(credentialStore::clear)
    }

    private suspend fun clearAllAuthenticationForAccountSwitch(): Boolean = withContext(Dispatchers.IO) {
        // Keep these as separate statements: every old-identity store must be
        // attempted even when an earlier clear fails.
        val sessionCleared = tryClear(sessionStore::clear)
        val credentialCleared = tryClear(credentialStore::clear)
        val accountCleared = tryClear(accountStore::clear)
        sessionCleared && credentialCleared && accountCleared
    }

    private fun tryClear(clear: () -> Boolean): Boolean = try {
        clear()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

    private fun showError(
        code: String,
        diagnosticStage: String = "",
        diagnosticCause: String = "",
        diagnosticDurationMillis: Long = 0,
    ) {
        pendingVpnPermission = null
        pendingCredential = null
        _state.update {
            it.withoutSensitiveInputs().copy(
                phase = ConnectionPhase.ERROR,
                internalCode = code,
                diagnosticStage = diagnosticStage,
                diagnosticCause = diagnosticCause,
                diagnosticDurationMillis = diagnosticDurationMillis,
            )
        }
    }

    override fun onCleared() {
        attempts.invalidate()
        pendingVpnPermission = null
        bridge.discardPreparedRealVpn()
        bridge.cancelAuthentication()
        pendingCredential = null
        super.onCleared()
    }
}

private fun ConnectionUiState.withoutSensitiveInputs(): ConnectionUiState = copy(
    password = "",
    phone = "",
    smsCode = "",
    token = "",
    challengeKind = "",
    phoneNumbers = emptyList(),
    captchaImage = null,
    captchaWidth = 0,
    captchaHeight = 0,
    captchaPoints = emptyList(),
    diagnosticStage = "",
    diagnosticCause = "",
    diagnosticDurationMillis = 0,
)

internal fun canSwitchAccount(state: ConnectionUiState): Boolean =
    state.phase == ConnectionPhase.DISCONNECTED && state.rememberedUsername.isNotBlank()

internal fun isVpnDisconnectablePhase(phase: ConnectionPhase): Boolean =
    phase == ConnectionPhase.RECOVERING_VPN || phase == ConnectionPhase.CONNECTED

internal fun accountSwitchPendingState(state: ConnectionUiState): ConnectionUiState =
    state.withoutSensitiveInputs().copy(
        phase = ConnectionPhase.FETCHING_AUTH_METHODS,
        internalCode = "",
        rememberedUsername = "",
        username = "",
    )

internal fun shouldRetryAccountSwitchClear(state: ConnectionUiState): Boolean =
    state.phase == ConnectionPhase.ERROR && state.internalCode == "accountSwitchClearFailed"

internal fun usesScrollableHomeLayout(phase: ConnectionPhase): Boolean = phase in AUTH_INPUT_PHASES

private val AUTH_INPUT_PHASES = setOf(
    ConnectionPhase.AWAITING_CREDENTIALS,
    ConnectionPhase.AWAITING_PHONE,
    ConnectionPhase.AWAITING_SMS,
    ConnectionPhase.AWAITING_TOKEN,
    ConnectionPhase.AWAITING_CAPTCHA,
)

private val AUTHENTICATION_PHASES = setOf(
    ConnectionPhase.RESTORING_SESSION,
    ConnectionPhase.FETCHING_AUTH_METHODS,
    ConnectionPhase.AUTHENTICATING,
) + AUTH_INPUT_PHASES

private val VPN_PHASES = setOf(
    ConnectionPhase.PREPARING_VPN_PERMISSION,
    ConnectionPhase.ESTABLISHING_VPN,
    ConnectionPhase.RECOVERING_VPN,
    ConnectionPhase.CONNECTED,
    ConnectionPhase.DISCONNECTING,
)

private val SUPPORTED_AUTH_TYPES = setOf("auth/psw", "auth/smsCheckCode")
