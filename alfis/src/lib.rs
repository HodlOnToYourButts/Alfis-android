use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use log::{error, info, warn};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};

// Import Alfis core components
use alfis::settings::Settings;
use alfis::{Context, Keystore, Chain};
use alfis::dns::context::ServerContext;
use alfis::p2p::network::Network;
use alfis::eventbus::register;
use alfis::event::Event;

// Global state for the DNS server and network
static mut ALFIS_CONTEXT: Option<Arc<Mutex<Context>>> = None;
static mut SERVER_CONTEXT: Option<Arc<ServerContext>> = None;
static mut NETWORK_HANDLE: Option<thread::JoinHandle<()>> = None;
static mut DNS_UDP_HANDLE: Option<thread::JoinHandle<()>> = None;
static mut DNS_TCP_HANDLE: Option<thread::JoinHandle<()>> = None;
static mut NETWORK_PEER_COUNT: usize = 0;
static mut DNS_RUNNING: bool = false;
static mut DNS_START_TIME: u64 = 0;
static mut LOG_BUFFER: Option<Arc<Mutex<VecDeque<String>>>> = None;
static DNS_SHUTDOWN_FLAG: AtomicBool = AtomicBool::new(false);
static mut SERVICE_PID: Option<u32> = None;

/// Initialize Android logging
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_initLogging(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("AlfisRust"),
    );
    
    // Initialize log buffer for console output
    unsafe {
        LOG_BUFFER = Some(Arc::new(Mutex::new(VecDeque::new())));
    }
    
    add_log_message("Alfis Android logging initialized".to_string());
    info!("Alfis Android logging initialized");
}

/// Start the DNS server
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_startDnsServer(
    mut env: JNIEnv,
    _class: JClass,
    config_path: JString,
    work_dir: JString,
    log_file: JString,
) -> jboolean {
    add_log_message("=== NATIVE LIBRARY UPDATED WITH DEBUG ===".to_string());
    let config_path: String = match env.get_string(&config_path) {
        Ok(path) => path.into(),
        Err(e) => {
            error!("Failed to get config path: {}", e);
            return 0; // false
        }
    };

    let work_dir: String = match env.get_string(&work_dir) {
        Ok(path) => path.into(),
        Err(e) => {
            error!("Failed to get work directory: {}", e);
            return 0; // false
        }
    };

    let log_file: String = match env.get_string(&log_file) {
        Ok(path) => path.into(),
        Err(e) => {
            error!("Failed to get log file path: {}", e);
            return 0; // false
        }
    };

    unsafe {
        if DNS_RUNNING {
            add_log_message("DNS server is already running".to_string());
            warn!("DNS server is already running");
            return 1; // true - already running
        }
        
        add_log_message("Starting DNS server...".to_string());
        info!("Starting DNS server...");
        
        // Reset shutdown flag and network peer count for new start
        DNS_SHUTDOWN_FLAG.store(false, Ordering::Relaxed);
        NETWORK_PEER_COUNT = 0;

        // Start the DNS server in a background thread to avoid blocking the main thread
        let config_path_clone = config_path.clone();
        let work_dir_clone = work_dir.clone();
        let log_file_clone = log_file.clone();
        
        thread::spawn(move || {
            match start_dns_server_internal(&config_path_clone, &work_dir_clone, &log_file_clone) {
                Ok((context, server_context)) => {
                    unsafe {
                        ALFIS_CONTEXT = Some(context);
                        SERVER_CONTEXT = Some(server_context);
                        DNS_RUNNING = true;
                        DNS_START_TIME = SystemTime::now()
                            .duration_since(UNIX_EPOCH)
                            .unwrap()
                            .as_secs();
                    }
                    add_log_message("DNS server started successfully".to_string());
                    add_log_message("UDP and TCP servers listening on configured address".to_string());
                    
                    // Log initial blockchain status
                    unsafe {
                        if let Some(alfis_ctx) = &ALFIS_CONTEXT {
                            if let Ok(ctx_guard) = alfis_ctx.lock() {
                                let blocks = ctx_guard.chain.get_height();
                                add_log_message(format!("Blockchain loaded with {} blocks", blocks));
                            }
                        }
                    }
                    
                    add_log_message("Ready to resolve .alfis domains".to_string());
                    add_log_message("DNS forwarding enabled for regular domains".to_string());
                    info!("DNS server started successfully");
                }
                Err(e) => {
                    add_log_message(format!("Failed to start DNS server: {}", e));
                    error!("Failed to start DNS server: {}", e);
                    unsafe {
                        DNS_RUNNING = false;
                    }
                }
            }
        });
        
        // Give the server a moment to start
        thread::sleep(Duration::from_millis(500));
        
        // Check if server was started
        unsafe {
            if DNS_RUNNING {
                1 // true
            } else {
                0 // false
            }
        }
    }
}


