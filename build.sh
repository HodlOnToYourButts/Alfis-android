#!/bin/bash

# Build script for Alfis Android app
# This script downloads the main Alfis repository as a dependency and builds the Android APK

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/android"
RUST_LIB_DIR="$SCRIPT_DIR/alfis"
TMP_DIR="/tmp/alfis-build-$$"
ALFIS_REPO="https://github.com/Revertron/Alfis.git"
ALFIS_BRANCH="master"

echo "Building Alfis Android app..."

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --branch)
            ALFIS_BRANCH="$2"
            shift 2
            ;;
        --local)
            LOCAL_ALFIS_PATH="$2"
            shift 2
            ;;
        --clean)
            CLEAN_BUILD=1
            shift
            ;;
        --help)
            echo "Usage: $0 [options]"
            echo "Options:"
            echo "  --branch BRANCH    Use specific branch from Alfis repo (default: master)"
            echo "  --local PATH       Use local Alfis repository instead of cloning"
            echo "  --clean            Clean build - remove temporary files"
            echo "  --help             Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for available options"
            exit 1
            ;;
    esac
done

# Clean up function
cleanup() {
    # Restore original Cargo.toml if backup exists
    if [ -f "$RUST_LIB_DIR/Cargo.toml.backup" ]; then
        echo "Restoring original Cargo.toml..."
        mv "$RUST_LIB_DIR/Cargo.toml.backup" "$RUST_LIB_DIR/Cargo.toml"
    fi
    
    if [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ]; then
        echo "Cleaning up temporary directory: $TMP_DIR"
        rm -rf "$TMP_DIR"
    fi
}

# Set up cleanup trap
trap cleanup EXIT

# Check if required tools are installed
if ! command -v cargo &> /dev/null; then
    echo "Error: Rust/Cargo not found. Please install Rust first."
    exit 1
fi

if ! command -v git &> /dev/null; then
    echo "Error: Git not found. Please install Git first."
    exit 1
fi

if ! command -v cargo-ndk &> /dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Check for Android NDK and auto-detect if not set
if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$ANDROID_NDK_ROOT" ]; then
    echo "ANDROID_NDK_HOME not set. Attempting to auto-detect..."
    
    # Common NDK locations
    NDK_PATHS=(
        "$HOME/Android/Sdk/ndk"
        "$HOME/Library/Android/sdk/ndk"
        "/opt/android-ndk"
        "/usr/local/android-ndk"
    )
    
    # Preferred NDK versions (in order of preference)
    PREFERRED_VERSIONS=(
        "29.0.13846066"
        "26.0.10404224"
        "25.2.9519653"
    )
    
    for ndk_path in "${NDK_PATHS[@]}"; do
        if [ -d "$ndk_path" ]; then
            # First, try preferred versions
            for version in "${PREFERRED_VERSIONS[@]}"; do
                if [ -d "$ndk_path/$version" ]; then
                    export ANDROID_NDK_HOME="$ndk_path/$version"
                    echo "Found preferred NDK version at: $ANDROID_NDK_HOME"
                    break 2
                fi
            done
            
            # Fallback to latest version if no preferred version found
            latest_version=$(ls "$ndk_path" | sort -V | tail -n 1)
            if [ -n "$latest_version" ] && [ -d "$ndk_path/$latest_version" ]; then
                export ANDROID_NDK_HOME="$ndk_path/$latest_version"
                echo "Found NDK at: $ANDROID_NDK_HOME"
                break
            fi
        fi
    done
    
    if [ -z "$ANDROID_NDK_HOME" ]; then
        echo "Error: Could not find Android NDK installation."
        echo "Please set ANDROID_NDK_HOME environment variable or install Android NDK."
        echo "You can install it via Android Studio SDK Manager or download from:"
        echo "https://developer.android.com/ndk/downloads"
        exit 1
    fi
fi

# Prepare Alfis source code
if [ -n "$LOCAL_ALFIS_PATH" ]; then
    echo "Using local Alfis repository at: $LOCAL_ALFIS_PATH"
    if [ ! -d "$LOCAL_ALFIS_PATH" ]; then
        echo "Error: Local Alfis path does not exist: $LOCAL_ALFIS_PATH"
        exit 1
    fi
    ALFIS_SOURCE_DIR="$LOCAL_ALFIS_PATH"
else
    echo "Downloading Alfis source code..."
    mkdir -p "$TMP_DIR"
    echo "Cloning Alfis repository (branch: $ALFIS_BRANCH)..."
    git clone --depth 1 --branch "$ALFIS_BRANCH" "$ALFIS_REPO" "$TMP_DIR/alfis"
    ALFIS_SOURCE_DIR="$TMP_DIR/alfis"
