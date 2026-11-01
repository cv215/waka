param([string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))
# Installe l'APK de test sur un vrai telephone Android branche en USB (debogage USB autorise sur le telephone).
# Le telephone joint le serveur local de ce PC par le cable ("adb reverse") : rien n'est ouvert sur Internet ni sur le Wi-Fi.
# A relancer apres chaque rebranchement du cable : le renvoi du port s'arrete quand le telephone est debranche.
$ErrorActionPreference = 'Stop'
$phones = @(& $Adb devices | Select-String -Pattern '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value } |
    Where-Object { $_ -notlike 'emulator-*' })
if ($phones.Count -eq 0) { throw 'Aucun telephone pret : branche-le, deverrouille-le et accepte "Autoriser le debogage USB".' }
if ($phones.Count -gt 1) { throw 'Plusieurs telephones branches : n en garder qu un.' }
$phone = $phones[0]
& (Join-Path $PSScriptRoot 'build-android-preview.ps1') -Phone
$apk = Join-Path $PSScriptRoot '..\apps\mobile\build\app\outputs\flutter-apk\app-debug.apk'
& $Adb -s $phone install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Installation refusee par le telephone.' }
& $Adb -s $phone reverse tcp:8080 tcp:8080
if ($LASTEXITCODE -ne 0) { throw 'Renvoi du port 8080 impossible.' }
Write-Host 'WAKA est installe. Lance le serveur local (start-dev-backend.ps1), puis ouvre WAKA sur le telephone.'