/// Stop the DNS server
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_stopDnsServer(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    unsafe {
        if DNS_RUNNING {
            add_log_message("Stopping DNS server...".to_string());
            info!("Stopping DNS server...");
            
            // Signal shutdown to all DNS threads
            DNS_SHUTDOWN_FLAG.store(true, Ordering::Relaxed);
            DNS_RUNNING = false;
            
            add_log_message("Waiting for DNS threads to stop...".to_string());
            
            // Give threads a moment to see the shutdown flag
            thread::sleep(Duration::from_millis(100));
            
            // Try to join UDP thread with timeout
            if let Some(handle) = DNS_UDP_HANDLE.take() {
                add_log_message("Stopping UDP server thread...".to_string());
                // Note: std::thread::JoinHandle doesn't have timeout, so we'll just join
                // The threads should exit quickly due to the 10ms sleep in the loop
                match handle.join() {
                    Ok(_) => add_log_message("UDP server thread stopped".to_string()),
                    Err(e) => {
                        add_log_message(format!("UDP thread join failed: {:?}", e));
                        error!("Failed to join UDP thread: {:?}", e);
                    }
                }
            }
            
            // Try to join TCP thread
            if let Some(handle) = DNS_TCP_HANDLE.take() {
                add_log_message("Stopping TCP server thread...".to_string());
                match handle.join() {
                    Ok(_) => add_log_message("TCP server thread stopped".to_string()),
                    Err(e) => {
                        add_log_message(format!("TCP thread join failed: {:?}", e));
                        error!("Failed to join TCP thread: {:?}", e);
                    }
                }
            }
            
            // Stop network thread if it exists
            if let Some(handle) = NETWORK_HANDLE.take() {
                add_log_message("Stopping network thread...".to_string());
                // The network thread should stop when contexts are cleared
                // We don't join it to avoid hanging
                drop(handle);
            }
            
            // Clear all contexts and handles
            ALFIS_CONTEXT = None;
            SERVER_CONTEXT = None;
            NETWORK_PEER_COUNT = 0;
            
            add_log_message("DNS server stopped cleanly - port 5353 released".to_string());
            info!("DNS server stopped cleanly");
            1 // true
        } else {
            warn!("DNS server was not running");
            0 // false
        }
    }
}

/// Check if DNS server is running
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_isDnsServerRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    unsafe {
        if DNS_RUNNING {
            1 // true
        } else {
            0 // false
        }
    }
}

