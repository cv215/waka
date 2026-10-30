param([string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'))
# Enregistre les cles Google Maps sur ce poste, protegees par Windows (DPAPI, utilisateur courant).
# Les valeurs sont collees ici (Ctrl+V), jamais affichees, jamais ecrites en clair, jamais dans Git ni dans une conversation.
# Chaque valeur est controlee des qu'elle est collee (forme seulement) ; une valeur fausse est redemandee.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force
$asks = [ordered]@{
    'WAKA_GOOGLE_SERVER_KEY' = 'cle "waka-serveur" (Routes, Places, Geocoding)'
    'WAKA_GOOGLE_MAP_KEY'    = 'cle "waka-carte" (affichage de la carte)'
}
$first = $null
foreach ($name in $asks.Keys) {
    while ($true) {
        $value = Read-Host -AsSecureString ('Colle la ' + $asks[$name])
        $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
        try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
        $ok = $plain -cmatch '^AIza[0-9A-Za-z_-]{35}$'
        $same = $null -ne $first -and $plain -ceq $first
        $length = $plain.Length
        if ($null -eq $first -and $ok) { $first = $plain }
        $plain = $null
        if ($length -eq 0) { Write-Host '  Rien n a ete colle. Copie la cle dans Google Cloud puis recommence (Ctrl+V).'; continue }
        if (-not $ok) { Write-Host ('  Refuse : ' + $length + ' caracteres. Une cle Google commence par AIza et fait 39 caracteres. Recopie-la.'); continue }
        if ($same) { Write-Host '  Refuse : c est la meme cle que la precedente. Copie l autre cle.'; continue }
        Write-Host '  OK (39 caracteres).'
        $value | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets ($name + '.dpapi'))
        break
    }
}
$first = $null
Write-Host 'Cles Google enregistrees sur ce poste. Le serveur local les utilisera au prochain demarrage avec -LocalApp.'
