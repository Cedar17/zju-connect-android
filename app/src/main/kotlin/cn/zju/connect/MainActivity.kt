package cn.zju.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZjuConnectApp()
        }
    }
}
@Composable
private fun ZjuConnectApp() {
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
                }
            }
        }
    }
}