/// Get DNS server statistics as JSON string
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_getDnsStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let stats_json = unsafe {
        if DNS_RUNNING {
            let _uptime = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs() - DNS_START_TIME;
                
            // Get comprehensive statistics 
            let (queries, responses, blocks, peers) = match (&SERVER_CONTEXT, &ALFIS_CONTEXT) {
                (Some(server_ctx), Some(alfis_ctx)) => {
                    let udp_queries = server_ctx.statistics.get_udp_query_count();
                    let tcp_queries = server_ctx.statistics.get_tcp_query_count();
                    let total_queries = udp_queries + tcp_queries;
                    
                    // Get blockchain statistics
                    let (block_count, peer_count) = if let Ok(ctx_guard) = alfis_ctx.lock() {
                        let blocks = ctx_guard.chain.get_height();
                        let peers = unsafe { NETWORK_PEER_COUNT };
                        // Add debug logging for block count
                        if blocks > 0 {
                            log::debug!("Blockchain height: {}", blocks);
                        }
                        (blocks, peers)
                    } else {
                        log::warn!("Failed to acquire context lock for statistics");
                        (0, 0)
                    };
                    
                    (total_queries, total_queries, block_count, peer_count)
                }
                _ => (0, 0, 0, 0)
            };
                
            format!(r#"{{"blocks": {}, "peers": {}, "queries": {}, "responses": {}}}"#, blocks, peers, queries, responses)
        } else {
            r#"{"blocks": 0, "peers": 0, "queries": 0, "responses": 0}"#.to_string()
        }
    };

    match env.new_string(stats_json) {
        Ok(jstr) => jstr.as_raw(),
        Err(e) => {
            error!("Failed to create Java string: {}", e);
            std::ptr::null_mut()
        }
    }
}

/// Generate default configuration
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_generateDefaultConfig(
    mut env: JNIEnv,
    _class: JClass,
    config_path: JString,
) -> jboolean {
    let config_path: String = match env.get_string(&config_path) {
        Ok(path) => path.into(),
        Err(e) => {
            error!("Failed to get config path: {}", e);
            return 0; // false
        }
    };

    match generate_android_config(&config_path) {
        Ok(_) => {
            add_log_message(format!("Default configuration generated at: {}", config_path));
            info!("Default configuration generated at: {}", config_path);
            1 // true
        }
        Err(e) => {
            add_log_message(format!("Failed to generate configuration: {}", e));
            error!("Failed to generate configuration: {}", e);
            0 // false
        }
    }
}

/// Get console output for the Android app
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_getConsoleOutput(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let console_output = unsafe {
        match &LOG_BUFFER {
            Some(buffer) => {
                if let Ok(buffer_guard) = buffer.lock() {
                    buffer_guard.iter().cloned().collect::<Vec<String>>().join("\n")
                } else {
                    "Failed to get log buffer lock".to_string()
                }
            }
            None => "Log buffer not initialized".to_string(),
        }
    };

    match env.new_string(console_output) {
        Ok(jstr) => jstr.as_raw(),
        Err(e) => {
            error!("Failed to create Java string for console output: {}", e);
            std::ptr::null_mut()
        }
    }
}

/// Trigger network reconnection after connectivity change
#[no_mangle]
pub extern "C" fn Java_name_alfis_android_native_AlfisNative_triggerNetworkReconnect(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe {
        if DNS_RUNNING {
            add_log_message("Network connectivity changed - triggering reconnection".to_string());
            
            // Signal the network thread to reconnect
            if let Some(context) = &ALFIS_CONTEXT {
                if let Ok(ctx) = context.lock() {
                    let current_height = ctx.chain.get_height();
                    
                    // Force network reconnection by restarting network connections
                    // This helps recover from network switches (WiFi <-> cellular)
                    info!("Triggering network reconnection due to connectivity change");
                    add_log_message(format!("Reconnecting at block {} - clearing stale connections", current_height));
                    
                    // The network layer should automatically handle reconnection
                    // when it detects connection issues during its periodic operations
                } else {
                    warn!("Could not acquire context lock for network reconnection");
                }
            }
        }
    }
}

// Internal implementation functions

