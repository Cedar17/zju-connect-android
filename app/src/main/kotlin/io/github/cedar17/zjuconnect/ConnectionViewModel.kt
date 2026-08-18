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
    val statusMessage: String = "尚未连接",
    val internalCode: String = "",
    val notice: String = "",
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

internal enum class StoredSessionFailureAction {
    CLEAR_AND_REAUTHENTICATE,
    RETAIN_AND_SHOW_ERROR,
}

internal enum class ReusableAuthenticationFailureAction {
    FALLBACK_TO_STORED_SESSION,
    RETAIN_AND_SHOW_ERROR,
}

internal enum class AuthenticationRecoveryPath {
    REUSE_IN_PROCESS_RESULT,
    RESTORE_STORED_SESSION,
}

internal fun authenticationRecoveryPath(hasReusableAuthenticatedResult: Boolean): AuthenticationRecoveryPath =
    if (hasReusableAuthenticatedResult) {
        AuthenticationRecoveryPath.REUSE_IN_PROCESS_RESULT
    } else {
        AuthenticationRecoveryPath.RESTORE_STORED_SESSION
    }

internal fun storedSessionFailureAction(eventType: String, code: String): StoredSessionFailureAction =
    if (storedSessionInvalidationBoundary(eventType, code) != null) {
        StoredSessionFailureAction.CLEAR_AND_REAUTHENTICATE
    } else {
        StoredSessionFailureAction.RETAIN_AND_SHOW_ERROR
    }

