#!/bin/bash
set -e

echo "Compiling shaders..."
# Compile terrain shader
xcrun -sdk macosx metal -c src/main/resources/native/shaders/metalrender.metal -o src/main/resources/native/shaders/metalrender.air
# Compile entity shader
xcrun -sdk macosx metal -c src/main/resources/native/shaders/entity.metal -o src/main/resources/native/shaders/entity.air
# Link both into metallib
xcrun -sdk macosx metallib src/main/resources/native/shaders/metalrender.air src/main/resources/native/shaders/entity.air -o src/main/resources/shaders.metallib
echo "Shaders compiled to src/main/resources/shaders.metallib"

echo "Compiling native library..."
JAVA_HOME=$(/usr/libexec/java_home)
echo "Using JAVA_HOME: $JAVA_HOME"

clang++ -O3 -std=c++17 -dynamiclib \
    -framework Metal -framework Foundation -framework Cocoa -framework IOKit -framework IOSurface -framework OpenGL -framework QuartzCore \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
    -I"src/main/resources/native" \
    src/main/resources/native/metalrender.mm \
    src/main/resources/native/meshshader.mm \
    src/main/resources/native/gl2metal.mm \
    -o src/main/resources/libmetalrender_debug_v2.dylib

echo "Native library compiled to src/main/resources/libmetalrender_debug_v2.dylib"
