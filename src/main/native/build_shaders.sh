#!/usr/bin/env bash
set -euo pipefail

xcrun -sdk macosx metal -o src/main/resources/native/shaders/compiled/fragment.ir -c src/main/resources/native/shaders/fragment.metal
xcrun -sdk macosx metal -o src/main/resources/native/shaders/compiled/vertex.ir -c src/main/resources/native/shaders/vertex.metal
xcrun -sdk macosx metallib -o build/native/shaders.metallib src/main/resources/native/shaders/compiled/fragment.ir src/main/resources/native/shaders/compiled/vertex.ir

cp build/native/shaders.metallib src/main/resources/