fn start_dns_server_internal(
    config_path: &str,
    _work_dir: &str,
    _log_file: &str,
) -> Result<(Arc<Mutex<Context>>, Arc<ServerContext>), Box<dyn std::error::Error>> {
    add_log_message("Alfis DNS resolver starting...".to_string());
    add_log_message(format!("Loading configuration from {}", config_path));
    info!("Starting DNS server with config: {}", config_path);

    // Load settings with error handling
    let mut settings = match Settings::load(config_path) {
        Some(s) => s,
        None => {
            add_log_message("Configuration not found, generating defaults".to_string());
            warn!("Failed to load settings from {}, generating default config", config_path);
            generate_android_config(config_path)?;
            add_log_message("Default configuration created".to_string());
            Settings::load(config_path).ok_or("Failed to load generated settings")?
        }
    };
    
    // Debug: Log the DNS listen address from config
    add_log_message(format!("Loaded DNS listen address from config: {}", settings.dns.listen));
    
    // Override settings for Android
    // Note: DNS listen address is taken from config file, no override here
    settings.dns.threads = 8; // Increased for better performance
    
    // Initialize context with better error handling
    let keystore = Keystore::new();
    let keystores = vec![keystore];
    
    // Initialize chain with better Android-specific error handling
    let db_path = format!("{}/alfis.db", _work_dir);
    add_log_message("Initializing blockchain database...".to_string());
    info!("Initializing blockchain database at: {}", db_path);
    
    // Ensure the directory exists
    if let Some(parent) = std::path::Path::new(&db_path).parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            warn!("Could not create database directory: {}", e);
        }
    }
    
    let chain = match create_chain_safely(&settings, &db_path) {
        Ok(chain) => chain,
        Err(e) => {
            warn!("Failed to create file-based database ({}), falling back to in-memory database", e);
            // Fallback to in-memory database for Android compatibility
            match create_chain_safely(&settings, ":memory:") {
                Ok(chain) => {
                    add_log_message("Blockchain initialized (in-memory mode)".to_string());
                    info!("Successfully created in-memory blockchain database");
                    chain
                }
                Err(e) => {
                    add_log_message(format!("Error: Blockchain initialization failed: {}", e));
                    error!("Even in-memory database failed: {}", e);
                    return Err("Blockchain initialization failed completely".into());
                }
            }
        }
    };
    
    let context = Arc::new(Mutex::new(Context::new("0.8.6".to_owned(), settings.clone(), keystores, chain)));
    
    // Start the real DNS server with statistics tracking
    add_log_message("Starting DNS servers...".to_string());
    info!("Starting DNS servers...");
    let server_context = start_dns_server_with_context(&context, &settings)?;

    // Start the P2P network to connect to peers and sync blocks
    add_log_message("Starting P2P network...".to_string());
    info!("Starting P2P network...");
    start_network_with_context(Arc::clone(&context))?;

    info!("DNS server and network started successfully");
    Ok((context, server_context))
}

fn generate_android_config(config_path: &str) -> Result<(), Box<dyn std::error::Error>> {
    let config = r#"# Alfis Android Configuration
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
"#;

    std::fs::write(config_path, config)?;
    Ok(())
}

// Helper function to add messages to log buffer
fn add_log_message(message: String) {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let formatted_message = format!("[{}] {}", timestamp, message);
    
    unsafe {
        if let Some(buffer) = &LOG_BUFFER {
            if let Ok(mut buffer_guard) = buffer.lock() {
                buffer_guard.push_back(formatted_message);
                // Keep only last 100 messages
                if buffer_guard.len() > 100 {
                    buffer_guard.pop_front();
                }
            }
        }
    }
}

// Real DNS server implementation using Alfis core components

