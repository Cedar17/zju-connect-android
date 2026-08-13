package io.github.cedar17.zjuconnect

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private const val MAIN_ACTIVITY_LOG_TAG = "ZjuConnectMain"
private const val BACKGROUND_PROTECTION_PREFERENCES = "background_protection"
private const val NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

class MainActivity : ComponentActivity() {
    private val connectionViewModel: ConnectionViewModel by viewModels()
    private val backgroundProtectionState = mutableStateOf(BackgroundProtectionState())
    private val backgroundProtectionPreferences by lazy {
        getSharedPreferences(BACKGROUND_PROTECTION_PREFERENCES, MODE_PRIVATE)
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            connectionViewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshBackgroundProtection()
            requestBatteryProtectionIfNeeded()
        }

    private val notificationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshBackgroundProtection()
            requestBatteryProtectionIfNeeded()
        }

    private val batterySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshBackgroundProtection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshBackgroundProtection()
        enableEdgeToEdge()
        setContent {
            val state by connectionViewModel.state.collectAsState()
            val protection by backgroundProtectionState
            LaunchedEffect(connectionViewModel) {
                connectionViewModel.effects.collect(::handleConnectionEffect)
            }
            ZjuConnectTheme {
                ZjuConnectApp(
                    state = state,
                    viewModel = connectionViewModel,
                    backgroundProtection = protection,
                    onEnableBackgroundProtection = ::requestBackgroundProtection,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBackgroundProtection()
    }

    private fun requestBackgroundProtection() {
        refreshBackgroundProtection()
        if (!backgroundProtectionState.value.notificationsEnabled) {
            requestNotificationProtection()
        } else {
            requestBatteryProtectionIfNeeded()
        }
    }

    private fun requestNotificationProtection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val previouslyRequested = backgroundProtectionPreferences.getBoolean(
                NOTIFICATION_PERMISSION_REQUESTED,
                false,
            )
            if (!previouslyRequested || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                backgroundProtectionPreferences.edit()
                    .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
                    .apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notificationSettingsLauncher.launch(appNotificationSettingsIntent())
            }
            return
        }
        notificationSettingsLauncher.launch(appNotificationSettingsIntent())
    }

    private fun requestBatteryProtectionIfNeeded() {
        refreshBackgroundProtection()
        if (backgroundProtectionState.value.batteryOptimizationIgnored) return

        val packageUri = Uri.parse("package:$packageName")
        val intent = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        ).firstOrNull { candidate -> candidate.resolveActivity(packageManager) != null }
        if (intent == null) {
            Log.w(MAIN_ACTIVITY_LOG_TAG, "No battery optimization settings activity is available")
            return
        }
        runCatching { batterySettingsLauncher.launch(intent) }
            .onFailure { Log.e(MAIN_ACTIVITY_LOG_TAG, "Unable to open battery optimization settings", it) }
    }

    private fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun refreshBackgroundProtection() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val runtimeNotificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val powerManager = getSystemService(PowerManager::class.java)
        backgroundProtectionState.value = BackgroundProtectionState(
            notificationsEnabled = runtimeNotificationGranted && notificationManager.areNotificationsEnabled(),
            batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun handleConnectionEffect(effect: ConnectionEffect) {
        if (!connectionViewModel.canHandleEffect(effect)) return
        when (effect) {
            is ConnectionEffect.RequestVpnPermission -> {
                val prepareIntent = runCatching { VpnService.prepare(this) }
                    .getOrElse { error ->
                        Log.e(MAIN_ACTIVITY_LOG_TAG, "Unable to request VPN permission", error)
                        connectionViewModel.onVpnServiceDispatchFailed(effect)
                        return
                    }
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    connectionViewModel.onVpnPermissionResult(granted = true)
                }
            }
            is ConnectionEffect.StartVpnService -> {
                runCatching {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, RealVpnService::class.java)
                            .setAction(RealVpnService.ACTION_START),
                    )
                }.onFailure { error ->
                    Log.e(MAIN_ACTIVITY_LOG_TAG, "Unable to start VPN service", error)
                    connectionViewModel.onVpnServiceDispatchFailed(effect)
                }
            }
            is ConnectionEffect.StopVpnService -> {
                runCatching {
                    startService(
                        Intent(this, RealVpnService::class.java)
                            .setAction(RealVpnService.ACTION_STOP),
                    )
                }.onFailure { error ->
                    Log.e(MAIN_ACTIVITY_LOG_TAG, "Unable to stop VPN service", error)
                    connectionViewModel.onVpnServiceDispatchFailed(effect)
                }
            }
        }
    }
}

