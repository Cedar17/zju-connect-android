package cn.zju.connect

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
    AWAITING_CAPTCHA,
    PREPARING_VPN_PERMISSION,
    ESTABLISHING_VPN,
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
    val rememberedUsername: String = "",
    val username: String = "",
    val password: String = "",
    val phone: String = "",
    val smsCode: String = "",
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
    DELETE_AND_REAUTHENTICATE,
    RETAIN_AND_SHOW_ERROR,
}

internal fun storedSessionFailureAction(eventType: String, code: String): StoredSessionFailureAction =
    if (eventType == "sessionInvalid" || code in setOf("invalidSession", "sessionInvalid")) {
        StoredSessionFailureAction.DELETE_AND_REAUTHENTICATE
    } else {
        StoredSessionFailureAction.RETAIN_AND_SHOW_ERROR
    }

internal fun selectAutomaticAuthMethod(methods: List<GoAuthMethod>): GoAuthMethod? {
    methods.firstOrNull {
        it.authType == "auth/psw" && it.loginDomain == "Radius"
    }?.let { return it }

    val supported = methods.filter { it.authType in SUPPORTED_AUTH_TYPES }
    return supported.singleOrNull()
}

internal fun connectionErrorMessage(code: String): String = when (code) {
    "vpnPermissionDenied" -> "需要授予系统 VPN 权限才能连接。"
    "certificateRejected" -> "无法验证学校 VPN 服务器，请检查系统时间和当前网络后重试。"
    "unsupportedAuthMethod" -> "学校当前要求的登录方式暂不受支持。"
    "invalidInput", "authenticationFailed" -> "登录未完成，请检查账号、密码或验证码后重试。"
    "sessionStoreUnavailable" -> "无法读取本机保存的登录状态，请稍后重试。"
    "sessionRestoreUnavailable" -> "暂时无法验证已保存的登录状态，请检查网络后重试。"
    "authInfoUnavailable", "initializationFailed" -> "暂时无法连接学校 VPN 服务，请检查网络后重试。"
    "vpnRevoked" -> "系统已撤销 VPN 权限，请重新连接。"
    "vpnStopDispatchFailed" -> "未能发送断开请求，请稍后重试。"
    "vpnStartDispatchFailed" -> "未能启动 VPN 服务，请稍后重试。"
    "vpnSetupFailed", "vpnConfigurationUnavailable", "vpnAddressUnavailable", "vpnRoutesUnavailable" ->
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

    private companion object {
        const val PREFERENCES_NAME = "connection_preferences"
        const val KEY_USERNAME = "last_authenticated_username"
    }
}

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val bridge = GoCoreBridge()
    private val sessionStore = AuthSessionStore(application)
    private val accountStore = AccountStore(application)
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
    private var hasAuthenticatedResult = false
    private var pendingPermissionAttemptId: Long? = null

    init {
        // Observing an in-process service state is local-only. Session files and
        // the Go authentication bridge are deliberately untouched until connect.
        viewModelScope.launch {
            RealVpnStateStore.state.collect(::consumeVpnState)
        }
    }

    fun onPrimaryAction() {
        when (_state.value.phase) {
            ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR -> beginConnection()
            ConnectionPhase.AWAITING_CREDENTIALS -> submitCredentials()
            ConnectionPhase.AWAITING_PHONE -> submitPhone()
            ConnectionPhase.AWAITING_SMS -> submitSmsCode()
            ConnectionPhase.AWAITING_CAPTCHA -> submitCaptcha()
            ConnectionPhase.CONNECTED -> cancelConnection()
            else -> Unit
        }
    }

    fun updateUsername(username: String) = _state.update { it.copy(username = username) }

    fun updatePassword(password: String) = _state.update { it.copy(password = password) }

    fun updatePhone(phone: String) = _state.update { it.copy(phone = phone) }

    fun updateSmsCode(code: String) = _state.update { it.copy(smsCode = code) }

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

    fun cancelConnection() {
        val currentPhase = _state.value.phase
        val vpnMayBeRunning = currentPhase in setOf(
            ConnectionPhase.ESTABLISHING_VPN,
            ConnectionPhase.CONNECTED,
            ConnectionPhase.DISCONNECTING,
        )
        val stopAttemptId = attempts.invalidate()
        pendingPermissionAttemptId = null
        restoringStoredSession = false

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
            showError("vpnPermissionDenied")
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
        if (!hasAuthenticatedResult) {
            bridge.cancelAuthentication()
        }
        RealVpnStateStore.reset()
        _state.update {
            it.withoutSensitiveInputs().copy(
                phase = ConnectionPhase.RESTORING_SESSION,
                statusMessage = if (hasAuthenticatedResult) "正在继续已认证会话…" else "正在检查已保存的登录状态…",
                internalCode = "",
                notice = "",
            )
        }
        if (hasAuthenticatedResult) {
            requestVpnPermission(attemptId)
            return
        }
        restoreStoredSessionOrAuthenticate(attemptId)
    }

    private fun restoreStoredSessionOrAuthenticate(attemptId: Long) {
        restoringStoredSession = true
        viewModelScope.launch {
            val snapshot = try {
                withContext(Dispatchers.IO) { sessionStore.read() }
            } catch (_: InvalidStoredAuthenticationSession) {
                restoringStoredSession = false
                if (attempts.accepts(attemptId)) {
                    startFreshAuthentication(attemptId, "已保存的登录状态不可用，请重新登录。")
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
                startFreshAuthentication(attemptId)
                return@launch
            }

            try {
                consumeAuthEvent(
                    attemptId,
                    bridge.resumeAuthentication(snapshot) { event -> consumeAuthEvent(attemptId, event) },
                )
            } finally {
                snapshot.fill(0)
            }
        }
    }

    private fun startFreshAuthentication(attemptId: Long, notice: String = "") {
        if (!attempts.accepts(attemptId)) return
        restoringStoredSession = false
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
            bridge.startAuthentication { event -> consumeAuthEvent(attemptId, event) },
        )
    }

    private fun submitCredentials() {
        val current = _state.value
        if (current.username.isBlank() || current.password.isBlank()) return
        val attemptId = attempts.activeAttemptId
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
                .put("username", current.username)
                .put("password", current.password),
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
                "authMethodsReady" -> handleAuthMethods(attemptId, event.authMethods)
                "credentialsRequired" -> _state.update {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_CREDENTIALS,
                        statusMessage = "请输入浙大上网账号和密码",
                        internalCode = "",
                        password = "",
                    )
                }
                "phoneRequired" -> _state.update {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_PHONE,
                        statusMessage = "请输入服务端要求的手机号",
                        internalCode = "",
                    )
                }
                "smsRequired" -> _state.update {
                    it.copy(
                        phase = ConnectionPhase.AWAITING_SMS,
                        statusMessage = "请输入收到的短信验证码",
                        internalCode = "",
                        phoneNumbers = event.phoneNumbers.ifEmpty { it.phoneNumbers },
                        smsCode = "",
                    )
                }
                "captchaRequired" -> _state.update {
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
                "sessionInvalid" -> handleStoredSessionFailure(attemptId, event)
                "error" -> {
                    if (restoringStoredSession &&
                        storedSessionFailureAction(event.type, event.code) ==
                        StoredSessionFailureAction.DELETE_AND_REAUTHENTICATE
                    ) {
                        handleStoredSessionFailure(attemptId, event)
                    } else {
                        restoringStoredSession = false
                        showError(event.code)
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
            StoredSessionFailureAction.DELETE_AND_REAUTHENTICATE -> {
                withContext(Dispatchers.IO) { sessionStore.clear() }
                restoringStoredSession = false
                startFreshAuthentication(attemptId, "登录状态已过期，请重新登录。")
            }
            StoredSessionFailureAction.RETAIN_AND_SHOW_ERROR -> {
                restoringStoredSession = false
                showError(event.code)
            }
        }
    }

    private suspend fun persistAuthenticatedSession(attemptId: Long, event: GoAuthEvent) {
        if (!attempts.accepts(attemptId)) return
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
        if (!attempts.accepts(attemptId)) return

        restoringStoredSession = false
        hasAuthenticatedResult = true
        val continuation = authenticatedContinuation(sessionSaved, usernameSaved)
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
                    showError(vpnState.code)
                }
            }
        }
    }

    private fun showError(code: String) {
        pendingPermissionAttemptId = null
        _state.update {
            it.withoutSensitiveInputs().copy(
                phase = ConnectionPhase.ERROR,
                statusMessage = connectionErrorMessage(code),
                internalCode = code,
            )
        }
    }

    override fun onCleared() {
        attempts.invalidate()
        pendingPermissionAttemptId = null
        bridge.cancelAuthentication()
        bridge.clearAuthenticatedResult()
        super.onCleared()
    }
}

private fun ConnectionUiState.withoutSensitiveInputs(): ConnectionUiState = copy(
    password = "",
    phone = "",
    smsCode = "",
    phoneNumbers = emptyList(),
    captchaImage = null,
    captchaWidth = 0,
    captchaHeight = 0,
    captchaPoints = emptyList(),
)

private val AUTH_INPUT_PHASES = setOf(
    ConnectionPhase.AWAITING_CREDENTIALS,
    ConnectionPhase.AWAITING_PHONE,
    ConnectionPhase.AWAITING_SMS,
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
    ConnectionPhase.CONNECTED,
    ConnectionPhase.DISCONNECTING,
)

private val SUPPORTED_AUTH_TYPES = setOf("auth/psw", "auth/smsCheckCode")