internal fun reusableAuthenticationFailureAction(
    code: String,
    cause: String,
): ReusableAuthenticationFailureAction =
    if (code == "vpnSessionInvalid" || cause in setOf("authentication", "serverRejected")) {
        ReusableAuthenticationFailureAction.FALLBACK_TO_STORED_SESSION
    } else {
        ReusableAuthenticationFailureAction.RETAIN_AND_SHOW_ERROR
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

internal fun connectionErrorMessage(code: String): String = when (code) {
    "vpnPermissionDenied" -> "需要授予系统 VPN 权限才能连接。"
    "authDnsFailure" -> "无法解析学校 VPN 服务地址，请检查当前网络后重试。"
    "authNetworkFailure" -> "无法连接学校 VPN 服务，请切换网络后重试。"
    "authNetworkTimeout" -> "连接学校 VPN 服务超时，请切换网络后重试。"
    "authProtocolFailure" -> "学校 VPN 服务返回了无法识别的响应，请稍后重试。"
    "authServerFailure" -> "学校 VPN 服务暂时不可用，请稍后重试。"
    "vpnSessionInvalid" -> "登录状态已失效，请重新连接。"
    "vpnConfigurationUnavailable" -> "学校 VPN 暂时没有提供可用配置，请稍后重试。"
    "certificateRejected" -> "无法验证学校 VPN 服务器，请检查系统时间和当前网络后重试。"
    "unsupportedAuthMethod" -> "学校当前要求的登录方式暂不受支持。"
    "invalidInput", "authenticationFailed" -> "登录未完成，请检查账号、密码或验证码后重试。"
    "sessionStoreUnavailable" -> "无法读取本机保存的登录状态，请稍后重试。"
    "credentialStoreUnavailable" -> "无法更新本机保存的登录凭据，请稍后重试。"
    "deviceIdentityUnavailable" -> "无法读取本机设备身份，请重启设备后重试。"
    "accountSwitchClearFailed" -> "无法清除本机登录状态，请稍后重试。"
    "sessionRestoreUnavailable" -> "暂时无法验证已保存的登录状态，请检查网络后重试。"
    "alwaysOnAuthenticationRequired" -> "请打开应用完成登录后重试。"
    "alwaysOnDisconnectBlocked" -> "Always-on 由系统管理，请先在系统 VPN 设置中关闭。"
    "authInfoUnavailable", "initializationFailed" -> "暂时无法连接学校 VPN 服务，请检查网络后重试。"
    "vpnRevoked" -> "系统已撤销 VPN 权限，请重新连接。"
    "vpnStopDispatchFailed" -> "未能发送断开请求，请稍后重试。"
    "vpnStartDispatchFailed" -> "未能启动 VPN 服务，请稍后重试。"
    "networkMonitorUnavailable" -> "Android 无法监测当前网络，请重新连接。"
    "vpnSetupFailed", "vpnAddressUnavailable", "vpnRoutesUnavailable" ->
        "学校 VPN 暂时无法完成连接，请稍后重试。"
    "tunEstablishFailed", "tunEstablishTimeout", "tunInitializationFailed" ->
        "Android 无法建立 VPN 接口，请稍后重试。"
    "vpnTunReadFailed", "vpnTunWriteFailed", "vpnServerReadFailed", "vpnServerWriteFailed", "stopTimeout" ->
        "VPN 连接意外中断，请重试。"
    else -> "连接没有完成，请稍后重试。"
}

internal data class AuthenticatedContinuation(
    val requestVpnPermission: Boolean,
    val notice: String,
)

internal fun authenticatedContinuation(
    sessionSaved: Boolean,
    usernameSaved: Boolean,
): AuthenticatedContinuation = AuthenticatedContinuation(
    requestVpnPermission = true,
    notice = if (sessionSaved && usernameSaved) {
        ""
    } else {
        "本次可以继续连接，但下次可能需要重新登录。"
    },
)

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
    private var pendingCredential: StoredCredential? = null
    private var savedCredentialAttempted = false
    private var pendingPermissionAttemptId: Long? = null
    private var reusableVpnPreparationPending = false
    private var authenticationRecoverySource: AuthenticationRecoverySource? = null

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
        pendingPermissionAttemptId = null
        restoringStoredSession = false
        pendingCredential = null
        savedCredentialAttempted = true
        activeDeviceID = deviceIdentityProvider.read().orEmpty()
        reusableVpnPreparationPending = false
        bridge.discardPreparedRealVpn()
        bridge.cancelAuthentication()
        clearInProcessAuthentication(AuthenticationStateBoundary.ACCOUNT_SWITCH)
        recordAuthenticationRecovery(
            source = AuthenticationRecoverySource.PERSISTED_SESSION,
            outcome = AuthenticationRecoveryOutcome.INVALIDATED,
            boundary = AuthenticationStateBoundary.ACCOUNT_SWITCH,
        )
        _state.update(::accountSwitchPendingState)

        viewModelScope.launch {
            val cleared = clearPersistedAuthentication(AuthenticationStateBoundary.ACCOUNT_SWITCH)
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
        pendingPermissionAttemptId = null
        restoringStoredSession = false
        pendingCredential = null
        reusableVpnPreparationPending = false
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
                    statusMessage = "正在断开 VPN…",
                    internalCode = "",
                )
            }
            effectChannel.trySend(ConnectionEffect.StopVpnService(stopAttemptId))
        } else {
            RealVpnStateStore.reset()
            _state.update {
                it.withoutSensitiveInputs().copy(
                    phase = ConnectionPhase.DISCONNECTED,
                    statusMessage = "尚未连接",
                    internalCode = "",
                    notice = "",
                )
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val attemptId = pendingPermissionAttemptId ?: return
        pendingPermissionAttemptId = null
        if (!attempts.accepts(attemptId) || _state.value.phase != ConnectionPhase.PREPARING_VPN_PERMISSION) {
            return
        }
        if (!granted) {
            reusableVpnPreparationPending = false
            showError("vpnPermissionDenied")
            return
        }
        if (reusableVpnPreparationPending) {
            reusableVpnPreparationPending = false
            _state.update {
                it.copy(
                    phase = ConnectionPhase.ESTABLISHING_VPN,
                    statusMessage = "正在复用上次认证状态…",
                    internalCode = "",
                )
            }
            prepareReusableVpn(attemptId)
            return
        }
        _state.update {
            it.copy(
                phase = ConnectionPhase.ESTABLISHING_VPN,
                statusMessage = "正在建立 VPN…",
                internalCode = "",
            )
        }
        effectChannel.trySend(ConnectionEffect.StartVpnService(attemptId))
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
        pendingPermissionAttemptId = null
        restoringStoredSession = false
        pendingCredential = null
        savedCredentialAttempted = false
        reusableVpnPreparationPending = false
        authenticationRecoverySource = null
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
                statusMessage = "正在检查已保存的登录状态…",
                internalCode = "",
                notice = "",
            )
        }
        when (authenticationRecoveryPath(bridge.hasReusableAuthenticatedResult())) {
            AuthenticationRecoveryPath.REUSE_IN_PROCESS_RESULT -> {
                authenticationRecoverySource = AuthenticationRecoverySource.REUSABLE_RESULT
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.REUSABLE_RESULT,
                    outcome = AuthenticationRecoveryOutcome.SELECTED,
                )
                reusableVpnPreparationPending = true
                requestVpnPermission(attemptId)
            }
            AuthenticationRecoveryPath.RESTORE_STORED_SESSION -> {
                authenticationRecoverySource = AuthenticationRecoverySource.PERSISTED_SESSION
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.PERSISTED_SESSION,
                    outcome = AuthenticationRecoveryOutcome.SELECTED,
                )
                restoreStoredSessionOrAuthenticate(attemptId)
            }
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
                        statusMessage = "正在建立 VPN…",
                        internalCode = "",
                    )
                }
                effectChannel.trySend(ConnectionEffect.StartVpnService(attemptId))
                return@launch
            }

            val code = config.code.ifBlank { "vpnSetupFailed" }
            if (
                config.state == "error" &&
                reusableAuthenticationFailureAction(code, config.cause) ==
                ReusableAuthenticationFailureAction.FALLBACK_TO_STORED_SESSION
            ) {
                clearInProcessAuthentication(AuthenticationStateBoundary.REUSABLE_RESULT_REJECTED)
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.REUSABLE_RESULT,
                    outcome = AuthenticationRecoveryOutcome.REJECTED,
                    boundary = AuthenticationStateBoundary.REUSABLE_RESULT_REJECTED,
                )
                bridge.discardPreparedRealVpn()
                _state.update {
                    it.withoutSensitiveInputs().copy(
                        phase = ConnectionPhase.RESTORING_SESSION,
                        statusMessage = "正在验证已保存的登录状态…",
                        internalCode = "",
                    )
                }
                authenticationRecoverySource = AuthenticationRecoverySource.PERSISTED_SESSION
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
                    val cleared = clearPersistedAuthentication(
                        AuthenticationStateBoundary.INVALID_STORED_SESSION,
                    )
                    recordAuthenticationRecovery(
                        source = AuthenticationRecoverySource.PERSISTED_SESSION,
                        outcome = AuthenticationRecoveryOutcome.INVALIDATED,
                        boundary = AuthenticationStateBoundary.INVALID_STORED_SESSION,
                    )
                    if (cleared) {
                        startFreshAuthentication(attemptId, "已保存的登录状态不可用，请重新登录。")
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

    private fun startFreshAuthentication(attemptId: Long, notice: String = "") {
        if (!attempts.accepts(attemptId)) return
        restoringStoredSession = false
        authenticationRecoverySource = null
        _state.update {
            it.withoutSensitiveInputs().copy(
                phase = ConnectionPhase.FETCHING_AUTH_METHODS,
                statusMessage = "正在获取学校要求的登录方式…",
                internalCode = "",
                notice = notice,
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
        pendingCredential = credential
        submitCredential(attempts.activeAttemptId, credential)
    }

    private fun submitCredential(attemptId: Long, credential: StoredCredential) {
        _state.update {
            it.copy(
                phase = ConnectionPhase.AUTHENTICATING,
                statusMessage = "正在验证账号…",
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
                statusMessage = "正在发送短信验证码…",
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
                statusMessage = "正在验证短信验证码…",
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
                statusMessage = "正在验证服务端要求的认证码…",
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
                statusMessage = "正在验证图形验证码…",
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
                        authenticationRecoverySource = AuthenticationRecoverySource.PERSISTED_SESSION_STALE
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
                        statusMessage = "请输入服务端要求的手机号",
                        internalCode = "",
                    )
                }
                "smsRequired" -> showServerChallenge {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_SMS,
                        statusMessage = "请输入收到的短信验证码",
                        internalCode = "",
                        phoneNumbers = event.phoneNumbers.ifEmpty { it.phoneNumbers },
                        smsCode = "",
                    )
                }
                "tokenRequired" -> showServerChallenge {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_TOKEN,
                        statusMessage = tokenChallengeMessage(event.challengeKind),
                        internalCode = "",
                        token = "",
                        challengeKind = event.challengeKind,
                    )
                }
                "captchaRequired" -> showServerChallenge {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_CAPTCHA,
                        statusMessage = "请按提示完成图形验证码",
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
                        storedSessionFailureAction(event.type, event.code) ==
                        StoredSessionFailureAction.CLEAR_AND_REAUTHENTICATE
                    ) {
                        handleStoredSessionFailure(attemptId, event)
                    } else {
                        restoringStoredSession = false
                        authenticationRecoverySource?.let { source ->
                            recordAuthenticationRecovery(
                                source = source,
                                outcome = AuthenticationRecoveryOutcome.FAILED,
                            )
                        }
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
                                statusMessage = "尚未连接",
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
                                statusMessage = "正在获取学校要求的登录方式…",
                            )
                        }
                    }
                }
                "sessionRestoreStarted" -> {
                    if (_state.value.phase == ConnectionPhase.RESTORING_SESSION) {
                        _state.update { it.copy(statusMessage = "正在验证已保存的登录状态…") }
                    }
                }
                "responseAccepted" -> {
                    if (_state.value.phase !in AUTH_INPUT_PHASES) {
                        _state.update {
                            it.copy(
                                phase = ConnectionPhase.AUTHENTICATING,
                                statusMessage = "正在完成登录…",
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleCredentialsRequired(attemptId: Long, event: GoAuthEvent) {
        if (!attempts.accepts(attemptId)) return
        val invalidationBoundary = credentialInvalidationBoundary(event.code)
        if (invalidationBoundary != null) {
            val cleared = clearPersistedAuthentication(invalidationBoundary)
            recordAuthenticationRecovery(
                source = AuthenticationRecoverySource.SAVED_CREDENTIALS,
                outcome = AuthenticationRecoveryOutcome.REJECTED,
                boundary = invalidationBoundary,
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
                    statusMessage = "保存的密码已失效，请重新输入",
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
                pendingCredential = credential
                authenticationRecoverySource = AuthenticationRecoverySource.SAVED_CREDENTIALS
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.SAVED_CREDENTIALS,
                    outcome = AuthenticationRecoveryOutcome.SUBMITTED,
                )
                _state.update {
                    it.copy(
                        username = credential.username,
                        phase = ConnectionPhase.AUTHENTICATING,
                        statusMessage = "正在使用已保存的凭据重新验证…",
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
                statusMessage = "请输入浙大上网账号和密码",
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
                statusMessage = "正在准备登录…",
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
        when (storedSessionFailureAction(event.type, event.code)) {
            StoredSessionFailureAction.CLEAR_AND_REAUTHENTICATE -> {
                val boundary = requireNotNull(
                    storedSessionInvalidationBoundary(event.type, event.code),
                )
                val cleared = clearPersistedAuthentication(boundary)
                recordAuthenticationRecovery(
                    source = AuthenticationRecoverySource.PERSISTED_SESSION,
                    outcome = AuthenticationRecoveryOutcome.INVALIDATED,
                    boundary = boundary,
                )
                restoringStoredSession = false
                savedCredentialAttempted = false
                if (cleared) {
                    startFreshAuthentication(attemptId, "已保存的登录状态不可用，请重新登录。")
                } else {
                    showError("sessionStoreUnavailable")
                }
            }
            StoredSessionFailureAction.RETAIN_AND_SHOW_ERROR -> {
                restoringStoredSession = false
                authenticationRecoverySource?.let { source ->
                    recordAuthenticationRecovery(
                        source = source,
                        outcome = AuthenticationRecoveryOutcome.FAILED,
                    )
                }
                showError(event.code)
            }
        }
    }

    private suspend fun persistAuthenticatedSession(attemptId: Long, event: GoAuthEvent) {
        if (!attempts.accepts(attemptId)) return
        val credentialToSave = pendingCredential
        val snapshot = bridge.exportAuthenticatedSession()
        val sessionSaved = if (snapshot.isEmpty()) {
            false
        } else {
            try {
                withContext(Dispatchers.IO) { sessionStore.write(snapshot) }
                true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            } finally {
                snapshot.fill(0)
            }
        }
        val authenticatedUsername = event.username.ifBlank { _state.value.username }
        val usernameSaved = try {
            withContext(Dispatchers.IO) { accountStore.writeUsername(authenticatedUsername) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
        val credentialSaved = if (credentialToSave == null) {
            true
        } else {
            try {
                withContext(Dispatchers.IO) { credentialStore.write(credentialToSave) }
                true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
        }
        if (!attempts.accepts(attemptId)) return

        val authenticatedSource = when {
            restoringStoredSession -> AuthenticationRecoverySource.PERSISTED_SESSION_AUTHENTICATED
            else -> authenticationRecoverySource
        }
        authenticatedSource?.let { source ->
            recordAuthenticationRecovery(
                source = source,
                outcome = AuthenticationRecoveryOutcome.AUTHENTICATED,
            )
        }
        restoringStoredSession = false
        pendingCredential = null
        reusableVpnPreparationPending = false
        authenticationRecoverySource = null
        val continuation = authenticatedContinuation(sessionSaved, usernameSaved && credentialSaved)
        _state.update {
            it.withoutSensitiveInputs().copy(
                rememberedUsername = authenticatedUsername,
                username = authenticatedUsername,
                notice = continuation.notice,
            )
        }
        if (continuation.requestVpnPermission) {
            requestVpnPermission(attemptId)
        }
    }

    private fun requestVpnPermission(attemptId: Long) {
        if (!attempts.accepts(attemptId)) return
        pendingPermissionAttemptId = attemptId
        _state.update {
            it.copy(
                phase = ConnectionPhase.PREPARING_VPN_PERMISSION,
                statusMessage = "正在检查系统 VPN 权限…",
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
                            statusMessage = "正在建立 VPN…",
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
                            statusMessage = "正在恢复 VPN…",
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
                            statusMessage = "正在等待可用网络…",
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
                        statusMessage = vpnState.message.ifBlank { "已连接到浙江大学 VPN" },
                        internalCode = "",
                    )
                }
            }
            "active" -> _state.update {
                it.withoutSensitiveInputs().copy(
                    phase = ConnectionPhase.CONNECTED,
                    statusMessage = "已连接到浙江大学 VPN",
                    internalCode = "",
                )
            }
            "stopping" -> _state.update {
                it.copy(
                    phase = ConnectionPhase.DISCONNECTING,
                    statusMessage = "正在断开 VPN…",
                    internalCode = "",
                )
            }
            "stopped" -> {
                if (_state.value.phase in VPN_PHASES) {
                    _state.update {
                        it.withoutSensitiveInputs().copy(
                            phase = ConnectionPhase.DISCONNECTED,
                            statusMessage = "尚未连接",
                            internalCode = "",
                            notice = "",
                        )
                    }
                }
            }
            "error" -> {
                if (_state.value.phase !in AUTHENTICATION_PHASES) {
                    if (vpnState.code == "vpnSessionInvalid") {
                        clearInProcessAuthentication(AuthenticationStateBoundary.REUSABLE_RESULT_REJECTED)
                        recordAuthenticationRecovery(
                            source = AuthenticationRecoverySource.REUSABLE_RESULT,
                            outcome = AuthenticationRecoveryOutcome.INVALIDATED,
                            boundary = AuthenticationStateBoundary.REUSABLE_RESULT_REJECTED,
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
        authenticationRecoverySource = AuthenticationRecoverySource.SERVER_CHALLENGE
        recordAuthenticationRecovery(
            source = AuthenticationRecoverySource.SERVER_CHALLENGE,
            outcome = AuthenticationRecoveryOutcome.WAITING_FOR_USER,
        )
        _state.update(transform)
    }

    private fun recordAuthenticationRecovery(
        source: AuthenticationRecoverySource,
        outcome: AuthenticationRecoveryOutcome,
        boundary: AuthenticationStateBoundary? = null,
    ) {
        RedactedDiagnostics.recordAuthenticationRecovery(
            context = appContext,
            source = source,
            outcome = outcome,
            boundary = boundary,
        )
    }

    private fun clearInProcessAuthentication(boundary: AuthenticationStateBoundary) {
        if (authenticationStateDisposition(boundary).clearInProcessResult) {
            bridge.clearAuthenticatedResult()
        }
    }

    private suspend fun clearPersistedAuthentication(
        boundary: AuthenticationStateBoundary,
    ): Boolean {
        val disposition = authenticationStateDisposition(boundary)
        return try {
            withContext(Dispatchers.IO) {
                val sessionCleared = !disposition.clearStoredSession || sessionStore.clear()
                val credentialCleared = !disposition.clearSavedCredential || credentialStore.clear()
                val accountCleared = !disposition.clearRememberedAccount || accountStore.clear()
                sessionCleared && credentialCleared && accountCleared
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }

    private fun showError(
        code: String,
        diagnosticStage: String = "",
        diagnosticCause: String = "",
        diagnosticDurationMillis: Long = 0,
    ) {
        pendingPermissionAttemptId = null
        pendingCredential = null
        reusableVpnPreparationPending = false
        _state.update {
            it.withoutSensitiveInputs().copy(
                phase = ConnectionPhase.ERROR,
                statusMessage = connectionErrorMessage(code),
                internalCode = code,
                diagnosticStage = diagnosticStage,
                diagnosticCause = diagnosticCause,
                diagnosticDurationMillis = diagnosticDurationMillis,
            )
        }
    }

    override fun onCleared() {
        attempts.invalidate()
        pendingPermissionAttemptId = null
        reusableVpnPreparationPending = false
        bridge.discardPreparedRealVpn()
        bridge.cancelAuthentication()
        clearInProcessAuthentication(AuthenticationStateBoundary.VIEW_MODEL_TEARDOWN)
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
        statusMessage = "正在切换账号…",
        internalCode = "",
        notice = "",
        rememberedUsername = "",
        username = "",
    )

internal fun shouldRetryAccountSwitchClear(state: ConnectionUiState): Boolean =
    state.phase == ConnectionPhase.ERROR && state.internalCode == "accountSwitchClearFailed"

internal fun usesScrollableHomeLayout(phase: ConnectionPhase): Boolean = phase in AUTH_INPUT_PHASES

internal fun tokenChallengeMessage(challengeKind: String): String = when (challengeKind) {
    "auth/totp" -> "请输入动态认证码"
    "auth/radius" -> "请输入 RADIUS 认证码"
    "auth/challenge" -> "请输入服务端挑战码"
    else -> "请输入服务端要求的认证码"
}

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