@Composable
internal fun ZjuConnectTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme(
            primary = Color(0xFF9FC9FF),
            secondary = Color(0xFFBBC7DB),
            tertiary = Color(0xFFD9BDE9),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ZjuConnectApp(
    state: ConnectionUiState,
    viewModel: ConnectionViewModel,
    backgroundProtection: BackgroundProtectionState,
    onEnableBackgroundProtection: () -> Unit,
) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                val contentModifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                if (
                    usesScrollableHomeLayout(state.phase) ||
                    shouldShowBackgroundProtection(state.phase, backgroundProtection)
                ) {
                    ConnectionHomeContent(
                        state = state,
                        viewModel = viewModel,
                        backgroundProtection = backgroundProtection,
                        onEnableBackgroundProtection = onEnableBackgroundProtection,
                        modifier = contentModifier
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 36.dp),
                        centered = false,
                    )
                } else {
                    ConnectionHomeContent(
                        state = state,
                        viewModel = viewModel,
                        backgroundProtection = backgroundProtection,
                        onEnableBackgroundProtection = onEnableBackgroundProtection,
                        modifier = contentModifier,
                        centered = true,
                    )
                }

                TextButton(
                    onClick = {
                        context.startActivity(Intent(context, DiagnosticsActivity::class.java))
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 4.dp),
                ) {
                    Text("诊断")
                }
            }
        }
    }
}

@Composable
private fun ConnectionHomeContent(
    state: ConnectionUiState,
    viewModel: ConnectionViewModel,
    backgroundProtection: BackgroundProtectionState,
    onEnableBackgroundProtection: () -> Unit,
    modifier: Modifier,
    centered: Boolean,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centered) {
            Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        } else {
            Arrangement.spacedBy(20.dp)
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "ZJU Connect",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "浙江大学 VPN",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ConnectionIndicator(state.phase)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = connectionTitle(state.phase),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = connectionSupportingText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.phase == ConnectionPhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (state.notice.isNotBlank()) {
            Text(
                text = state.notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        if (shouldShowBackgroundProtection(state.phase, backgroundProtection)) {
            BackgroundProtectionCard(onEnableBackgroundProtection)
        }

        AuthenticationStep(state, viewModel)

        Button(
            onClick = viewModel::onPrimaryAction,
            enabled = isPrimaryActionEnabled(state),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (isConnectionProgress(state.phase)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(primaryActionLabel(state.phase))
            }
        }

        if (canSwitchAccount(state)) {
            TextButton(onClick = viewModel::switchAccount) {
                Text("切换账号")
            }
        }

        if (canCancelConnection(state.phase)) {
            TextButton(onClick = viewModel::cancelConnection) {
                Text("取消连接")
            }
        }
    }
}

@Composable
private fun BackgroundProtectionCard(onEnable: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "开启后台保护",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "允许通知并解除电池限制，锁屏时连接更稳定，也能从通知栏断开。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(
                onClick = onEnable,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("开启")
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(phase: ConnectionPhase) {
    val indicatorColor = when (phase) {
        ConnectionPhase.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionPhase.ERROR -> MaterialTheme.colorScheme.error
        ConnectionPhase.DISCONNECTED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = Modifier
            .size(112.dp)
            .background(indicatorColor.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(indicatorColor, CircleShape),
        )
    }
}

@Composable
private fun AuthenticationStep(state: ConnectionUiState, viewModel: ConnectionViewModel) {
    when (state.phase) {
        ConnectionPhase.AWAITING_CREDENTIALS -> {
            var passwordVisible by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.username,
                    onValueChange = viewModel::updateUsername,
                    label = { Text("上网账号") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    label = { Text("密码") },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                text = if (passwordVisible) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                    singleLine = true,
                )
            }
        }
        ConnectionPhase.AWAITING_PHONE -> OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.phone,
            onValueChange = viewModel::updatePhone,
            label = { Text("手机号") },
            singleLine = true,
        )
        ConnectionPhase.AWAITING_SMS -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.phoneNumbers.firstOrNull()?.let { maskedPhone ->
                    Text(
                        text = "验证码已发送至 $maskedPhone",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.smsCode,
                    onValueChange = viewModel::updateSmsCode,
                    label = { Text("短信验证码") },
                    singleLine = true,
                )
            }
        }
        ConnectionPhase.AWAITING_TOKEN -> OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.token,
            onValueChange = viewModel::updateToken,
            label = { Text(tokenChallengeMessage(state.challengeKind)) },
            singleLine = true,
        )
        ConnectionPhase.AWAITING_CAPTCHA -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("请按服务端提示依次点击图片中的位置。")
                CaptchaChallenge(state, viewModel)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已选择 ${state.captchaPoints.size} 个位置")
                    TextButton(onClick = viewModel::clearCaptchaPoints) {
                        Text("重新选择")
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun CaptchaChallenge(state: ConnectionUiState, viewModel: ConnectionViewModel) {
    val image = state.captchaImage?.let { bytes ->
        remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    if (image == null) {
        Text("验证码图片暂不可用", color = MaterialTheme.colorScheme.error)
        return
    }

    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .onSizeChanged { imageSize = it }
            .pointerInput(state.captchaWidth, state.captchaHeight) {
                detectTapGestures { offset ->
                    viewModel.addCaptchaTap(
                        tapX = offset.x,
                        tapY = offset.y,
                        displayedWidth = imageSize.width.toFloat(),
                        displayedHeight = imageSize.height.toFloat(),
                    )
                }
            },
    ) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = "图形验证码",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            state.captchaPoints.forEach { point ->
                val x = point.x.toFloat() / state.captchaWidth * size.width
                val y = point.y.toFloat() / state.captchaHeight * size.height
                drawCircle(Color.Red, radius = with(density) { 8.dp.toPx() }, center = Offset(x, y))
                drawCircle(Color.White, radius = with(density) { 3.dp.toPx() }, center = Offset(x, y))
            }
        }
    }
}

