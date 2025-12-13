#!/bin/bash
cd "$(dirname "$0")"
rm -f run/logs/latest.log
./gradlew runClient
