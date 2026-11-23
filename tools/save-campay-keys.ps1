param([string]$PrivateRoot = (& (Join-Path $PSScriptRoot 'private-root.ps1')))
# Enregistre le jeton permanent et la cle de webhook d'une application CamPay (demo) sur ce poste,
# proteges par Windows (DPAPI, utilisateur courant). Les valeurs sont tapees ici, jamais affichees,
# jamais ecrites en clair, jamais dans Git ni dans une conversation.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force
$asks = [ordered]@{
    'WAKA_CAMPAY_TOKEN'       = @('le jeton d acces permanent (APP KEYS, "Permanent access token")', '^[A-Za-z0-9]{20,200}$')
    'WAKA_CAMPAY_WEBHOOK_KEY' = @('la cle de webhook (Webhook key) de la meme application', '^\S{8,300}$')
}
foreach ($name in $asks.Keys) {
    while ($true) {
        $value = Read-Host -AsSecureString ('Colle ' + $asks[$name][0])
        $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
        try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
        $ok = $plain -cmatch $asks[$name][1]
        $length = $plain.Length
        $plain = $null
        if ($length -eq 0) { Write-Host '  Rien n a ete colle. Copie la valeur dans CamPay puis recommence.'; continue }
        if (-not $ok) { Write-Host ('  Refuse : ' + $length + ' caracteres, ce n est pas la bonne valeur. Recopie-la dans CamPay.'); continue }
        $value | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets ($name + '.dpapi'))
        Write-Host ('  OK (' + $length + ' caracteres).')
        break
    }
}
Write-Host 'Cles CamPay enregistrees sur ce poste. Le serveur local utilisera CamPay (demo) au prochain demarrage avec -LocalApp.'
