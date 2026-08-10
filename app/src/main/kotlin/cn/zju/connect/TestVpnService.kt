package cn.zju.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.zju.connect.gocore.core.SocketProtector
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TEST_VPN_CHANNEL = "zju_connect_test_vpn"
private const val TEST_VPN_NOTIFICATION_ID = 1001
private const val TEST_VPN_ADDRESS = "192.168.255.2"
private const val TEST_VPN_DESTINATION = "192.168.255.1"
private const val TEST_VPN_PORT = 34890
private const val TEST_VPN_MTU = 1400
private const val TEST_MARKER = "zju-connect-tun-test-v1"
private const val MARKER_ATTEMPTS = 3
private const val MARKER_TIMEOUT_MS = 1000
private const val MARKER_RETRY_DELAY_MS = 100L

class TestVpnService : VpnService() {
    companion object {
        const val ACTION_START = "cn.zju.connect.action.START_TEST_VPN"
        const val ACTION_STOP = "cn.zju.connect.action.STOP_TEST_VPN"
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val goCoreBridge = GoCoreBridge()
    private val stateLock = Any()

    @Volatile
    private var sessionStarted = false

    @Volatile
    private var markerSocket: DatagramSocket? = null

    @Volatile
    private var lastDataPlaneError: Pair<String, String>? = null

    private val socketProtector = object : SocketProtector {
        override fun protect(socketFd: Long): Boolean = this@TestVpnService.protect(socketFd.toInt())
    }

    private val goListener: (GoTestVpnEvent) -> Unit = { event ->
        TestVpnStateStore.update(event)
        when (event.state) {
            "socketProtected" -> sendMarkerPacket()
            "error" -> {
                lastDataPlaneError = event.code to event.message
                requestStop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> requestStart()
            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopMarkerSocket()
        synchronized(stateLock) {
            if (sessionStarted) {
                goCoreBridge.stopTestDataPlane()
                sessionStarted = false
            }
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun requestStart() {
        executor.execute { startInternal() }
    }

    private fun requestStop() {
        executor.execute { stopInternal() }
    }

    private fun startInternal() {
        var detachedTunFd: Int? = null
        synchronized(stateLock) {
            if (sessionStarted) {
                return
            }
            sessionStarted = true
        }
        lastDataPlaneError = null

        TestVpnStateStore.setStatus("starting", "Starting Android test VPN")
        try {
            startForegroundCompat()
            val tun = Builder()
                .setSession("ZJU Connect test")
                .setMtu(TEST_VPN_MTU)
                .setBlocking(true)
                .addAddress(TEST_VPN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addAllowedApplication(packageName)
                .establish()
                ?: error("Android refused to establish the VPN interface")

            val tunFd = tun.detachFd()
            detachedTunFd = tunFd
            tun.close()
            goCoreBridge.startTestDataPlane(tunFd.toLong(), socketProtector, goListener)
            detachedTunFd = null
        } catch (error: Throwable) {
            detachedTunFd?.let { fd ->
                runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
            }
            TestVpnStateStore.setError(
                code = "vpnStartFailed",
                message = error.message ?: "Unable to start Android test VPN",
            )
            synchronized(stateLock) {
                sessionStarted = false
            }
            lastDataPlaneError = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopInternal() {
        stopMarkerSocket()
        val shouldStop = synchronized(stateLock) {
            if (!sessionStarted) {
                false
            } else {
                TestVpnStateStore.setStatus("stopping", "Stopping Android test VPN")
                goCoreBridge.stopTestDataPlane()
                sessionStarted = false
                true
            }
        }
        if (!shouldStop) {
            stopSelf()
            return
        }
        stopForegroundCompat()
        stopSelf()
        lastDataPlaneError?.let { (code, message) ->
            TestVpnStateStore.setError(code, message)
        }
    }

    private fun sendMarkerPacket() {
        executor.execute {
            val marker = TEST_MARKER.toByteArray(StandardCharsets.UTF_8)
            val socket = try {
                DatagramSocket().also { markerSocket = it }
            } catch (error: Throwable) {
                TestVpnStateStore.setError("markerSocketFailed", error.message ?: "Unable to create marker socket")
                requestStop()
                return@execute
            }

            try {
                val destination = InetAddress.getByName(TEST_VPN_DESTINATION)
                var lastError: Throwable? = null
                repeat(MARKER_ATTEMPTS) { attempt ->
                    try {
                        socket.soTimeout = MARKER_TIMEOUT_MS
                        socket.send(
                            DatagramPacket(
                                marker,
                                marker.size,
                                destination,
                                TEST_VPN_PORT,
                            ),
                        )

                        val responseBytes = ByteArray(TEST_VPN_MTU)
                        val response = DatagramPacket(responseBytes, responseBytes.size)
                        socket.receive(response)
                        val returned = String(
                            response.data,
                            response.offset,
                            response.length,
                            StandardCharsets.UTF_8,
                        )
                        if (returned != TEST_MARKER) {
                            error("Marker response did not match")
                        }
                        TestVpnStateStore.markRoundTripVerified()
                        return@execute
                    } catch (error: SocketTimeoutException) {
                        lastError = error
                        if (attempt + 1 < MARKER_ATTEMPTS) {
                            Thread.sleep(MARKER_RETRY_DELAY_MS)
                        }
                    } catch (error: Throwable) {
                        lastError = error
                        if (attempt + 1 < MARKER_ATTEMPTS) {
                            Thread.sleep(MARKER_RETRY_DELAY_MS)
                        }
                    }
                }
                throw lastError ?: IllegalStateException("Marker packet did not return")
            } catch (error: Throwable) {
                TestVpnStateStore.setError(
                    code = "markerRoundTripFailed",
                    message = error.message ?: "Marker packet did not return",
                )
                requestStop()
            } finally {
                if (markerSocket === socket) {
                    markerSocket = null
                }
                socket.close()
            }
        }
    }

    private fun stopMarkerSocket() {
        markerSocket?.close()
        markerSocket = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TEST_VPN_CHANNEL,
                "ZJU Connect test VPN",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = Notification.Builder(this, TEST_VPN_CHANNEL)
            .setContentTitle("ZJU Connect test VPN")
            .setContentText("Synthetic TUN data plane is active")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TEST_VPN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(TEST_VPN_NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}

data class TestVpnUiState(
    val state: String = "idle",
    val code: String = "",
    val message: String = "Test VPN is idle",
    val packetsFromTun: Long = 0,
    val packetsToTun: Long = 0,
    val bytesFromTun: Long = 0,
    val bytesToTun: Long = 0,
)

object TestVpnStateStore {
    var state by mutableStateOf(TestVpnUiState())
        private set

    @Synchronized
    fun update(event: GoTestVpnEvent) {
        state = state.copy(
            state = event.state,
            code = event.code,
            message = event.message,
            packetsFromTun = event.packetsFromTun,
            packetsToTun = event.packetsToTun,
            bytesFromTun = event.bytesFromTun,
            bytesToTun = event.bytesToTun,
        )
    }

    @Synchronized
    fun setStatus(nextState: String, message: String) {
        state = state.copy(state = nextState, code = "", message = message)
    }

    @Synchronized
    fun setError(code: String, message: String) {
        state = state.copy(state = "error", code = code, message = message)
    }

    @Synchronized
    fun markRoundTripVerified() {
        state = state.copy(
            state = "roundTripVerified",
            code = "",
            message = "Marker packet returned through TUN and Go",
        )
    }

    @Synchronized
    fun reset() {
        state = TestVpnUiState()
    }
}
