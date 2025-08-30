package name.alfis.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import name.alfis.android.service.AlfisDnsService

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        // Use multiple logging methods to ensure visibility
        Log.i("AlfisBootReceiver", "=== BOOT RECEIVER TRIGGERED ===")
        Log.i("AlfisBootReceiver", "Action: ${intent.action}")
        android.util.Log.println(android.util.Log.WARN, "AlfisBootReceiver", "BootReceiver called: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.PACKAGE_REPLACED" -> {
                Log.i("AlfisBootReceiver", "Processing boot/restart event: ${intent.action}")
                
                // Check if auto-start is enabled in preferences
                val prefs = context.getSharedPreferences("alfis_config", Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("auto_start", false)
                
                Log.i("AlfisBootReceiver", "Auto-start preference: $autoStart")
                android.util.Log.println(android.util.Log.WARN, "AlfisBootReceiver", "Auto-start enabled: $autoStart")
                
                if (autoStart) {
                    Log.i("AlfisBootReceiver", "Auto-start ENABLED - Starting Alfis DNS service on boot")
                    android.util.Log.println(android.util.Log.ERROR, "AlfisBootReceiver", "ATTEMPTING TO START DNS SERVICE ON BOOT")
                    
                    // Add a small delay to ensure system is ready
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val serviceIntent = Intent(context, AlfisDnsService::class.java).apply {
                            action = "START_DNS"
                        }
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                Log.i("AlfisBootReceiver", "Starting foreground service (Android 8+)")
                                android.util.Log.println(android.util.Log.ERROR, "AlfisBootReceiver", "Calling startForegroundService")
                                val componentName = context.startForegroundService(serviceIntent)
                                if (componentName != null) {
                                    Log.i("AlfisBootReceiver", "✓ SUCCESS: Started foreground service: $componentName")
                                    android.util.Log.println(android.util.Log.ERROR, "AlfisBootReceiver", "SUCCESS: Service started: $componentName")
                                } else {
                                    Log.e("AlfisBootReceiver", "✗ FAILED: startForegroundService returned null")
                                    android.util.Log.println(android.util.Log.ERROR, "AlfisBootReceiver", "FAILED: Service start returned null")
                                }
                            } else {
                                Log.i("AlfisBootReceiver", "Starting regular service (Android 7-)")
                                android.util.Log.println(android.util.Log.ERROR, "AlfisBootReceiver", "Calling startService (legacy)")
                                val componentName = context.startService(serviceIntent)
                                if (componentName != null) {
                                    Log.i("AlfisBootReceiver", "✓ SUCCESS: Started service: $componentName")
                                    android.util.Log.println(android.util.Log.ERROR, "AlfisBootReceiver", "SUCCESS: Legacy service started: $componentName")
                                } else {
                                    Log.e("AlfisBootReceiver", "✗ FAILED: startService returned null")
                                    android.util.Log.println(android.util.Log.ERROR, "AlfisBootReceiver", "FAILED: Legacy service start returned null")
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
                    Log.i("AlfisBootReceiver", "Auto-start DISABLED in preferences - not starting service")
                    android.util.Log.println(android.util.Log.WARN, "AlfisBootReceiver", "Auto-start disabled - service not started")
                }
            }
        }
    }
}