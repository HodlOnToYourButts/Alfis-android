package name.alfis.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import name.alfis.android.MainActivity
import name.alfis.android.R
import name.alfis.android.native.AlfisNative
import name.alfis.android.data.AlfisConfig
import java.io.File

class AlfisDnsService : Service() {
    companion object {
        const val CHANNEL_ID = "AlfisServiceChannel"
        const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "Alfis::DnsServiceWakelock"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChange = 0L
    private var currentNetworkType: String = "unknown"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Acquire wake lock to keep service running
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        )
        wakeLock?.acquire(10*60*1000L /*10 minutes initially*/)
        
        // Set up connectivity monitoring
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setupNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_DNS" -> startDnsService()
            "STOP_DNS" -> stopDnsService()
            else -> startDnsService() // Default action
        }
        
        return START_STICKY // Restart service if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDnsService() {
        if (isRunning) return

        val notification = createNotification("Starting Alfis DNS...", false)
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                // Prepare configuration
                val configFile = File(filesDir, "alfis.toml")
                if (!configFile.exists()) {
                    AlfisConfig.createDefaultConfig(this@AlfisDnsService)
                }

                // Initialize native Alfis
                val workDir = File(filesDir, "alfis_work").apply { mkdirs() }
                val logFile = File(filesDir, "alfis.log")
                
                // Start Alfis DNS server
                val success = AlfisNative.startDnsServer(
                    configFile.absolutePath,
                    workDir.absolutePath,
                    logFile.absolutePath
                )

                if (success) {
                    isRunning = true
                    updateNotification("Alfis DNS running on localhost:5353", true)
                } else {
                    updateNotification("Failed to start Alfis DNS", false)
                    stopSelf()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("Error: ${e.message}", false)
                stopSelf()
            }
        }
    }

    private fun stopDnsService() {
        if (!isRunning) return

        serviceScope.launch {
            try {
                AlfisNative.stopDnsServer()
                isRunning = false
                updateNotification("Alfis DNS stopped", false)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alfis DNS Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Alfis DNS resolver service"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String, isRunning: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val actionIntent = Intent(this, AlfisDnsService::class.java).apply {
            action = if (isRunning) "STOP_DNS" else "START_DNS"
        }
        val actionPendingIntent = PendingIntent.getService(
            this, 0, actionIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alfis DNS")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_dns)
            .setContentIntent(pendingIntent)
            .addAction(
                if (isRunning) R.drawable.ic_stop else R.drawable.ic_play,
                if (isRunning) "Stop" else "Start",
                actionPendingIntent
            )
            .setOngoing(isRunning)
            .build()
    }

    private fun updateNotification(text: String, isRunning: Boolean) {
        val notification = createNotification(text, isRunning)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun setupNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                handleNetworkChange("Network available")
            }
            
            override fun onLost(network: Network) {
                super.onLost(network)
                handleNetworkChange("Network lost")
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternet) {
                    // Determine actual network type to detect real changes
                    val newNetworkType = when {
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }
                    
                    // Only trigger if it's actually a different network type
                    if (newNetworkType != currentNetworkType) {
                        val oldType = currentNetworkType
                        currentNetworkType = newNetworkType
                        handleNetworkChange("Network type changed: $oldType -> $newNetworkType")
                    }
                }
            }
        }
        
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
    }
    
    private fun handleNetworkChange(reason: String) {
        val currentTime = System.currentTimeMillis()
        
        // Debounce network changes - only react if it's been at least 5 seconds since last change  
        if (currentTime - lastNetworkChange < 5000) {
            return
        }
        lastNetworkChange = currentTime
        
        if (isRunning) {
            serviceScope.launch {
                delay(1000) // Give the network a moment to stabilize
                
                // Signal the native layer to reconnect
                try {
                    AlfisNative.triggerNetworkReconnect()
                } catch (e: Exception) {
                    // Network reconnect failed
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        
        // Unregister network callback
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Failed to unregister network callback
            }
        }
        
        if (isRunning) {
            AlfisNative.stopDnsServer()
        }
        
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}