param([string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'),
      [string]$Flutter = 'C:\src\flutter\flutter\bin\flutter.bat',
      [switch]$Phone)
# Construit l'APK de test avec la cle "waka-carte" protegee de ce poste. Serveur local : 10.0.2.2:8080 pour l'emulateur ;
# avec -Phone, 127.0.0.1:8080, qu'un vrai telephone branche en USB atteint par "adb reverse" (voir install-android-phone.ps1).
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
    $api = if ($Phone) { 'http://127.0.0.1:8080' } else { 'http://10.0.2.2:8080' }
    Push-Location (Join-Path $PSScriptRoot '..\apps\mobile')
    try {
        # Through cmd: Windows PowerShell 5.1 would stop on Java's harmless warnings written to stderr.
        & cmd /c "`"$Flutter`" --no-version-check --suppress-analytics build apk --debug --no-pub --dart-define=WAKA_ENV=dev --dart-define=WAKA_API_BASE_URL=$api 2>&1"
        if ($LASTEXITCODE -ne 0) { throw 'Construction de l APK en echec.' }
    } finally { Pop-Location }
} finally {
    [Environment]::SetEnvironmentVariable('WAKA_GOOGLE_MAP_KEY', $previous, 'Process')
}
