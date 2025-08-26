# Alfis Android

Android application for ALFIS (Alternative Free Identity System) - a decentralized DNS and identity system.

This is a standalone Android project that builds against the main [Alfis repository](https://github.com/Revertron/Alfis) as a dependency.

## Project Structure

```
alfis-android/
├── android/                 # Android app (Kotlin/Compose)
├── alfis/                  # Rust JNI library
├── build.sh               # Main build script
└── README.md              # This file
```

## Prerequisites

1. **Rust toolchain** with Android targets:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
   ```

2. **Android NDK** (preferably version 29.0.13846066):
   - Install via Android Studio SDK Manager
   - Or set `ANDROID_NDK_HOME` environment variable

3. **Android SDK** with API level 21 or higher

4. **Git** for cloning the main Alfis repository

5. **cargo-ndk**:
   ```bash
   cargo install cargo-ndk
   ```

## Building

### Quick Build (Recommended)

Build using the latest code from the main Alfis repository:

```bash
./build.sh
```

### Build Options

```bash
# Use a specific branch
./build.sh --branch develop

# Use local Alfis repository (for development)
./build.sh --local /path/to/local/alfis

# Clean build
./build.sh --clean

# Show help
./build.sh --help
```

### Manual Build Steps

If you prefer to build manually:

1. Clone or update the main Alfis repository
2. Update the dependency path in `alfis/Cargo.toml`
3. Build the Rust library for Android targets
4. Build the Android APK using Gradle

## Development Workflow

### Using Local Alfis Repository

For development work where you're making changes to both the main Alfis code and the Android app:

```bash
# Clone the main Alfis repo locally
git clone https://github.com/Revertron/Alfis.git /path/to/alfis

# Build against your local changes
./build.sh --local /path/to/alfis
```

### Using Remote Repository

For building releases or when you just need the Android app:

```bash
# Build against latest main branch
./build.sh

# Build against specific branch/tag
./build.sh --branch v1.0.0
```

## Configuration

The Android app includes a Configuration section where users can adjust:

- **Forwarders**: DNS servers to forward queries to
- **Listen Address**: Local DNS server address
- **Peers**: Alfis network peers to connect to
- **Yggdrasil Only**: Toggle to connect only to Yggdrasil network peers

Default settings prioritize privacy and Yggdrasil network connectivity.

## Output

After a successful build, you'll find:

- **Debug APK**: `android/app/build/outputs/apk/debug/app-debug.apk`
- **Native libraries**: `android/app/src/main/jniLibs/`

## Installation

```bash
# Install on connected device
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

The Android app consists of two main components:

1. **Rust Library** (`alfis/`):
   - JNI bindings to the main Alfis Rust code
   - Handles DNS resolution, P2P networking, and blockchain operations
   - Built as a native library for multiple Android architectures

2. **Android App** (`android/`):
   - Kotlin app using Jetpack Compose
   - Provides configuration UI and Android integration
   - VPN service for DNS interception

## Supported Architectures

- ARM64 (arm64-v8a) - Primary target for modern devices
- ARMv7 (armeabi-v7a) - Older ARM devices
- x86_64 - x86-64 emulators and devices
- x86 - x86 emulators and older devices

## Troubleshooting

### NDK Issues

If the build fails with NDK-related errors:

1. Ensure you have the correct NDK version installed
2. Set `ANDROID_NDK_HOME` manually:
   ```bash
   export ANDROID_NDK_HOME="/path/to/ndk/29.0.13846066"
   ./build.sh
   ```

### Dependency Issues

If Rust compilation fails:

1. Ensure all Android targets are installed:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
   ```

2. Install cargo-ndk:
   ```bash
   cargo install cargo-ndk
   ```

### Gradle Issues

If the Android build fails:

1. Ensure you have a recent version of Android SDK
2. Check that API level 21+ is installed
3. Try cleaning the Gradle cache:
   ```bash
   cd android && ./gradlew clean
   ```

## Contributing

This project follows the same contribution guidelines as the main Alfis project. Please submit pull requests and issues to the appropriate repository:

- Android-specific issues: This repository
- Core Alfis issues: [Main Alfis repository](https://github.com/Revertron/Alfis)

## License

This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0), the same license as the main Alfis project. See the [LICENSE](LICENSE) file for the full license text.

The AGPL-3.0 is a copyleft license that ensures any modifications to this software remain open source and available to the community.