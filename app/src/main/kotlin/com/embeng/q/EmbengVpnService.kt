package com.embeng.q

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import usqueandroid.Usqueandroid
import usqueandroid.VpnStateCallback
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class EmbengVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.embeng.q.STOP_VPN"
        private const val TAG = "EmbengVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "embengq_vpn"
        @Volatile private var activeService: EmbengVpnService? = null
        // The native core is a singleton. Keep cleanup and startup serialized even
        // when Android destroys one Service instance and creates its replacement.
        private val tunnelWorker = ScheduledThreadPoolExecutor(1)

        fun stopActiveTunnel() { activeService?.requestStop() }
    }

    private data class Session(
        val configPath: String, val sni: String, val endpoint: String,
        val splitMode: Boolean, val allowedApps: List<String>
    )

    // All native start/stop calls and TUN ownership changes run on this one worker.
    private val executor get() = tunnelWorker
    private val desiredRunning = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private var tun: ParcelFileDescriptor? = null
    private var session: Session? = null
    private var retryCount = 0
    private val sessionPrefs by lazy { getSharedPreferences("vpn_session", MODE_PRIVATE) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeService = this
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return Service.START_NOT_STICKY
        }
        if (TunnelState.snapshot.phase == TunnelPhase.STOPPING) return Service.START_NOT_STICKY
        if (!desiredRunning.compareAndSet(false, true)) return Service.START_STICKY
        val token = generation.incrementAndGet()
        retryCount = 0
        try {
            publish(TunnelSnapshot(TunnelPhase.CONNECTING))
        } catch (e: Exception) {
            desiredRunning.set(false)
            TunnelState.publish(TunnelSnapshot(detail = e.message.orEmpty()))
            stopSelf()
            return Service.START_NOT_STICKY
        }
        val restored = readSession(intent)
        session = restored
        executor.execute {
            if (!isCurrent(token)) return@execute
            try {
                if (prepare(this) != null || !Usqueandroid.isRegistered(restored.configPath)) {
                    requestStop("VPN permission or WARP account is unavailable")
                    return@execute
                }
                if (!saveSession(restored)) {
                    requestStop("Cannot save VPN session")
                    return@execute
                }
                startNativeTunnel(restored, token)
            } catch (e: Exception) {
                requestStop(e.message.orEmpty())
            }
        }
        return Service.START_STICKY
    }

    private fun readSession(intent: Intent?): Session {
        val saved = if (sessionPrefs.contains("sni")) sessionPrefs else getSharedPreferences("embengq", MODE_PRIVATE)
        return Session(
            intent?.getStringExtra("configPath") ?: File(filesDir, "config.json").absolutePath,
            intent?.getStringExtra("sni") ?: saved.getString("sni", "classroom.google.com").orEmpty(),
            intent?.getStringExtra("endpoint") ?: saved.getString("endpoint", "162.159.198.2:443").orEmpty(),
            if (intent?.hasExtra("splitMode") == true) intent.getBooleanExtra("splitMode", false) else saved.getBoolean("splitMode", false),
            intent?.getStringArrayListExtra("allowedApps")?.toList()
                ?: saved.getStringSet("selectedPackages", emptySet()).orEmpty().toList()
        )
    }

    private fun saveSession(value: Session): Boolean = sessionPrefs.edit()
        .putString("sni", value.sni).putString("endpoint", value.endpoint)
        .putBoolean("splitMode", value.splitMode)
        .putStringSet("selectedPackages", value.allowedApps.toSet()).commit()

    private fun isCurrent(token: Long) = desiredRunning.get() && generation.get() == token

    private fun startNativeTunnel(value: Session, token: Long) {
        if (!isCurrent(token)) return
        try {
            Usqueandroid.resetConnectionOptions()
            Usqueandroid.setSNI(value.sni)
            Usqueandroid.setEndpoint(value.endpoint)
            val ipv4 = Usqueandroid.getAssignedIPv4(value.configPath).ifBlank { "172.16.0.2" }
            val builder = Builder().setSession("Embeng-Q VPN").setMtu(1280)
                .addAddress(ipv4, 32).addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
                .addRoute("0.0.0.0", 0)
            if (value.splitMode) {
                var count = 0
                value.allowedApps.distinct().filter { it != packageName }.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                        count++
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Selected app is no longer installed: $pkg")
                    }
                }
                check(count > 0) { "Select at least one installed app before connecting" }
            } else builder.addDisallowedApplication(packageName)

            val ipv6 = Usqueandroid.getAssignedIPv6(value.configPath)
            if (ipv6.isNotBlank()) {
                builder.addAddress(ipv6, 128).addRoute("::", 0)
                    .addDnsServer("2606:4700:4700::1111").addDnsServer("2606:4700:4700::1001")
            }
            tun = builder.establish() ?: error("VPN permission was revoked")
            val interfaceName = runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { network ->
                    Collections.list(network.inetAddresses).any { it.hostAddress == ipv4 }
                }?.name
            }.getOrNull()
            if (!isCurrent(token)) { closeTunnel(); return }

            // The native AndroidTunDevice wraps this fd in os.File and closes it.
            // Java owns it only until detachFd(); never close the integer again here.
            val fd = checkNotNull(tun).detachFd()
            tun = null
            val error = Usqueandroid.startTunnelWithFd(value.configPath, fd.toLong(), object : VpnStateCallback {
                override fun onConnected() = dispatch {
                    if (isCurrent(token)) {
                        retryCount = 0
                        publish(TunnelSnapshot(TunnelPhase.CONNECTED, interfaceName = interfaceName))
                    }
                }
                override fun onDisconnected(reason: String?) = dispatch { failed(token, reason.orEmpty()) }
                override fun onError(message: String?) = dispatch { failed(token, message.orEmpty()) }
            })
            if (!error.isNullOrBlank()) failed(token, error)
        } catch (e: Exception) {
            failed(token, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (!executor.isShutdown) runCatching { executor.execute(action) }
    }

    private fun failed(token: Long, reason: String) {
        if (!isCurrent(token)) return
        // Invalidate callbacks from this attempt before stopping its native tunnel.
        val next = generation.incrementAndGet()
        closeTunnel()
        if (!desiredRunning.get()) return
        Log.w(TAG, "Tunnel failed: $reason")
        val value = session ?: return
        publish(TunnelSnapshot(TunnelPhase.RECONNECTING, reason))
        val delay = (3L shl retryCount.coerceAtMost(3)).coerceAtMost(30)
        retryCount++
        executor.schedule({ if (isCurrent(next)) startNativeTunnel(value, next) }, delay, TimeUnit.SECONDS)
    }

    private fun requestStop(reason: String = "") {
        if (!desiredRunning.getAndSet(false) && TunnelState.snapshot.phase == TunnelPhase.STOPPING) return
        generation.incrementAndGet()
        TunnelState.publish(TunnelSnapshot(TunnelPhase.STOPPING))
        dispatch {
            closeTunnel()
            TunnelState.publish(TunnelSnapshot(detail = reason))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun closeTunnel() {
        runCatching { Usqueandroid.stopTunnel() }.onFailure { Log.w(TAG, "Native stop failed", it) }
        runCatching { tun?.close() }.onFailure { Log.w(TAG, "TUN close failed", it) }
        tun = null
    }

    private fun publish(value: TunnelSnapshot) {
        if (!desiredRunning.get()) return
        TunnelState.publish(value)
        val prefs = getSharedPreferences("embengq", MODE_PRIVATE)
        val en = prefs.getBoolean("useEnglish", false)
        val text = when (value.phase) {
            TunnelPhase.CONNECTED -> if (en) "Connected" else "Terhubung"
            TunnelPhase.RECONNECTING -> if (en) "Reconnecting" else "Menyambung ulang"
            else -> if (en) "Connecting" else "Menghubungkan"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Embeng-Q VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EmbengVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setContentTitle("Embeng-Q VPN").setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (en) "Disconnect" else "Putuskan", stop).build())
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onRevoke() { requestStop("VPN permission revoked"); super.onRevoke() }

    override fun onDestroy() {
        desiredRunning.set(false)
        generation.incrementAndGet()
        executor.execute {
            closeTunnel()
            if (activeService === this) {
                activeService = null
                TunnelState.publish(TunnelSnapshot())
            }
        }
        super.onDestroy()
    }

}
