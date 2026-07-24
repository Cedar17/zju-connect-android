package cn.zju.connect

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startTestVpnService()
            } else {
                TestVpnStateStore.setError("vpnPermissionDenied", "VPN permission was not granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val authState by authViewModel.state.collectAsState()
            ZjuConnectApp(
                authState = authState,
                authViewModel = authViewModel,
                onStartTestVpn = ::requestStartTestVpn,
                onStopTestVpn = ::stopTestVpn,
                onResetTestVpn = TestVpnStateStore::reset,
            )
        }
    }

    private fun requestStartTestVpn() {
        TestVpnStateStore.setStatus("preparingVpnPermission", "Checking Android VPN permission")
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startTestVpnService()
        }
    }

    private fun startTestVpnService() {
        startService(
            Intent(this, TestVpnService::class.java)
                .setAction(TestVpnService.ACTION_START),
        )
    }

    private fun stopTestVpn() {
        startService(
            Intent(this, TestVpnService::class.java)
                .setAction(TestVpnService.ACTION_STOP),
        )
    }
}

@Composable
private fun ZjuConnectApp(
    authState: AuthUiState,
    authViewModel: AuthViewModel,
    onStartTestVpn: () -> Unit,
    onStopTestVpn: () -> Unit,
    onResetTestVpn: () -> Unit,
) {
    val goCoreBridge = remember { GoCoreBridge() }
    var bridgeStatus by remember {
        mutableStateOf(
            GoBridgeEvent(
                type = "initializing",
                upstreamCommit = "unknown",
                message = "Calling Go bridge",
            ),
        )
    }
    val testState = TestVpnStateStore.state
    val testIsActive = testState.state in setOf(
        "starting",
        "preparingVpnPermission",
        "tunAttached",
        "socketProtected",
        "roundTripVerified",
        "stopping",
    )

    LaunchedEffect(goCoreBridge) {
        bridgeStatus = goCoreBridge.readBuildInfo()
        goCoreBridge.emitBuildInfo { event -> bridgeStatus = event }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding)
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "ZJU Connect",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = bridgeStatus.displayText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    AuthenticationPanel(authState, authViewModel)

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "实验性 TUN 数据面",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(text = "状态：${testState.state}")
                    Text(text = testState.message)
                    Text(
                        text = "TUN → Go：${testState.packetsFromTun} 包 / ${testState.bytesFromTun} 字节",
                    )
                    Text(
                        text = "Go → TUN：${testState.packetsToTun} 包 / ${testState.bytesToTun} 字节",
                    )
                    Button(
                        onClick = onStartTestVpn,
                        enabled = !testIsActive && !isAuthenticationActive(authState.phase),
                    ) {
                        Text("启动测试 VPN")
                    }
                    OutlinedButton(
                        onClick = onStopTestVpn,
                        enabled = testIsActive,
                    ) {
                        Text("停止测试 VPN")
                    }
                    OutlinedButton(
                        onClick = onResetTestVpn,
                        enabled = !testIsActive,
                    ) {
                        Text("重置测试状态")
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthenticationPanel(state: AuthUiState, viewModel: AuthViewModel) {
    Text(
        text = "浙江大学 aTrust 认证",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(text = "状态：${state.phase}")
    Text(text = state.message)
    if (state.code.isNotBlank()) {
        Text(text = "代码：${state.code}", color = MaterialTheme.colorScheme.error)
    }

    when (state.phase) {
        "idle", "cancelled" -> Button(onClick = viewModel::startAuthentication) {
            Text("开始登录")
        }

        "fetchingAuthMethods", "authenticating" -> RowProgress()

        "awaitingMethod" -> {
            Text("选择登录方式")
            state.authMethods.forEach { method ->
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.selectMethod(method) },
                ) {
                    val label = method.authName.ifBlank { method.authType }
                    Text(if (method == state.selectedMethod) "默认：$label" else label)
                }
            }
            CancelButton(viewModel)
        }

        "awaitingCredentials" -> {
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
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(
                onClick = viewModel::submitCredentials,
                enabled = state.username.isNotBlank() && state.password.isNotBlank(),
            ) {
                Text("提交账号与密码")
            }
            CancelButton(viewModel)
        }

        "awaitingPhone" -> {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.phone,
                onValueChange = viewModel::updatePhone,
                label = { Text("手机号") },
                singleLine = true,
            )
            Button(onClick = viewModel::submitPhone, enabled = state.phone.isNotBlank()) {
                Text("发送短信验证码")
            }
            CancelButton(viewModel)
        }

        "awaitingSms" -> {
            state.phoneNumbers.firstOrNull()?.let { Text("发送至：$it") }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.smsCode,
                onValueChange = viewModel::updateSmsCode,
                label = { Text("短信验证码") },
                singleLine = true,
            )
            Button(onClick = viewModel::submitSmsCode, enabled = state.smsCode.isNotBlank()) {
                Text("提交短信验证码")
            }
            CancelButton(viewModel)
        }

        "awaitingCaptcha" -> {
            Text("请按服务端提示依次点击图中的位置。")
            CaptchaChallenge(state, viewModel)
            Text("已选择 ${state.captchaPoints.size} 个位置")
            Button(onClick = viewModel::submitCaptcha, enabled = state.captchaPoints.isNotEmpty()) {
                Text("提交图形验证码")
            }
            OutlinedButton(onClick = viewModel::clearCaptchaPoints) {
                Text("清除已选位置")
            }
            CancelButton(viewModel)
        }

        "authenticated" -> {
            Text(
                text = "认证已完成${state.authenticatedUsername.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}",
                color = MaterialTheme.colorScheme.primary,
            )
            Text("认证结果仅保留在当前进程内，尚未建立 VPN 隧道。")
            OutlinedButton(onClick = viewModel::cancelAuthentication) {
                Text("清除本次认证结果")
            }
        }

        "error" -> {
            Button(onClick = viewModel::retryAuthentication) {
                Text("重试登录")
            }
            CancelButton(viewModel)
        }
    }
}

@Composable
private fun RowProgress() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        CircularProgressIndicator(modifier = Modifier.width(24.dp))
    }
}

@Composable
private fun CancelButton(viewModel: AuthViewModel) {
    OutlinedButton(onClick = viewModel::cancelAuthentication) {
        Text("取消登录")
    }
}

@Composable
private fun CaptchaChallenge(state: AuthUiState, viewModel: AuthViewModel) {
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
            state.captchaPoints.forEachIndexed { index, point ->
                val x = point.x.toFloat() / state.captchaWidth * size.width
                val y = point.y.toFloat() / state.captchaHeight * size.height
                drawCircle(Color.Red, radius = with(density) { 8.dp.toPx() }, center = Offset(x, y))
                drawCircle(Color.White, radius = with(density) { 3.dp.toPx() }, center = Offset(x, y))
            }
        }
    }
}

private fun isAuthenticationActive(phase: String): Boolean = phase !in setOf("idle", "cancelled", "error", "authenticated")
