$ErrorActionPreference = "Stop"

param(
    [switch]$SkipAndroid,
    [switch]$SkipWindows
)

$repoRoot = $PSScriptRoot

if (-not $SkipAndroid) {
    Push-Location (Join-Path $repoRoot "android")
    try {
        & .\gradlew.bat :app:assembleGatewayDebug :app:assembleClientDebug
    } finally {
        Pop-Location
    }
}

if (-not $SkipWindows) {
    Push-Location $repoRoot
    try {
        dotnet build "windows-ui\GSMRelayDesktop\GSMRelayDesktop.csproj" -c Debug
    } finally {
        Pop-Location
    }
}

& (Join-Path $repoRoot "sync-artifacts.ps1")

