package name.alfis.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import name.alfis.android.service.AlfisDnsService

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.PACKAGE_REPLACED" -> {
                Log.d("AlfisBootReceiver", "Boot completed or package replaced")
                
                // Check if auto-start is enabled in preferences
                val prefs = context.getSharedPreferences("alfis_config", Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("auto_start", false)
                
                if (autoStart) {
                    Log.d("AlfisBootReceiver", "Starting Alfis DNS service on boot")
                    val serviceIntent = Intent(context, AlfisDnsService::class.java).apply {
                        action = "START_DNS"
                    }
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("AlfisBootReceiver", "Failed to start service on boot: ${e.message}")
                    }
                } else {
                    Log.d("AlfisBootReceiver", "Auto-start disabled")
                }
            }
        }
    }
}