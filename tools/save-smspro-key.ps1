param([string]$PrivateRoot = (& (Join-Path $PSScriptRoot 'private-root.ps1')))
# Enregistre la cle d'API SmsPro et le nom d'expediteur sur ce poste, la cle protegee par Windows (DPAPI, utilisateur courant).
# La cle est tapee ici, jamais affichee, jamais ecrite en clair, jamais dans Git ni dans une conversation.
# Une cle smspro_test_... ne fait rien partir et ne coute rien ; une cle reelle envoie de vrais SMS factures.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force
while ($true) {
    $value = Read-Host -AsSecureString 'Colle la cle d API SmsPro (elle commence par smspro_)'
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
    $ok = $plain -cmatch '^smspro_[A-Za-z0-9_-]{9,293}$'
    $test = $plain -clike 'smspro_test_*'
    $length = $plain.Length
    $plain = $null
    if ($length -eq 0) { Write-Host '  Rien n a ete colle. Copie la cle dans SmsPro (Developpeurs) puis recommence.'; continue }
    if (-not $ok) { Write-Host ('  Refuse : ' + $length + ' caracteres, ce n est pas une cle SmsPro. Recopie-la.'); continue }
    $value | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets 'WAKA_SMSPRO_KEY.dpapi')
    Write-Host ('  OK (' + $length + ' caracteres, ' + $(if ($test) { 'cle d essai : aucun SMS ne partira' } else { 'cle reelle : de vrais SMS seront factures' }) + ').')
    break
}
while ($true) {
    $sender = (Read-Host 'Nom d expediteur declare chez SmsPro, exactement comme affiche (par exemple WAKA)').Trim()
    if ($sender -cmatch '^[A-Za-z][A-Za-z0-9]{0,10}$') { break }
    Write-Host '  Refuse : 1 a 11 lettres ou chiffres, sans espace, commencant par une lettre.'
}
ConvertTo-SecureString $sender -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets 'WAKA_SMSPRO_SENDER.dpapi')
Write-Host 'SmsPro enregistre sur ce poste. Le serveur local l utilisera au prochain demarrage avec -LocalApp.'