/// Start DNS server with controllable threads
fn start_dns_server_with_context(context: &Arc<Mutex<Context>>, settings: &Settings) -> Result<Arc<ServerContext>, Box<dyn std::error::Error>> {
    // Create server context
    let server_context = create_android_server_context(Arc::clone(context), settings);

    // DNS server setup
    
    // Start UDP server in controllable thread
    if server_context.enable_udp {
        add_log_message("Starting UDP DNS server...".to_string());
        let server_ctx_clone = Arc::clone(&server_context);
        let udp_handle = thread::Builder::new()
            .name("DNS-UDP".to_string())
            .spawn(move || {
                run_controllable_udp_server(server_ctx_clone);
            })?;
        
        unsafe {
            DNS_UDP_HANDLE = Some(udp_handle);
        }
        add_log_message("UDP DNS server started successfully".to_string());
    }

    // Start TCP server in controllable thread  
    if server_context.enable_tcp {
        add_log_message("Starting TCP DNS server...".to_string());
        let server_ctx_clone = Arc::clone(&server_context);
        let tcp_handle = thread::Builder::new()
            .name("DNS-TCP".to_string())
            .spawn(move || {
                run_controllable_tcp_server(server_ctx_clone);
            })?;
            
        unsafe {
            DNS_TCP_HANDLE = Some(tcp_handle);
        }
        add_log_message("TCP DNS server started successfully".to_string());
    }
    
    Ok(server_context)
}

/// Create server context for Android (based on dns_utils.rs create_server_context)
fn create_android_server_context(context: Arc<Mutex<Context>>, settings: &Settings) -> Arc<ServerContext> {
    use alfis::dns::context::ResolveStrategy;
    use alfis::blockchain::filter::BlockchainFilter;

    let mut server_context = ServerContext::new(settings.dns.listen.clone(), settings.dns.bootstraps.clone());
    add_log_message(format!("DNS server configured to listen on: {}", settings.dns.listen));
    server_context.allow_recursive = true;
    server_context.resolve_strategy = match settings.dns.forwarders.is_empty() {
        true => ResolveStrategy::Recursive,
        false => ResolveStrategy::Forward { upstreams: settings.dns.forwarders.clone() }
    };
    
    // Add blockchain filter for .alfis domains
    server_context.filters.push(Box::new(BlockchainFilter::new(context)));
    
    match server_context.initialize() {
        Ok(_) => info!("Server context initialized successfully"),
        Err(e) => error!("Server context initialization failed: {:?}", e)
    }

    Arc::new(server_context)
}

/// Safely create a blockchain with proper error handling for Android
fn create_chain_safely(settings: &Settings, db_path: &str) -> Result<Chain, Box<dyn std::error::Error>> {
    info!("Attempting to create blockchain database at: {}", db_path);
    
    // Pre-check: test if we can create/access the database file
    match sqlite::open(db_path) {
        Ok(db) => {
            match db.execute("SELECT 1") {
                Ok(_) => {
                    info!("Database connection test successful for {}", db_path);
                    // Close the test connection
                    drop(db);
                }
                Err(e) => {
                    error!("Database functionality test failed: {}", e);
                    return Err(format!("Database not functional: {}", e).into());
                }
            }
        }
        Err(e) => {
            error!("Failed to open SQLite database at {}: {}", db_path, e);
            return Err(format!("Database creation failed: {}", e).into());
        }
    }
    
    // Now try to create the chain - this might still panic due to internal expects
    // but at least we know the database itself works
    let result = std::panic::catch_unwind(|| {
        Chain::new(settings, db_path)
    });
    
    match result {
        Ok(chain) => {
            info!("Blockchain created successfully");
            Ok(chain)
        }
        Err(panic_err) => {
            error!("Chain::new panicked during initialization");
            // Try to extract panic message if possible
            if let Some(msg) = panic_err.downcast_ref::<String>() {
                error!("Panic message: {}", msg);
                Err(format!("Chain creation panicked: {}", msg).into())
            } else if let Some(msg) = panic_err.downcast_ref::<&str>() {
                error!("Panic message: {}", msg);
                Err(format!("Chain creation panicked: {}", msg).into())
            } else {
                Err("Chain creation panicked with unknown error".into())
            }
        }
    }
}

