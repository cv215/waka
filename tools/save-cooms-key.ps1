param([string]$PrivateRoot = (& (Join-Path $PSScriptRoot 'private-root.ps1')))
# Enregistre la cle d'API Cooms sur ce poste, protegee par Windows (DPAPI, utilisateur courant).
# La cle est tapee ici, jamais affichee, jamais ecrite en clair, jamais dans Git ni dans une conversation.
# Une cle coom_live_... envoie de vrais SMS, pris sur le credit du compte Cooms.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force
while ($true) {
    $value = Read-Host -AsSecureString 'Colle la nouvelle cle d API Cooms (elle commence par coom_)'
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
    $ok = $plain -cmatch '^coom_[a-z]+_[A-Za-z0-9]{16,200}$'
    $length = $plain.Length
    $plain = $null
    if ($length -eq 0) { Write-Host '  Rien n a ete colle. Copie la cle dans la console Cooms puis recommence.'; continue }
    if (-not $ok) { Write-Host ('  Refuse : ' + $length + ' caracteres, ce n est pas une cle Cooms. Recopie-la.'); continue }
    $value | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets 'WAKA_COOMS_KEY.dpapi')
    Write-Host ('  OK (' + $length + ' caracteres).')
    break
}
while ($true) {
    $url = (Read-Host 'URL de l API (Passerelle) affichee dans la console Cooms, par exemple https://gw-89.cooms.app').Trim().TrimEnd('/')
    if ($url -cmatch '^https://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.cooms\.app$') { break }
    Write-Host '  Refuse : une adresse https://....cooms.app, sans rien apres.'
}
ConvertTo-SecureString $url -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets 'WAKA_COOMS_URL.dpapi')
Write-Host 'Cle Cooms enregistree sur ce poste. Le serveur local l utilisera au prochain demarrage avec -LocalApp.'
