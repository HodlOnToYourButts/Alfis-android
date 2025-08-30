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
                    Log.d("AlfisBootReceiver", "Auto-start enabled, starting Alfis DNS service on boot")
                    
                    // Add a small delay to ensure system is ready
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val serviceIntent = Intent(context, AlfisDnsService::class.java).apply {
                            action = "START_DNS"
                        }
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                Log.d("AlfisBootReceiver", "Starting foreground service (Android 8+)")
                                val componentName = context.startForegroundService(serviceIntent)
                                if (componentName != null) {
                                    Log.d("AlfisBootReceiver", "Successfully started foreground service: $componentName")
                                } else {
                                    Log.w("AlfisBootReceiver", "startForegroundService returned null")
                                }
                            } else {
                                Log.d("AlfisBootReceiver", "Starting regular service (Android 7-)")
                                val componentName = context.startService(serviceIntent)
                                if (componentName != null) {
                                    Log.d("AlfisBootReceiver", "Successfully started service: $componentName")
                                } else {
                                    Log.w("AlfisBootReceiver", "startService returned null")
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e("AlfisBootReceiver", "SecurityException starting service on boot: ${e.message}")
                            Log.e("AlfisBootReceiver", "This may be due to battery optimization. Please whitelist the app in battery settings.")
                        } catch (e: IllegalStateException) {
                            Log.e("AlfisBootReceiver", "IllegalStateException starting service on boot: ${e.message}")
                            Log.e("AlfisBootReceiver", "This may be due to background restrictions on Android 8+")
                        } catch (e: Exception) {
                            Log.e("AlfisBootReceiver", "Failed to start service on boot: ${e.message}")
                            Log.e("AlfisBootReceiver", "Exception type: ${e.javaClass.simpleName}")
                        }
                    }, 2000) // 2 second delay to let system settle
                } else {
                    Log.d("AlfisBootReceiver", "Auto-start disabled in preferences")
                }
            }
        }
    }
}