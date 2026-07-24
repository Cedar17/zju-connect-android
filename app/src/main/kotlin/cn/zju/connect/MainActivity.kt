package cn.zju.connect

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
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
            ZjuConnectApp(
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
                        .padding(contentPadding)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "ZJU Connect",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = bridgeStatus.displayText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
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
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onStartTestVpn,
                        enabled = !testIsActive,
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
