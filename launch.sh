#!/bin/bash
cd "$(dirname "$0")"
rm -f run/logs/latest.log

# Use Java 21 for Fabric Loom
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

# Check for GL2Metal flag
if [[ "$1" == "gl2metal" || "$*" == *"gl2metal"* ]]; then
    echo "Launching with GL2Metal mode enabled..."
    ./gradlew runClient -Pgl2metal
else
    ./gradlew runClient
fi