fi

echo "Alfis source directory: $ALFIS_SOURCE_DIR"

# Update Cargo.toml to point to the main Alfis codebase
echo "Updating alfis Cargo.toml dependencies..."
cd "$RUST_LIB_DIR"

# Create a backup of the original Cargo.toml
cp Cargo.toml Cargo.toml.backup

# Update the dependency path to point to the Alfis source
sed -i.tmp "s|alfis = { path = \"../\"|alfis = { path = \"$ALFIS_SOURCE_DIR\"|g" Cargo.toml
rm -f Cargo.toml.tmp

# Apply Android target fix if patch file exists
if [ -f "$SCRIPT_DIR/android_target_fix.patch" ]; then
    echo "Applying Android target fix..."
    cd "$ALFIS_SOURCE_DIR"
    if patch -p1 < "$SCRIPT_DIR/android_target_fix.patch" --dry-run > /dev/null 2>&1; then
        patch -p1 < "$SCRIPT_DIR/android_target_fix.patch"
        echo "Android target fix applied successfully"
    else
        echo "Warning: Could not apply Android target fix patch (may already be applied)"
    fi
    cd "$RUST_LIB_DIR"
fi

# Add Android targets if not already added
echo "Adding Android targets..."
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android 2>/dev/null || true

# Build Rust native library for all Android architectures
echo "Building Rust native library..."

echo "Building for ARM64..."
cargo ndk --target arm64-v8a --platform 21 -- build --release

echo "Building for ARMv7..."
cargo ndk --target armeabi-v7a --platform 21 -- build --release

echo "Building for x86_64..."
cargo ndk --target x86_64 --platform 21 -- build --release

echo "Building for x86..."
cargo ndk --target x86 --platform 21 -- build --release

# Note: Cargo.toml will be restored by cleanup function

# Create JNI libs directories
echo "Copying native libraries to Android project..."
mkdir -p "$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$ANDROID_DIR/app/src/main/jniLibs/armeabi-v7a"
mkdir -p "$ANDROID_DIR/app/src/main/jniLibs/x86_64"
mkdir -p "$ANDROID_DIR/app/src/main/jniLibs/x86"

# Copy libraries
cp "$RUST_LIB_DIR/target/aarch64-linux-android/release/libalfis.so" "$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a/"
cp "$RUST_LIB_DIR/target/armv7-linux-androideabi/release/libalfis.so" "$ANDROID_DIR/app/src/main/jniLibs/armeabi-v7a/"
cp "$RUST_LIB_DIR/target/x86_64-linux-android/release/libalfis.so" "$ANDROID_DIR/app/src/main/jniLibs/x86_64/"
cp "$RUST_LIB_DIR/target/i686-linux-android/release/libalfis.so" "$ANDROID_DIR/app/src/main/jniLibs/x86/"

echo "Native libraries copied successfully!"

# Build Android APK
echo "Building Android APK..."
cd "$ANDROID_DIR"

# Check if gradlew exists, if not, generate it
if [ ! -f "gradlew" ]; then
    echo "Gradle wrapper not found. Generating..."
    if command -v gradle &> /dev/null; then
        gradle wrapper
    else
        echo "Warning: Gradle not found. Installing Gradle..."
        # Try to install Gradle using SDKMAN
        if command -v curl &> /dev/null; then
            echo "Installing SDKMAN and Gradle..."
            curl -s "https://get.sdkman.io" | bash
            source "$HOME/.sdkman/bin/sdkman-init.sh"
            sdk install gradle
            gradle wrapper
        else
            echo "Please install Gradle or manually add the Gradle wrapper to the android directory."
            echo "You can do this by running 'gradle wrapper' in the android directory."
            echo ""
            echo "Native libraries were built successfully and copied to:"
            echo "  $ANDROID_DIR/app/src/main/jniLibs/"
            echo ""
            echo "To complete the Android build later:"
            echo "  cd android && gradle wrapper && ./gradlew assembleDebug"
            exit 0
        fi
    fi
fi

# Make gradlew executable
chmod +x gradlew

# Build debug APK
echo "Building debug APK..."
./gradlew assembleDebug

echo ""
echo "Build completed successfully!"
echo "Debug APK location: $ANDROID_DIR/app/build/outputs/apk/debug/"
echo ""
echo "To install on device:"
echo "  adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To build release APK:"
echo "  cd android && ./gradlew assembleRelease"
echo ""
echo "Build artifacts:"
echo "  APK: $ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -n "$LOCAL_ALFIS_PATH" ]; then
    echo "  Used local Alfis source: $LOCAL_ALFIS_PATH"
else
    echo "  Downloaded Alfis source to: $TMP_DIR/alfis"
fi