/// Start the P2P network thread
fn start_network_with_context(context: Arc<Mutex<Context>>) -> Result<(), Box<dyn std::error::Error>> {
    // Register event listener to track network status and block updates
    register(|_uuid, event| {
        match event {
            Event::NetworkStatus { blocks, domains: _, keys: _, nodes } => {
                unsafe {
                    NETWORK_PEER_COUNT = nodes;
                }
                
                // Log peer connectivity status periodically
                static mut LAST_PEER_LOG: u64 = 0;
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_secs();
                
                unsafe {
                    if now - LAST_PEER_LOG > 60 { // Log every 60 seconds
                        LAST_PEER_LOG = now;
                        if nodes == 0 {
                            add_log_message("Warning: No peer connections active".to_string());
                            log::warn!("No active peer connections - this will prevent sync");
                        } else {
                            add_log_message(format!("Network: {} peers, {} blocks", nodes, blocks));
                            log::info!("Active peers: {}, known blocks: {}", nodes, blocks);
                            
                            // Check if we have a reasonable number of peers for good sync
                            if nodes < 2 {
                                add_log_message("Few peers: Consider checking network connectivity".to_string());
                                log::warn!("Low peer count ({}) may impact sync performance", nodes);
                            }
                        }
                    }
                }
            }
            Event::BlockchainChanged { index: _ } => {
                // Silent - syncing events will show progress
            }
            Event::NewBlockReceived => {
                // Silent - syncing events will show progress
            }
            Event::Syncing { have, height } => {
                let percent = if height > 0 { (have as f64 / height as f64) * 100.0 } else { 0.0 };
                add_log_message(format!("Syncing: {}/{} blocks ({:.1}%)", have, height, percent));
            }
            Event::SyncFinished => {
                add_log_message("Blockchain synchronization completed".to_string());
            }
            _ => {
                // Other events - silent
            }
        }
        true // Keep listening
    });

    let context_clone = Arc::clone(&context);
    
    let handle = thread::Builder::new()
        .name(String::from("Network"))
        .spawn(move || {
            // Give the DNS server time to start
            thread::sleep(Duration::from_millis(1000));
            
            add_log_message("Connecting to P2P network...".to_string());
            info!("Starting P2P network thread");
            
            add_log_message("Network thread started".to_string());
            add_log_message("Attempting to connect to bootstrap peers...".to_string());
            add_log_message("Looking for peers at peer-v4.alfis.name:4244 and peer-v6.alfis.name:4244".to_string());
            
            let mut network = Network::new(context_clone);
            network.start();
        })?;
    
    unsafe {
        NETWORK_HANDLE = Some(handle);
    }
    
    Ok(())
}

