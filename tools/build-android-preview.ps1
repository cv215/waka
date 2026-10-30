param([string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'),
      [string]$Flutter = 'C:\src\flutter\flutter\bin\flutter.bat')
# Construit l'APK de test (emulateur, serveur local 10.0.2.2:8080) avec la cle "waka-carte" protegee de ce poste.
# La cle n'est jamais affichee ni ecrite dans les sources ; sans cle, l'APK marche avec une carte vide.
$ErrorActionPreference = 'Stop'
$file = Join-Path $PrivateRoot 'Secrets\WAKA_GOOGLE_MAP_KEY.dpapi'
$previous = [Environment]::GetEnvironmentVariable('WAKA_GOOGLE_MAP_KEY', 'Process')
try {
    if (Test-Path -LiteralPath $file) {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR((Get-Content -LiteralPath $file | ConvertTo-SecureString))
        try { [Environment]::SetEnvironmentVariable('WAKA_GOOGLE_MAP_KEY', [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer), 'Process') }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
    Push-Location (Join-Path $PSScriptRoot '..\apps\mobile')
    try {
        & $Flutter --no-version-check --suppress-analytics build apk --debug --no-pub --dart-define=WAKA_ENV=dev --dart-define=WAKA_API_BASE_URL=http://10.0.2.2:8080
        if ($LASTEXITCODE -ne 0) { throw 'Construction de l APK en echec.' }
    } finally { Pop-Location }
} finally {
    [Environment]::SetEnvironmentVariable('WAKA_GOOGLE_MAP_KEY', $previous, 'Process')
}
