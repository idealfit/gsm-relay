$ErrorActionPreference = "Stop"

$repoRoot = $PSScriptRoot

function Copy-IfExists {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if (Test-Path $Source) {
        Copy-Item -Force $Source $Destination
        Write-Host "Copied: $Destination"
    } else {
        Write-Warning "Missing artifact: $Source"
    }
}

$gatewayApk = Join-Path $repoRoot "android\app\build\outputs\apk\gateway\debug\app-gateway-debug.apk"
$clientApk = Join-Path $repoRoot "android\app\build\outputs\apk\client\debug\app-client-debug.apk"
$gatewayOut = Join-Path $repoRoot "GSMRelayGateway-debug.apk"
$clientOut = Join-Path $repoRoot "GSMRelayClient-debug.apk"

Copy-IfExists -Source $gatewayApk -Destination $gatewayOut
Copy-IfExists -Source $clientApk -Destination $clientOut

$exePath = Join-Path $repoRoot "windows-ui\GSMRelayDesktop\bin\Debug\net8.0-windows\GSMRelayDesktop.exe"
$shortcutPath = Join-Path $repoRoot "GSMRelayDesktop.lnk"

if (Test-Path $exePath) {
    $ws = New-Object -ComObject WScript.Shell
    $shortcut = $ws.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = (Resolve-Path $exePath).Path
    $shortcut.WorkingDirectory = Split-Path $exePath
    $shortcut.IconLocation = "$exePath,0"
    $shortcut.Save()
    Write-Host "Updated shortcut: $shortcutPath"
} else {
    Write-Warning "Missing Windows exe: $exePath"
}

