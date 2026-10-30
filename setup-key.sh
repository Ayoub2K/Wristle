#!/bin/sh
# Sends the Claude API key from local.properties to the watch over adb (macOS/Linux/Git Bash).
set -eu
cd "$(dirname "$0")"

key=$(sed -n "s/^claude.apiKey=//p" local.properties | sed "s/'/'\\\\''/g")
[ -n "$key" ] || { echo "claude.apiKey not found in local.properties" >&2; exit 1; }

adb shell "am start -n dev.ayoub.wristle/.KeySetupActivity --es api_key '$key'"