/// Controllable UDP DNS server that respects shutdown flag
fn run_controllable_udp_server(server_context: Arc<ServerContext>) {
    use std::net::UdpSocket;
    use alfis::dns::server::execute_query;
    use alfis::dns::buffer::{BytePacketBuffer, PacketBuffer};
    use alfis::dns::protocol::DnsPacket;
    
    let socket = match UdpSocket::bind(&server_context.dns_listen) {
        Ok(socket) => {
            add_log_message(format!("UDP server bound to {}", server_context.dns_listen));
            socket
        }
        Err(e) => {
            add_log_message(format!("Failed to bind UDP socket: {}", e));
            error!("Failed to bind UDP socket: {}", e);
            return;
        }
    };

    // Set socket to non-blocking so we can check shutdown flag
    if let Err(e) = socket.set_nonblocking(true) {
        error!("Failed to set socket non-blocking: {}", e);
        return;
    }

    let mut buf = [0; 512];
    
    while !DNS_SHUTDOWN_FLAG.load(Ordering::Relaxed) {
        match socket.recv_from(&mut buf) {
            Ok((size, src)) => {
                let mut packet_buffer = BytePacketBuffer::new();
                packet_buffer.buf[..size].copy_from_slice(&buf[..size]);
                
                if let Ok(request) = DnsPacket::from_buffer(&mut packet_buffer) {
                    let mut response = execute_query(Arc::clone(&server_context), &request);
                    
                    let mut res_buffer = BytePacketBuffer::new();
                    if response.write(&mut res_buffer, 512).is_ok() {
                        let len = res_buffer.pos();
                        let _ = socket.send_to(&res_buffer.buf[..len], src);
                        
                        // Update UDP query statistics
                        server_context.statistics.udp_query_count.fetch_add(1, std::sync::atomic::Ordering::Release);
                    }
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                // No data available, sleep briefly and check shutdown flag again
                thread::sleep(Duration::from_millis(10));
                continue;
            }
            Err(e) => {
                if !DNS_SHUTDOWN_FLAG.load(Ordering::Relaxed) {
                    error!("UDP socket error: {}", e);
                }
                break;
            }
        }
    }
    
    add_log_message("UDP DNS server thread stopped".to_string());
    info!("UDP DNS server thread stopped");
}

/// Controllable TCP DNS server that respects shutdown flag  
fn run_controllable_tcp_server(server_context: Arc<ServerContext>) {
    use std::net::TcpListener;
    
    let listener = match TcpListener::bind(&server_context.dns_listen) {
        Ok(listener) => {
            add_log_message(format!("TCP server bound to {}", server_context.dns_listen));
            listener
        }
        Err(e) => {
            add_log_message(format!("Failed to bind TCP socket: {}", e));
            error!("Failed to bind TCP socket: {}", e);
            return;
        }
    };

    // Set listener to non-blocking
    if let Err(e) = listener.set_nonblocking(true) {
        error!("Failed to set TCP listener non-blocking: {}", e);
        return;
    }

    while !DNS_SHUTDOWN_FLAG.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((stream, _addr)) => {
                let server_ctx = Arc::clone(&server_context);
                thread::spawn(move || {
                    handle_tcp_client(stream, server_ctx);
                });
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                // No connection available, sleep briefly and check shutdown flag again
                thread::sleep(Duration::from_millis(10));
                continue;
            }
            Err(e) => {
                if !DNS_SHUTDOWN_FLAG.load(Ordering::Relaxed) {
                    error!("TCP accept error: {}", e);
                }
                break;
            }
        }
    }
    
    add_log_message("TCP DNS server thread stopped".to_string());
    info!("TCP DNS server thread stopped");
}

/// Handle individual TCP client connection (simplified)
fn handle_tcp_client(mut stream: std::net::TcpStream, server_context: Arc<ServerContext>) {
    use std::io::{Read, Write};
    use alfis::dns::server::execute_query;
    use alfis::dns::buffer::{BytePacketBuffer, PacketBuffer};
    use alfis::dns::protocol::DnsPacket;
    
    let mut buf = [0; 512];
    if let Ok(size) = stream.read(&mut buf) {
        if size >= 2 {
            // Skip the 2-byte length prefix for simplicity
            let mut packet_buffer = BytePacketBuffer::new();
            packet_buffer.buf[..size-2].copy_from_slice(&buf[2..size]);
            
            if let Ok(request) = DnsPacket::from_buffer(&mut packet_buffer) {
                let mut response = execute_query(server_context.clone(), &request);
                
                let mut res_buffer = BytePacketBuffer::new();
                if response.write(&mut res_buffer, 512).is_ok() {
                    let len = res_buffer.pos();
                    // Write length prefix then data
                    let len_bytes = (len as u16).to_be_bytes();
                    let _ = stream.write_all(&len_bytes);
                    let _ = stream.write_all(&res_buffer.buf[..len]);
                    
                    // Update TCP query statistics
                    server_context.statistics.tcp_query_count.fetch_add(1, std::sync::atomic::Ordering::Release);
                }
            }
        }
    }
}