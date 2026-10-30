# Sends the Claude API key from local.properties to the watch over adb.
# Usage: .\setup-key.ps1   (watch must already be connected via `adb connect`)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$line = Get-Content local.properties | Where-Object { $_ -match '^claude\.apiKey=' } | Select-Object -First 1
if (-not $line) { throw 'claude.apiKey not found in local.properties' }
$key = $line.Substring('claude.apiKey='.Length).Trim()

adb shell "am start -n dev.ayoub.wristle/.KeySetupActivity --es api_key '$key'"
