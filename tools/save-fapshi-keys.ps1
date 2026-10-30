param([string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'))
# Enregistre les cles de bac a sable Fapshi sur ce poste, protegees par Windows (DPAPI, utilisateur courant).
# Les valeurs sont tapees ici, jamais affichees, jamais ecrites en clair, jamais dans Git ni dans une conversation.
# Chaque valeur est controlee des qu'elle est collee (forme seulement) ; une valeur fausse est redemandee.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force
$asks = [ordered]@{
    'WAKA_FAPSHI_COLLECT_USER'   = @('api user du service (encaissement)', '^[A-Za-z0-9_.-]{4,200}$')
    'WAKA_FAPSHI_COLLECT_KEY'    = @('api key du service (encaissement)', '^[A-Za-z0-9_.-]{8,300}$')
    'WAKA_FAPSHI_PAYOUT_USER'    = @('api user pour les retraits (le meme si un seul service)', '^[A-Za-z0-9_.-]{4,200}$')
    'WAKA_FAPSHI_PAYOUT_KEY'     = @('api key pour les retraits (la meme si un seul service)', '^[A-Za-z0-9_.-]{8,300}$')
    'WAKA_FAPSHI_WEBHOOK_SECRET' = @('secret des notifications invente (16 caracteres ou plus)', '^\S.{14,198}\S$')
}
$saved = @{}
foreach ($name in $asks.Keys) {
    while ($true) {
        $value = Read-Host -AsSecureString ('Colle ' + $asks[$name][0])
        $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
        try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
        $ok = $plain -cmatch $asks[$name][1]
        $length = $plain.Length
        $same = $saved.ContainsValue($plain) -and $name -in @('WAKA_FAPSHI_COLLECT_KEY', 'WAKA_FAPSHI_WEBHOOK_SECRET')
        $plain = $null
        if ($length -eq 0) { Write-Host '  Rien n a ete colle. Copie la valeur dans Fapshi puis recommence.'; continue }
        if (-not $ok) { Write-Host ('  Refuse : ' + $length + ' caracteres, avec des espaces ou des signes qui ne sont pas ceux d une cle. Le presse-papiers contenait autre chose : recopie la valeur dans Fapshi.'); continue }
        if ($same) { Write-Host '  Refuse : c est la meme valeur que la precedente. Copie la bonne valeur.'; continue }
        Write-Host ('  OK (' + $length + ' caracteres).')
        $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
        try { $saved[$name] = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
        $value | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets ($name + '.dpapi'))
        break
    }
}
$saved.Clear()
Write-Host 'Cles Fapshi enregistrees sur ce poste. Le serveur local les utilisera au prochain demarrage avec -LocalApp.'
