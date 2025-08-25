package name.alfis.android.native

/**
 * JNI interface to the native Alfis DNS resolver
 */
object AlfisNative {
    
    init {
        System.loadLibrary("alfis")
        initLogging()
    }

    /**
     * Initialize native logging
     */
    private external fun initLogging()

    /**
     * Start the DNS server
     * @param configPath Path to the configuration file
     * @param workDir Working directory for blockchain data
     * @param logFile Path to log file
     * @return true if server started successfully
     */
    external fun startDnsServer(configPath: String, workDir: String, logFile: String): Boolean

    /**
     * Stop the DNS server
     * @return true if server stopped successfully
     */
    external fun stopDnsServer(): Boolean


    /**
     * Check if DNS server is running
     * @return true if running
     */
    external fun isDnsServerRunning(): Boolean

    /**
     * Get DNS server statistics as JSON string
     * @return JSON string with statistics
     */
    external fun getDnsStats(): String

    /**
     * Generate default configuration file
     * @param configPath Path where to save the configuration
     * @return true if configuration was generated successfully
     */
    external fun generateDefaultConfig(configPath: String): Boolean

    /**
     * Get console output for debugging
     * @return Recent log messages as a string
     */
    external fun getConsoleOutput(): String

    /**
     * Trigger network reconnection after connectivity change
     */
    external fun triggerNetworkReconnect()
}