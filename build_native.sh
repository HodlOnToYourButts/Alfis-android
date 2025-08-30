#!/bin/bash

# Android NDK Build Script for Alfis
# Generic script that auto-detects user, NDK location, and version
# Usage: ./build_native.sh [ndk_version]

set -e  # Exit on any error

# Auto-detect user and Android SDK path
CURRENT_USER=$(whoami)
ANDROID_HOME="$HOME/Android/Sdk"

echo "Detected user: $CURRENT_USER"
echo "Android SDK path: $ANDROID_HOME"

# Function to find the newest NDK version
find_newest_ndk() {
    local ndk_base_dir="$ANDROID_HOME/ndk"
    
    if [ ! -d "$ndk_base_dir" ]; then
        echo "Error: NDK directory not found at $ndk_base_dir"
        echo "Please install Android NDK through Android Studio or SDK Manager"
        exit 1
    fi
    
    # Find numeric NDK versions (ignore android-ndk-r* versions) and sort them
    local newest_version=$(ls -1 "$ndk_base_dir" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+' | sort -V -r | head -1)
    
    # If no numeric version found, fall back to all versions
    if [ -z "$newest_version" ]; then
        newest_version=$(ls -1 "$ndk_base_dir" | sort -V -r | head -1)
    fi
    
    if [ -z "$newest_version" ]; then
        echo "Error: No NDK versions found in $ndk_base_dir"
        exit 1
    fi
    
    echo "$newest_version"
}

# Determine NDK version to use
if [ -n "$1" ]; then
    NDK_VERSION="$1"
    echo "Using specified NDK version: $NDK_VERSION"
else
    NDK_VERSION=$(find_newest_ndk)
    echo "Auto-detected newest NDK version: $NDK_VERSION"
fi

# Set up NDK paths
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/$NDK_VERSION"
export NDK_HOME="$ANDROID_NDK_ROOT"

# Verify NDK exists
if [ ! -d "$ANDROID_NDK_ROOT" ]; then
    echo "Error: Android NDK not found at $ANDROID_NDK_ROOT"
    echo "Available NDK versions:"
    ls -1 "$ANDROID_HOME/ndk/" 2>/dev/null || echo "  (none found)"
    echo ""
    echo "To install NDK:"
    echo "1. Open Android Studio"
    echo "2. Go to Tools → SDK Manager"
    echo "3. Go to SDK Tools tab"
    echo "4. Check 'NDK (Side by side)' and click OK"
    exit 1
fi

echo "Using Android NDK: $ANDROID_NDK_ROOT"

# Set up cross-compilation environment
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"

# Verify toolchain exists
if [ ! -d "$TOOLCHAIN" ]; then
    echo "Error: NDK toolchain not found at $TOOLCHAIN"
    echo "This might be a different NDK structure or unsupported platform"
    exit 1
fi

export PATH="$TOOLCHAIN/bin:$PATH"

# Define target architectures and their corresponding Rust targets
declare -A TARGETS
TARGETS["arm64-v8a"]="aarch64-linux-android"
TARGETS["armeabi-v7a"]="armv7-linux-androideabi" 
TARGETS["x86_64"]="x86_64-linux-android"
TARGETS["x86"]="i686-linux-android"

echo "Configuring cross-compilation environment..."

# Define linkers for each target (with API level 21 for compatibility)
export CC_aarch64_linux_android="aarch64-linux-android21-clang"
export CXX_aarch64_linux_android="aarch64-linux-android21-clang++"
export AR_aarch64_linux_android="llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="aarch64-linux-android21-clang"

export CC_armv7_linux_androideabi="armv7a-linux-androideabi21-clang"
export CXX_armv7_linux_androideabi="armv7a-linux-androideabi21-clang++"
export AR_armv7_linux_androideabi="llvm-ar"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="armv7a-linux-androideabi21-clang"

export CC_i686_linux_android="i686-linux-android21-clang"
export CXX_i686_linux_android="i686-linux-android21-clang++"
export AR_i686_linux_android="llvm-ar"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="i686-linux-android21-clang"

export CC_x86_64_linux_android="x86_64-linux-android21-clang"
export CXX_x86_64_linux_android="x86_64-linux-android21-clang++"
export AR_x86_64_linux_android="llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="x86_64-linux-android21-clang"

# Verify required Rust targets are installed
echo "Checking Rust Android targets..."
for android_arch in "${!TARGETS[@]}"; do
    rust_target="${TARGETS[$android_arch]}"
    if ! rustup target list --installed | grep -q "$rust_target"; then
        echo "Installing Rust target: $rust_target"
        rustup target add "$rust_target"
    fi
done

# Change to alfis directory (relative to script location)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALFIS_DIR="$SCRIPT_DIR/alfis"

if [ ! -d "$ALFIS_DIR" ]; then
    echo "Error: alfis directory not found at $ALFIS_DIR"
    echo "Please run this script from the root of the Alfis-android project"
    exit 1
fi

cd "$ALFIS_DIR"

echo ""
echo "Building Rust native libraries for Android..."
echo "Project directory: $ALFIS_DIR"

# Clean previous builds
echo "Cleaning previous builds..."
cargo clean

# Build for each target
BUILD_SUCCESS=true
for android_arch in "${!TARGETS[@]}"; do
    rust_target="${TARGETS[$android_arch]}"
    echo ""
    echo "Building for $android_arch ($rust_target)..."
    
    # Build the library
    if cargo build --release --target "$rust_target"; then
        echo "✓ Successfully built $android_arch"
    else
        echo "✗ Failed to build $android_arch"
        BUILD_SUCCESS=false
    fi
done

if [ "$BUILD_SUCCESS" = false ]; then
    echo ""
    echo "✗ Some builds failed. Please check the errors above."
    exit 1
fi

echo ""
echo "✓ All native libraries built successfully!"
echo ""
echo "Copying libraries to Android jniLibs directory..."

# Copy built libraries to Android jniLibs directory
JNILIBS_DIR="$SCRIPT_DIR/android/app/src/main/jniLibs"

for android_arch in "${!TARGETS[@]}"; do
    rust_target="${TARGETS[$android_arch]}"
    src_lib="target/$rust_target/release/libalfis.so"
    dest_dir="$JNILIBS_DIR/$android_arch"
    
    if [ -f "$src_lib" ]; then
        mkdir -p "$dest_dir"
        cp "$src_lib" "$dest_dir/"
        echo "✓ Copied $android_arch library to $dest_dir/"
        
        # Show library info
        lib_size=$(du -h "$dest_dir/libalfis.so" | cut -f1)
        echo "  Library size: $lib_size"
    else
        echo "✗ Library not found: $src_lib"
        exit 1
    fi
done

echo ""
echo "🎉 Native library build completed successfully!"
echo ""
echo "Summary:"
echo "  User: $CURRENT_USER"
echo "  NDK: $NDK_VERSION"
echo "  Libraries built for: ${!TARGETS[*]}"
echo ""
echo "Next steps:"
echo "  cd android"
echo "  ./gradlew assembleDebug"
echo ""
echo "Or to build and install in one command:"
echo "  cd android && ./gradlew installDebug"
echo ""