package name.alfis.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AlfisConfig {
    
    suspend fun createDefaultConfig(context: Context) = withContext(Dispatchers.IO) {
        val configFile = File(context.filesDir, "alfis.toml")
        
        val defaultConfig = """
# Alfis Android Configuration
# The hash of first block in a chain to know with which nodes to work
origin = "0000001D2A77D63477172678502E51DE7F346061FF7EB188A2445ECA3FC0780E"
# Key files (empty for mobile)
key_files = []
# Reduced block checking for mobile
check_blocks = 4

# Network settings
[net]
# Bootstrap nodes
peers = ["peer-v4.alfis.name:4244", "peer-v6.alfis.name:4244"]
# Listen address (Android may restrict this)
listen = "127.0.0.1:42440"
# Mobile devices shouldn't be public peers
public = false
yggdrasil_only = true

# DNS resolver options
[dns]
# Listen on localhost IPv6 (non-privileged port)
listen = "[::1]:5353"
# Increased threads for better performance
threads = 8
# Use DoH when available, fallback to regular DNS
forwarders = ["https://dns.adguard.com/dns-query", "8.8.8.8:53"]
bootstraps = ["8.8.8.8:53", "1.1.1.1:53"]

# Mining disabled on mobile
[mining]
threads = 0
lower = true
        """.trimIndent()
        
        configFile.writeText(defaultConfig)
    }
    
    suspend fun loadConfig(context: Context): String? = withContext(Dispatchers.IO) {
        val configFile = File(context.filesDir, "alfis.toml")
        if (configFile.exists()) {
            configFile.readText()
        } else {
            null
        }
    }
    
    suspend fun saveConfig(context: Context, config: String) = withContext(Dispatchers.IO) {
        val configFile = File(context.filesDir, "alfis.toml")
        configFile.writeText(config)
    }
}