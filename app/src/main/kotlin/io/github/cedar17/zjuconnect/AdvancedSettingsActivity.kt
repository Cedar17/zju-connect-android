package io.github.cedar17.zjuconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Advanced settings, reachable only after the hidden About unlock. Hosts the
 * experimental custom aTrust server option; normal users never enter this
 * screen and keep the default ZJU endpoint.
 */
class AdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsStore = CustomServerSettingsStore(applicationContext)
        val proxyStore = ProxySettingsStore(applicationContext)
        val stored = settingsStore.read()
        val storedProxy = proxyStore.read()
        setContent {
            ZjuConnectTheme {
                AdvancedSettingsScreen(
                    initialEnabled = stored.enabled,
                    initialHost = stored.host,
                    initialPortText = stored.port.toString(),
                    defaultTarget = CustomServerTarget.DEFAULT,
                    initialProxyEnabled = storedProxy.enabled,
                    initialSocksPortText = storedProxy.socksPort.toString(),
                    initialHttpPortText = storedProxy.httpPort.toString(),
                    onBack = ::finish,
                    onSettingsChanged = { settings ->
                        settingsStore.write(settings)
                    },
                    onProxySettingsChanged = { settings ->
                        proxyStore.write(settings)
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AdvancedSettingsScreen(
    initialEnabled: Boolean,
    initialHost: String,
    initialPortText: String,
    defaultTarget: CustomServerTarget,
    initialProxyEnabled: Boolean,
    initialSocksPortText: String,
    initialHttpPortText: String,
    onBack: () -> Unit,
    onSettingsChanged: (CustomServerSettings) -> Unit,
    onProxySettingsChanged: (ProxySettings) -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var hostText by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf(initialPortText) }
    var proxyEnabled by remember { mutableStateOf(initialProxyEnabled) }
    var socksPortText by remember { mutableStateOf(initialSocksPortText) }
    var httpPortText by remember { mutableStateOf(initialHttpPortText) }

    // Normalize before persisting so the store only ever holds bare hosts.
    val normalizedHost = normalizeCustomServerHost(hostText) ?: ""
    val parsedPort = parseCustomServerPort(portText)
    val hostValid = normalizedHost.isNotEmpty() && isValidCustomServerHost(normalizedHost)
    val portValid = parsedPort != null
    val settings = CustomServerSettings(
        enabled = enabled,
        host = normalizedHost,
        port = parsedPort ?: defaultTarget.port,
    )
    val effectiveTarget = settings.effectiveTarget()

    val socksPort = parseProxyPort(socksPortText)
    val httpPort = parseProxyHttpPort(httpPortText) ?: -1
    val proxySettings = ProxySettings(
        enabled = proxyEnabled,
        socksPort = socksPort ?: ProxySettings.DEFAULT_SOCKS_PORT,
        httpPort = httpPort,
    )
    val proxySettingsValid = proxySettings.isValid()

    // Persist off the main thread: commit() syncs to disk on every keystroke.
    LaunchedEffect(settings) {
        withContext(Dispatchers.IO) {
            onSettingsChanged(settings)
        }
    }
    LaunchedEffect(proxySettings) {
        withContext(Dispatchers.IO) {
            onProxySettingsChanged(proxySettings)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.advanced_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (effectiveTarget != null) {
                    stringResource(
                        R.string.advanced_current_server,
                        effectiveTarget.host,
                        effectiveTarget.port,
                    )
                } else {
                    stringResource(R.string.advanced_server_invalid_notice)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.advanced_enable_custom_server),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.advanced_enable_custom_server_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                    },
                )
            }
            if (enabled) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = hostText,
                    onValueChange = { hostText = it },
                    label = { Text(stringResource(R.string.advanced_server_host_label)) },
                    supportingText = {
                        if (!hostValid) {
                            Text(stringResource(R.string.advanced_invalid_host))
                        }
                    },
                    isError = hostText.isNotEmpty() && !hostValid,
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text(stringResource(R.string.advanced_server_port_label)) },
                    supportingText = {
                        if (!portValid) {
                            Text(stringResource(R.string.advanced_invalid_port))
                        }
                    },
                    isError = portText.isNotEmpty() && !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.advanced_enable_proxy_mode),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.advanced_enable_proxy_mode_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = proxyEnabled,
                    onCheckedChange = { checked ->
                        proxyEnabled = checked
                    },
                )
            }
            if (proxyEnabled) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = socksPortText,
                    onValueChange = { socksPortText = it },
                    label = { Text(stringResource(R.string.advanced_proxy_socks_port_label)) },
                    supportingText = {
                        if (socksPort == null) {
                            Text(stringResource(R.string.advanced_invalid_proxy_port))
                        }
                    },
                    isError = socksPortText.isNotEmpty() && socksPort == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = httpPortText,
                    onValueChange = { httpPortText = it },
                    label = { Text(stringResource(R.string.advanced_proxy_http_port_label)) },
                    supportingText = {
                        if (!proxySettingsValid) {
                            Text(stringResource(R.string.advanced_invalid_proxy_ports))
                        }
                    },
                    isError = httpPortText.isNotEmpty() && !proxySettingsValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
    }
}
