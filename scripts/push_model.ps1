# Dev shortcut: push exported model files to the phone over USB (adb).
# The app scans /sdcard/Android/data/com.offlinetutor/files/models on start and on "Rescan".
# Usage:  .\scripts\push_model.ps1 [-Dir model-export\out]
param([string]$Dir = "model-export\out")

$adb = Join-Path $PSScriptRoot "..\..\AndroidSdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$dest = "/sdcard/Android/data/com.offlinetutor/files/models"

& $adb shell mkdir -p $dest
Get-ChildItem $Dir -Include *.pte, tokenizer.model -Recurse | ForEach-Object {
    Write-Host "Pushing $($_.Name) ($([math]::Round($_.Length / 1MB)) MB)..."
    & $adb push $_.FullName "$dest/"
}
& $adb shell ls -l $dest
