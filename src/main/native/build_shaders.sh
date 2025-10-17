#!/usr/bin/env bash
set -euo pipefail

if [ ! -d "src/main/resources/native/shaders/compiled" ]
    mkdir src/main/resources/native/shaders/compiled
fi

xcrun -sdk macosx metal -o src/main/resources/native/shaders/compiled/fragment.ir -c src/main/resources/native/shaders/fragment.metal
xcrun -sdk macosx metal -o src/main/resources/native/shaders/compiled/vertex.ir -c src/main/resources/native/shaders/vertex.metal
xcrun -sdk macosx metal -o src/main/resources/native/shaders/compiled/occlusion_culling.ir -c src/main/resources/native/shaders/occlusion_culling.metal

xcrun -sdk macosx metallib -o build/native/shaders.metallib src/main/resources/native/shaders/compiled/fragment.ir src/main/resources/native/shaders/compiled/vertex.ir src/main/resources/native/shaders/compiled/occlusion_culling.ir

cp build/native/shaders.metallib src/main/resources/