#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}

mkdir -p build/native
clang++ -fobjc-arc -shared -mmacosx-version-min=13.0 \
  -o build/native/libmetalrender.dylib \
  src/main/resources/native/metalrender.mm \
  src/main/resources/native/meshshader.mm \
  -framework Cocoa -framework Metal -framework QuartzCore \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  -MJ src/main/resources/native/compile_commands.json

cp build/native/libmetalrender.dylib src/main/resources/