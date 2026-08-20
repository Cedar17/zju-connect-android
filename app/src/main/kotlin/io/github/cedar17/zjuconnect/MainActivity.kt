package io.github.cedar17.zjuconnect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme

private const val MAIN_ACTIVITY_LOG_TAG = "ZjuConnectMain"
private const val NOTIFICATION_PREFERENCES = "notification_preferences"
private const val NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
private val CONNECTION_STATUS_CARD_HEIGHT = 96.dp

class MainActivity : ComponentActivity() {
    private val connectionViewModel: ConnectionViewModel by viewModels()
    private val notificationPreferences by lazy {
        getSharedPreferences(NOTIFICATION_PREFERENCES, MODE_PRIVATE)
    }
    private var pendingVpnStart: ConnectionEffect.StartVpnService? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            connectionViewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val pendingStart = pendingVpnStart
            pendingVpnStart = null
            pendingStart?.let(::startVpnService)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by connectionViewModel.state.collectAsState()
            LaunchedEffect(connectionViewModel) {
                connectionViewModel.effects.collect(::handleConnectionEffect)
            }
            ZjuConnectTheme {
                ZjuConnectApp(
                    state = state,
                    viewModel = connectionViewModel,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RealVpnService.refreshNotificationIfRunning()
    }

    private fun dispatchStartVpnService(effect: ConnectionEffect.StartVpnService) {
        if (!connectionViewModel.canHandleEffect(effect)) return
        if (shouldRequestNotificationPermission()) {
            pendingVpnStart = effect
            notificationPreferences.edit()
                .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
                .apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVpnService(effect)
        }
    }

    private fun shouldRequestNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationPreferences.getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)

    private fun startVpnService(effect: ConnectionEffect.StartVpnService) {
        if (!connectionViewModel.canHandleEffect(effect)) return
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RealVpnService::class.java)
                    .setAction(RealVpnService.ACTION_START)
                    .putExtra(
                        REAL_VPN_EXTRA_START_SOURCE,
                        REAL_VPN_START_SOURCE_MANUAL,
                    ),
            )
        }.onSuccess {
            connectionViewModel.onVpnServiceStartDispatched(effect)
        }.onFailure { error ->
            Log.e(MAIN_ACTIVITY_LOG_TAG, "Unable to start VPN service", error)
            connectionViewModel.onVpnServiceDispatchFailed(effect)
        }
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
                dispatchStartVpnService(effect)
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
    val darkTheme = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF9FC9FF),
            secondary = Color(0xFFBBC7DB),
            tertiary = Color(0xFFD9BDE9),
        )
    } else {
        lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ZjuConnectApp(
    state: ConnectionUiState,
    viewModel: ConnectionViewModel,
) {
    val context = LocalContext.current
    val menuDescription = stringResource(R.string.menu_overflow)
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var switchDialogVisible by rememberSaveable { mutableStateOf(false) }

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
                if (usesScrollableHomeLayout(state.phase)) {
                    ConnectionHomeContent(
                        state = state,
                        viewModel = viewModel,
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
                        modifier = contentModifier,
                        centered = true,
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 4.dp),
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = menuDescription
                        },
                    ) {
                        Text(
                            text = "⋮",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_diagnostics)) },
                            onClick = {
                                menuExpanded = false
                                context.startActivity(Intent(context, DiagnosticsActivity::class.java))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            onClick = {
                                menuExpanded = false
                                context.startActivity(Intent(context, AboutActivity::class.java))
                            },
                        )
                        if (canSwitchAccount(state)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_switch_account)) },
                                onClick = {
                                    menuExpanded = false
                                    switchDialogVisible = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (switchDialogVisible && canSwitchAccount(state)) {
        AlertDialog(
            onDismissRequest = { switchDialogVisible = false },
            title = { Text(stringResource(R.string.switch_account_title)) },
            text = { Text(stringResource(R.string.switch_account_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        switchDialogVisible = false
                        viewModel.switchAccount()
                    },
                ) {
                    Text(stringResource(R.string.switch_account_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { switchDialogVisible = false }) {
                    Text(stringResource(R.string.switch_account_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectionHomeContent(
    state: ConnectionUiState,
    viewModel: ConnectionViewModel,
    modifier: Modifier,
    centered: Boolean,
) {
    val presentation = connectionPresentation(state)
    val supportingText = presentation.supportingText.resolve()
    val progressDescription = stringResource(R.string.connection_progress)
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
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(CONNECTION_STATUS_CARD_HEIGHT),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = presentation.title.resolve(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = supportingText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = supportingText },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.phase == ConnectionPhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        AuthenticationStep(state, viewModel)

        Button(
            onClick = viewModel::onPrimaryAction,
            enabled = presentation.primaryActionEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            when (val primaryAction = presentation.primaryAction) {
                null -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .semantics {
                            contentDescription = progressDescription
                        },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                else -> Text(primaryAction.resolve())
            }
        }
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
                    label = { Text(stringResource(R.string.field_account)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    label = { Text(stringResource(R.string.field_password)) },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                text = stringResource(
                                    if (passwordVisible) R.string.password_hide else R.string.password_show,
                                ),
                                style = MaterialTheme.typography.labelLarge,
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
            label = { Text(stringResource(R.string.field_phone)) },
            singleLine = true,
        )
        ConnectionPhase.AWAITING_SMS -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.phoneNumbers.firstOrNull()?.let { maskedPhone ->
                    Text(
                        text = stringResource(R.string.sms_sent_to, maskedPhone),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.smsCode,
                    onValueChange = viewModel::updateSmsCode,
                    label = { Text(stringResource(R.string.field_sms_code)) },
                    singleLine = true,
                )
            }
        }
        ConnectionPhase.AWAITING_TOKEN -> OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.token,
            onValueChange = viewModel::updateToken,
            label = { Text(tokenChallengeUiText(state.challengeKind).resolve()) },
            singleLine = true,
        )
        ConnectionPhase.AWAITING_CAPTCHA -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CaptchaChallenge(state, viewModel)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.captcha_selected_count, state.captchaPoints.size))
                    TextButton(onClick = viewModel::clearCaptchaPoints) {
                        Text(stringResource(R.string.captcha_reset))
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
        Text(stringResource(R.string.captcha_image_unavailable), color = MaterialTheme.colorScheme.error)
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
            contentDescription = stringResource(R.string.captcha_image_description),
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
