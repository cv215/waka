param([string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'),
      [string]$Python = 'C:\Users\frang\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe')
# Lance l'apercu web (iPhone sur le meme Wi-Fi) avec la cle "waka-carte" protegee de ce poste, sans jamais l'afficher.
# Sans cle enregistree (tools/save-google-maps-keys.ps1), l'apercu marche sans carte.
$ErrorActionPreference = 'Stop'
$file = Join-Path $PrivateRoot 'Secrets\WAKA_GOOGLE_MAP_KEY.dpapi'
$previous = [Environment]::GetEnvironmentVariable('WAKA_GOOGLE_MAP_KEY', 'Process')
try {
    if (Test-Path -LiteralPath $file) {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR((Get-Content -LiteralPath $file | ConvertTo-SecureString))
        try { [Environment]::SetEnvironmentVariable('WAKA_GOOGLE_MAP_KEY', [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer), 'Process') }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        Write-Host 'Carte : Google (cle waka-carte)'
    } else {
        Write-Host 'Carte : absente (aucune cle enregistree)'
    }
    & $Python -I (Join-Path $PSScriptRoot 'serve-web-preview.py')
} finally {
    [Environment]::SetEnvironmentVariable('WAKA_GOOGLE_MAP_KEY', $previous, 'Process')
}
