package name.alfis.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
// Material icons imports removed - using text buttons instead
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.alfis.android.service.AlfisDnsService
import name.alfis.android.native.AlfisNative
import name.alfis.android.ui.theme.AlfisTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlfisTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AlfisMainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlfisMainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(false) }
    var isServiceStarting by remember { mutableStateOf(false) }
    var dnsStats by remember { mutableStateOf("") }
    var consoleOutput by remember { mutableStateOf("") }
    var showConfig by remember { mutableStateOf(false) }
    var lastServiceToggleTime by remember { mutableStateOf(0L) }
    var currentListenAddress by remember { mutableStateOf("[::1]:5353") }

    // Auto-start service state
    var hasAutoStarted by remember { mutableStateOf(false) }
    
    // Battery optimization state
    var batteryOptimizationIgnored by remember { mutableStateOf(true) }
    
    // Check battery optimization status and load listen address
    LaunchedEffect(Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        
        // Load current listen address from preferences
        val prefs = context.getSharedPreferences("alfis_config", android.content.Context.MODE_PRIVATE)
        currentListenAddress = prefs.getString("listen_address", "[::1]:5353") ?: "[::1]:5353"
    }
    
    // Refresh battery optimization status when app becomes visible
    androidx.compose.runtime.DisposableEffect(Unit) {
        val lifecycleOwner = context as LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Auto-start DNS service once and check status periodically
    LaunchedEffect(Unit) {
        // Auto-start DNS service when app opens (only once)
        if (!hasAutoStarted && !AlfisNative.isDnsServerRunning()) {
            // Use the same Android Service path as manual buttons
            val intent = Intent(context, AlfisDnsService::class.java).apply {
                action = "START_DNS"
            }
            context.startService(intent)
            isServiceStarting = true
            hasAutoStarted = true
        }
        
        // Periodic status updates
        while (true) {
            val previouslyRunning = isServiceRunning
            isServiceRunning = AlfisNative.isDnsServerRunning()
            
            // Clear starting state once service is running
            if (isServiceRunning && isServiceStarting) {
                isServiceStarting = false
            }
            
            // Update listen address when service state changes
            if (isServiceRunning != previouslyRunning) {
                val prefs = context.getSharedPreferences("alfis_config", android.content.Context.MODE_PRIVATE)
                currentListenAddress = prefs.getString("listen_address", "[::1]:5353") ?: "[::1]:5353"
            }
            
            if (isServiceRunning) {
                dnsStats = AlfisNative.getDnsStats()
                consoleOutput = AlfisNative.getConsoleOutput()
                
                // If service just started, force refresh after a delay
                if (!previouslyRunning && System.currentTimeMillis() - lastServiceToggleTime < 5000) {
                    delay(1000) // Give the service time to initialize
                    dnsStats = AlfisNative.getDnsStats()
                }
            } else {
                // Clear stats when service stops
                if (previouslyRunning) {
                    dnsStats = ""
                    consoleOutput = ""
                    isServiceStarting = false
                }
            }
            delay(2000) // Update every 2 seconds
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "Alternative Free Identity System",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 24.dp)
        )

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            isServiceRunning -> "Running"
                            isServiceStarting -> "Starting..."
                            else -> "Stopped"
                        },
                        color = when {
                            isServiceRunning -> MaterialTheme.colorScheme.primary
                            isServiceStarting -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Medium
                    )
                    
                    if (isServiceRunning) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentListenAddress,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    lastServiceToggleTime = System.currentTimeMillis()
                    if (isServiceRunning) {
                        val intent = Intent(context, AlfisDnsService::class.java).apply {
                            action = "STOP_DNS"
                        }
                        context.startService(intent)
                    } else if (!isServiceStarting) {
                        isServiceStarting = true
                        val intent = Intent(context, AlfisDnsService::class.java).apply {
                            action = "START_DNS"
                        }
                        context.startService(intent)
                    }
                },
                enabled = !isServiceStarting,
                modifier = Modifier.weight(1f)
            ) {
                Text(when {
                    isServiceRunning -> "Stop"
                    isServiceStarting -> "Starting..."
                    else -> "Start"
                })
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            OutlinedButton(
                onClick = { showConfig = !showConfig },
                modifier = Modifier.weight(1f)
            ) {
                Text("Config")
            }
        }

        // Battery optimization warning
        if (!batteryOptimizationIgnored && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Battery Optimization Warning",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "Background activity is restricted. This may cause network disconnections when screen sleeps and slow sync performance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text(
                            text = "Allow Unrestricted Background",
                            color = MaterialTheme.colorScheme.errorContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Configuration Section
        if (showConfig) {
            ConfigurationSection(
                onListenAddressChanged = { newAddress -> 
                    currentListenAddress = newAddress 
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Console Section
        if (isServiceRunning && consoleOutput.isNotEmpty()) {
            ConsoleSection(consoleOutput = consoleOutput)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Statistics Section
        if (isServiceRunning && dnsStats.isNotEmpty()) {
            StatisticsSection(dnsStats = dnsStats)
        }
        
        // Debug boot auto-start section (only show if auto-start is enabled)
        if (showConfig) {
            Spacer(modifier = Modifier.height(16.dp))
            BootAutoStartDebugSection()
        }

    }
}

@Composable
fun ConfigurationSection(onListenAddressChanged: (String) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("alfis_config", android.content.Context.MODE_PRIVATE)
    
    // Default configuration values
    val defaultListenAddress = "[::1]:5353"
    val defaultForwarders = "https://dns.adguard.com/dns-query\n8.8.8.8:53"
    val defaultPeers = "peer-v4.alfis.name:4244\npeer-v6.alfis.name:4244"
    val defaultBootstraps = "94.140.14.14:53\n94.140.15.15:53\n9.9.9.9:53\n149.112.112.112:53\n[2a10:50c0::ad1:ff]:53\n[2a10:50c0::ad2:ff]:53\n[2620:fe::fe]:53\n[2620:fe::9]:53"
    val defaultYggdrasilOnly = true
    val defaultAutoStart = false
    
    // State for editable configuration (load from preferences)
    var listenAddress by remember { 
        mutableStateOf(prefs.getString("listen_address", defaultListenAddress) ?: defaultListenAddress) 
    }
    var forwarders by remember { 
        mutableStateOf(prefs.getString("forwarders", defaultForwarders) ?: defaultForwarders) 
    }
    var peers by remember { 
        mutableStateOf(prefs.getString("peers", defaultPeers) ?: defaultPeers) 
    }
    var bootstraps by remember { 
        mutableStateOf(prefs.getString("bootstraps", defaultBootstraps) ?: defaultBootstraps) 
    }
    var yggdrasilOnly by remember { 
        mutableStateOf(prefs.getBoolean("yggdrasil_only", defaultYggdrasilOnly)) 
    }
    var autoStart by remember { 
        mutableStateOf(prefs.getBoolean("auto_start", defaultAutoStart)) 
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Configuration",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Configuration fields in dependency order
            ConfigField(
                label = "Listen Address",
                value = listenAddress,
                onValueChange = { listenAddress = it },
                placeholder = "IP:Port",
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            ConfigField(
                label = "Bootstraps",
                value = bootstraps,
                onValueChange = { bootstraps = it },
                placeholder = "Bootstrap DNS servers (one per line)"
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            ConfigField(
                label = "Forwarders",
                value = forwarders,
                onValueChange = { forwarders = it },
                placeholder = "DNS forwarders (one per line)"
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            ConfigField(
                label = "Peers",
                value = peers,
                onValueChange = { peers = it },
                placeholder = "Alfis peers (one per line)"
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Yggdrasil Only toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Yggdrasil Only",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Connect only to Yggdrasil network peers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = yggdrasilOnly,
                    onCheckedChange = { yggdrasilOnly = it }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Auto Start toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Auto Start on Boot",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Automatically start Alfis DNS when device boots",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoStart,
                    onCheckedChange = { autoStart = it }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = {
                        // Reset to default values
                        listenAddress = defaultListenAddress
                        forwarders = defaultForwarders
                        peers = defaultPeers
                        bootstraps = defaultBootstraps
                        yggdrasilOnly = defaultYggdrasilOnly
                        autoStart = defaultAutoStart
                        
                        // Show confirmation toast
                        android.widget.Toast.makeText(
                            context,
                            "Configuration reset to defaults",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset")
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        // Save configuration to SharedPreferences
                        prefs.edit().apply {
                            putString("listen_address", listenAddress)
                            putString("forwarders", forwarders)
                            putString("peers", peers)
                            putString("bootstraps", bootstraps)
                            putBoolean("yggdrasil_only", yggdrasilOnly)
                            putBoolean("auto_start", autoStart)
                            apply()
                        }
                        
                        // Create new config file with updated settings
                        val configContent = """
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
peers = [${peers.split('\n').filter { it.isNotBlank() }.joinToString(", ") { "\"${it.trim()}\"" }}]
# Listen address (Android may restrict this)
listen = "127.0.0.1:42440"
# Mobile devices shouldn't be public peers
public = false
yggdrasil_only = $yggdrasilOnly

# DNS resolver options
[dns]
# Listen address from UI configuration
listen = "$listenAddress"
# Increased threads for better performance
threads = 8
# Use DoH when available, fallback to regular DNS
forwarders = [${forwarders.split('\n').filter { it.isNotBlank() }.joinToString(", ") { "\"${it.trim()}\"" }}]
bootstraps = [${bootstraps.split('\n').filter { it.isNotBlank() }.joinToString(", ") { "\"${it.trim()}\"" }}]

# Mining disabled on mobile
[mining]
threads = 0
lower = true
                        """.trimIndent()
                        
                        // Write the config file
                        try {
                            val configFile = java.io.File(context.filesDir, "alfis.toml")
                            configFile.writeText(configContent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "Error saving config: ${e.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@Button
                        }
                        
                        // Show confirmation toast
                        android.widget.Toast.makeText(
                            context,
                            "Configuration saved successfully",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
fun StatisticsSection(dnsStats: String) {
    // Parse JSON stats for better display
    val stats = parseStatsJson(dnsStats)
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard(
                    title = "Blocks",
                    value = stats.blocks.toString(),
                    icon = "⛓️",
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                StatCard(
                    title = "Peers",
                    value = stats.peers.toString(),
                    icon = "🌐",
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard(
                    title = "Queries",
                    value = stats.queries.toString(),
                    icon = "❓",
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                StatCard(
                    title = "Responses",
                    value = stats.responses.toString(),
                    icon = "✅",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = icon,
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class DnsStats(
    val blocks: Int,
    val peers: Int,
    val queries: Int,
    val responses: Int
)

fun parseStatsJson(jsonString: String): DnsStats {
    // Simple JSON parsing for the expected format: {"blocks": 123, "peers": 5, "queries": 42, "responses": 42}
    return try {
        val blocksRegex = """"blocks":\s*(\d+)""".toRegex()
        val peersRegex = """"peers":\s*(\d+)""".toRegex()
        val queriesRegex = """"queries":\s*(\d+)""".toRegex()
        val responsesRegex = """"responses":\s*(\d+)""".toRegex()
        
        val blocks = blocksRegex.find(jsonString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val peers = peersRegex.find(jsonString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val queries = queriesRegex.find(jsonString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val responses = responsesRegex.find(jsonString)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        DnsStats(blocks, peers, queries, responses)
    } catch (e: Exception) {
        DnsStats(0, 0, 0, 0)
    }
}

@Composable
fun ConsoleSection(consoleOutput: String) {
    val scrollState = rememberScrollState()
    var userHasScrolled by remember { mutableStateOf(false) }
    var lastContentLength by remember { mutableStateOf(0) }
    
    // Auto-scroll when new content arrives, unless user has manually scrolled up
    LaunchedEffect(consoleOutput) {
        if (consoleOutput.length > lastContentLength) {
            if (!userHasScrolled || scrollState.value >= scrollState.maxValue - 20) {
                // Auto-scroll to bottom if user hasn't scrolled or is near the bottom
                scrollState.animateScrollTo(scrollState.maxValue)
                userHasScrolled = false // Reset when we auto-scroll to bottom
            }
            lastContentLength = consoleOutput.length
        }
    }
    
    // Track manual scrolling and detect when user returns to bottom
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            if (scrollState.value < scrollState.maxValue - 20) {
                userHasScrolled = true
            } else if (scrollState.value >= scrollState.maxValue - 5) {
                // User scrolled back to bottom, resume auto-scroll
                userHasScrolled = false
            }
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Console",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface
                )
            ) {
                Text(
                    text = if (consoleOutput.isNotEmpty()) {
                        "alfis@android:~\$ alfis\n${consoleOutput}"
                    } else {
                        "alfis@android:~\$ alfis\nStarting Alfis DNS resolver..."
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState),
                    lineHeight = 11.sp
                )
            }
        }
    }
}

@Composable
fun InfoSection() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = "How to Use",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Text(
                text = "1. Tap 'Start DNS' to begin the Alfis DNS resolver\n" +
                      "2. Configure other apps to use 127.0.0.1:5353 as DNS\n" +
                      "3. For Yggdrasil: Set DNS to localhost:5353\n" +
                      "4. Alfis will resolve .alfis domains and forward others",
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun BootAutoStartDebugSection() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("alfis_config", android.content.Context.MODE_PRIVATE)
    val autoStart = prefs.getBoolean("auto_start", false)
    
    if (autoStart) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Boot Auto-Start Debug",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "Auto-start on boot is ENABLED. If the DNS service doesn't start after reboot:\n\n" +
                          "1. Check that Alfis is whitelisted in battery optimization settings\n" +
                          "2. Enable 'Autostart' permission in your phone's app settings\n" +
                          "3. Check logcat for 'AlfisBootReceiver' messages\n" +
                          "4. Ensure the app has notification permissions\n\n" +
                          "To test: Enable 'Auto Start on Boot', reboot device, and check if DNS service is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AlfisMainScreenPreview() {
    AlfisTheme {
        AlfisMainScreen()
    }
}