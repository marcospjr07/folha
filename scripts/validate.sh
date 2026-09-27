#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew test lint assembleDebug "$@"
