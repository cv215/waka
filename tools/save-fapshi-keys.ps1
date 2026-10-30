param([string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'))
# Enregistre les cles de bac a sable Fapshi sur ce poste, protegees par Windows (DPAPI, utilisateur courant).
# Les valeurs sont tapees ici, jamais affichees, jamais ecrites en clair, jamais dans Git ni dans une conversation.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force
$names = [ordered]@{
    'WAKA_FAPSHI_COLLECT_USER'   = 'apiuser du service d encaissement'
    'WAKA_FAPSHI_COLLECT_KEY'    = 'apikey du service d encaissement'
    'WAKA_FAPSHI_PAYOUT_USER'    = 'apiuser du service de retraits'
    'WAKA_FAPSHI_PAYOUT_KEY'     = 'apikey du service de retraits'
    'WAKA_FAPSHI_WEBHOOK_SECRET' = 'secret des notifications (16 caracteres ou plus)'
}
foreach ($name in $names.Keys) {
    $value = Read-Host -AsSecureString ('Colle ' + $names[$name])
    if ($value.Length -eq 0) { throw 'Valeur vide : rien n a ete enregistre pour ' + $name }
    $value | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets ($name + '.dpapi'))
}
Write-Host 'Cles Fapshi enregistrees sur ce poste. Le serveur local les utilisera au prochain demarrage avec -LocalApp.'
