#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}

mkdir -p build/native
clang -fobjc-arc -shared \
  -o build/native/libmetalrender.dylib \
  src/main/native/metalrender.m \
  -framework Cocoa -framework Metal -framework QuartzCore \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin"

cp build/native/libmetalrender.dylib src/client/resources/