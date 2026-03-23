#!/bin/bash
echo "🔹 Starting Metal shader compilation..."
BUILD_DIR="build"
mkdir -p "$BUILD_DIR"
METAL_FILES=$(find . -name "*.metal")
for file in $METAL_FILES; do
    BASENAME=$(basename "$file" .metal)
    AIR_FILE="$BUILD_DIR/${BASENAME}.air"
    echo "Compiling $file → $AIR_FILE"
    xcrun -sdk macosx metal -c "$file" -o "$AIR_FILE"
    if [ $? -ne 0 ]; then
        echo "Compilation failed for $file"
        exit 1
    fi
done
echo "Linking all .air files into shaders.metallib..."
xcrun -sdk macosx metallib "$BUILD_DIR"/*.air -o "$BUILD_DIR/shaders.metallib"
if [ $? -eq 0 ]; then
    echo "Compilation complete."
    echo "Metallib located at: $BUILD_DIR/shaders.metallib"
else
    echo "Metallib creation failed."
    exit 1
fi