private fun connectionTitle(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.CONNECTED -> "已连接"
    ConnectionPhase.RECOVERING_VPN -> "正在恢复"
    ConnectionPhase.ERROR -> "连接遇到问题"
    ConnectionPhase.DISCONNECTED -> "未连接"
    ConnectionPhase.DISCONNECTING -> "正在断开"
    else -> "正在连接"
}

internal fun connectionSupportingText(state: ConnectionUiState): String =
    if (state.phase == ConnectionPhase.DISCONNECTED || state.phase == ConnectionPhase.CONNECTED) {
        if (state.rememberedUsername.isBlank()) {
            "尚未保存账号"
        } else {
            "账号：${state.rememberedUsername}"
        }
    } else {
        state.statusMessage
    }

private fun primaryActionLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.DISCONNECTED -> "连接"
    ConnectionPhase.ERROR -> "重试"
    ConnectionPhase.AWAITING_CREDENTIALS -> "登录并连接"
    ConnectionPhase.AWAITING_PHONE -> "发送验证码"
    ConnectionPhase.AWAITING_SMS -> "验证并连接"
    ConnectionPhase.AWAITING_TOKEN -> "验证并连接"
    ConnectionPhase.AWAITING_CAPTCHA -> "提交并继续"
    ConnectionPhase.RECOVERING_VPN,
    ConnectionPhase.CONNECTED -> "断开"
    else -> "正在连接"
}

private fun isPrimaryActionEnabled(state: ConnectionUiState): Boolean = when (state.phase) {
    ConnectionPhase.DISCONNECTED,
    ConnectionPhase.ERROR,
    ConnectionPhase.RECOVERING_VPN,
    ConnectionPhase.CONNECTED -> true
    ConnectionPhase.AWAITING_CREDENTIALS -> state.username.isNotBlank() && state.password.isNotBlank()
    ConnectionPhase.AWAITING_PHONE -> state.phone.isNotBlank()
    ConnectionPhase.AWAITING_SMS -> state.smsCode.isNotBlank()
    ConnectionPhase.AWAITING_TOKEN -> state.token.isNotBlank()
    ConnectionPhase.AWAITING_CAPTCHA -> state.captchaPoints.isNotEmpty()
    else -> false
}

private fun isConnectionProgress(phase: ConnectionPhase): Boolean = phase in setOf(
    ConnectionPhase.RESTORING_SESSION,
    ConnectionPhase.FETCHING_AUTH_METHODS,
    ConnectionPhase.AUTHENTICATING,
    ConnectionPhase.PREPARING_VPN_PERMISSION,
    ConnectionPhase.ESTABLISHING_VPN,
    ConnectionPhase.DISCONNECTING,
)

private fun canCancelConnection(phase: ConnectionPhase): Boolean = phase in setOf(
    ConnectionPhase.RESTORING_SESSION,
    ConnectionPhase.FETCHING_AUTH_METHODS,
    ConnectionPhase.AUTHENTICATING,
    ConnectionPhase.AWAITING_CREDENTIALS,
    ConnectionPhase.AWAITING_PHONE,
    ConnectionPhase.AWAITING_SMS,
    ConnectionPhase.AWAITING_TOKEN,
    ConnectionPhase.AWAITING_CAPTCHA,
    ConnectionPhase.PREPARING_VPN_PERMISSION,
    ConnectionPhase.ESTABLISHING_VPN,
)
