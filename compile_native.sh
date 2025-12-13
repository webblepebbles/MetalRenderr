#!/bin/bash
set -e

echo "Compiling shaders..."
xcrun -sdk macosx metal -c src/main/resources/native/shaders/metalrender.metal -o src/main/resources/native/shaders/metalrender.air
xcrun -sdk macosx metallib src/main/resources/native/shaders/metalrender.air -o src/main/resources/shaders.metallib
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
    -o src/main/resources/libmetalrender.dylib

echo "Native library compiled to src/main/resources/libmetalrender.dylib"
