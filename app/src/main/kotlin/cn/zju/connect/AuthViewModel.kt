package cn.zju.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class CaptchaPoint(
    val x: Int,
    val y: Int,
)

data class AuthUiState(
    val phase: String = "idle",
    val code: String = "",
    val message: String = "Ready to authenticate with Zhejiang University",
    val authMethods: List<GoAuthMethod> = emptyList(),
    val selectedMethod: GoAuthMethod? = null,
    val username: String = "",
    val password: String = "",
    val phone: String = "",
    val smsCode: String = "",
    val phoneNumbers: List<String> = emptyList(),
    val captchaImage: ByteArray? = null,
    val captchaWidth: Int = 0,
    val captchaHeight: Int = 0,
    val captchaPoints: List<CaptchaPoint> = emptyList(),
    val authenticatedUsername: String = "",
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

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val bridge = GoCoreBridge()
    private val sessionStore = AuthSessionStore(application)
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private var restoringStoredSession = false
    private var storedSessionRetryAvailable = false

    init {
        restoreStoredSession()
    }

    fun startAuthentication() {
        _state.update { it.copy(phase = "fetchingAuthMethods", code = "", message = "Fetching available authentication methods") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { sessionStore.clear() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        phase = "error",
                        code = "sessionStoreUnavailable",
                        message = "Saved authentication data could not be cleared",
                    )
                }
                return@launch
            }
            restoringStoredSession = false
            storedSessionRetryAvailable = false
            consume(bridge.startAuthentication(::consume))
        }
    }

    fun selectMethod(method: GoAuthMethod) {
        _state.update { it.copy(selectedMethod = method, phase = "authenticating", code = "") }
        submit(
            JSONObject()
                .put("action", "selectMethod")
                .put("authType", method.authType)
                .put("loginDomain", method.loginDomain),
        )
    }

    fun updateUsername(username: String) = _state.update { it.copy(username = username) }

    fun updatePassword(password: String) = _state.update { it.copy(password = password) }

    fun updatePhone(phone: String) = _state.update { it.copy(phone = phone) }

    fun updateSmsCode(code: String) = _state.update { it.copy(smsCode = code) }

    fun submitCredentials() {
        val current = _state.value
        _state.update { it.copy(password = "", phase = "authenticating", code = "") }
        submit(
            JSONObject()
                .put("action", "submitCredentials")
                .put("username", current.username)
                .put("password", current.password),
        )
    }

    fun submitPhone() {
        val phone = _state.value.phone
        _state.update { it.copy(phone = "", phase = "authenticating", code = "") }
        submit(JSONObject().put("action", "submitPhone").put("phone", phone))
    }

    fun submitSmsCode() {
        val code = _state.value.smsCode
        _state.update { it.copy(smsCode = "", phase = "authenticating", code = "") }
        submit(JSONObject().put("action", "submitSmsCode").put("smsCode", code))
    }

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

    fun submitCaptcha() {
        val current = _state.value
        if (current.captchaPoints.isEmpty()) {
            _state.update { it.copy(code = "captchaRequired", message = "Tap each requested position before submitting") }
            return
        }
        val coordinates = JSONArray().also { values ->
            current.captchaPoints.forEach { point ->
                values.put(JSONArray().put(point.x).put(point.y))
            }
        }
        _state.update {
            it.copy(
                phase = "authenticating",
                code = "",
                captchaPoints = emptyList(),
                captchaImage = null,
            )
        }
        submit(
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

    fun retryAuthentication() {
        if (storedSessionRetryAvailable) {
            restoreStoredSession()
        } else {
            _state.update { it.copy(phase = "fetchingAuthMethods", code = "", message = "Retrying authentication") }
            submit(JSONObject().put("action", "retry"))
        }
    }

    fun cancelAuthentication() {
        bridge.cancelAuthentication()
        restoringStoredSession = false
        storedSessionRetryAvailable = false
        _state.value = AuthUiState(phase = "cancelled", code = "cancelled", message = "Authentication cancelled")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionStore.clear() }
        }
    }

    private fun submit(response: JSONObject) {
        consume(bridge.submitAuthentication(response, ::consume))
    }

    private fun consume(event: GoAuthEvent) {
        viewModelScope.launch {
            when (event.type) {
                "authMethodsReady" -> {
                    val preferred = event.authMethods.firstOrNull {
                        it.authType == "auth/psw" && it.loginDomain == "Radius"
                    } ?: event.authMethods.firstOrNull()
                    _state.update {
                        it.copy(
                            phase = event.state,
                            code = "",
                            message = event.message,
                            authMethods = event.authMethods,
                            selectedMethod = preferred,
                            captchaImage = null,
                            captchaPoints = emptyList(),
                        )
                    }
                }

                "captchaRequired" -> _state.update {
                    it.copy(
                        phase = event.state,
                        code = "",
                        message = event.message,
                        captchaImage = bridge.pendingCaptchaImage().takeIf { bytes -> bytes.isNotEmpty() },
                        captchaWidth = event.captchaWidth,
                        captchaHeight = event.captchaHeight,
                        captchaPoints = emptyList(),
                    )
                }

                "authenticated" -> persistAuthenticatedSession(event)

                "sessionInvalid" -> discardInvalidStoredSession(event)

                "error" -> handleAuthenticationError(event)

                "cancelled" -> _state.value = AuthUiState(
                    phase = "cancelled",
                    code = event.code,
                    message = event.message,
                )

                else -> _state.update {
                    if (it.phase == "authenticated" && event.type in setOf("authenticationStarted", "sessionRestoreStarted")) {
                        return@update it
                    }
                    it.copy(
                        phase = event.state.takeIf { state -> state.isNotBlank() && state != "unknown" } ?: it.phase,
                        code = event.code,
                        message = event.message,
                        phoneNumbers = event.phoneNumbers.ifEmpty { it.phoneNumbers },
                    )
                }
            }
        }
    }

    private fun restoreStoredSession() {
        restoringStoredSession = true
        storedSessionRetryAvailable = false
        _state.update {
            it.copy(
                phase = "restoringSession",
                code = "",
                message = "Checking saved authentication session",
                password = "",
                smsCode = "",
                phone = "",
                captchaImage = null,
                captchaPoints = emptyList(),
            )
        }
        viewModelScope.launch {
            val snapshot = try {
                withContext(Dispatchers.IO) { sessionStore.read() }
            } catch (error: InvalidStoredAuthenticationSession) {
                restoringStoredSession = false
                _state.value = AuthUiState(
                    phase = "idle",
                    code = "storedSessionInvalid",
                    message = "Saved authentication data was unreadable; sign in again",
                )
                return@launch
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                restoringStoredSession = false
                storedSessionRetryAvailable = true
                _state.update {
                    it.copy(
                        phase = "error",
                        code = "sessionStoreUnavailable",
                        message = "Saved authentication data could not be read",
                    )
                }
                return@launch
            }
            if (snapshot == null) {
                restoringStoredSession = false
                _state.value = AuthUiState()
                return@launch
            }
            try {
                consume(bridge.resumeAuthentication(snapshot, ::consume))
            } finally {
                snapshot.fill(0)
            }
        }
    }

    private suspend fun persistAuthenticatedSession(event: GoAuthEvent) {
        val snapshot = bridge.exportAuthenticatedSession()
        val saveError = if (snapshot.isEmpty()) {
            IllegalStateException("Go did not provide an authentication snapshot")
        } else {
            try {
                withContext(Dispatchers.IO) { sessionStore.write(snapshot) }
                null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                error
            } finally {
                snapshot.fill(0)
            }
        }
        restoringStoredSession = false
        storedSessionRetryAvailable = false
        _state.update {
            it.copy(
                phase = "authenticated",
                code = if (saveError == null) "" else "sessionSaveFailed",
                message = if (saveError == null) event.message else "Authenticated, but the session could not be saved",
                authenticatedUsername = event.username,
                password = "",
                smsCode = "",
                phone = "",
                captchaImage = null,
                captchaPoints = emptyList(),
            )
        }
    }

    private suspend fun discardInvalidStoredSession(event: GoAuthEvent) {
        withContext(Dispatchers.IO) { sessionStore.clear() }
        restoringStoredSession = false
        storedSessionRetryAvailable = false
        _state.value = AuthUiState(
            phase = "idle",
            code = event.code,
            message = event.message,
        )
    }

    private suspend fun handleAuthenticationError(event: GoAuthEvent) {
        if (restoringStoredSession && event.code == "invalidSession") {
            withContext(Dispatchers.IO) { sessionStore.clear() }
            restoringStoredSession = false
            storedSessionRetryAvailable = false
            _state.value = AuthUiState(
                phase = "idle",
                code = "storedSessionInvalid",
                message = "Saved authentication data was invalid; sign in again",
            )
            return
        }
        storedSessionRetryAvailable = restoringStoredSession
        restoringStoredSession = false
        _state.update {
            it.copy(
                phase = "error",
                code = event.code,
                message = event.message,
                password = "",
                smsCode = "",
                phone = "",
                captchaImage = null,
                captchaPoints = emptyList(),
            )
        }
    }

    override fun onCleared() {
        bridge.cancelAuthentication()
        bridge.clearAuthenticatedResult()
        super.onCleared()
    }
}
