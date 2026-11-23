param([string]$PrivateRoot = (& (Join-Path $PSScriptRoot 'private-root.ps1')))
# Enregistre le jeton permanent et la cle de webhook d'une application CamPay (demo) sur ce poste,
# proteges par Windows (DPAPI, utilisateur courant). Les valeurs sont tapees ici, jamais affichees,
# jamais ecrites en clair, jamais dans Git ni dans une conversation.
# Les espaces et retours a la ligne ajoutes par le copier-coller, au debut ou a la fin, sont retires.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force
$asks = [ordered]@{
    'WAKA_CAMPAY_TOKEN'       = @('le jeton d acces permanent (Permanent Access token)', '^[!-~]{20,300}$')
    'WAKA_CAMPAY_WEBHOOK_KEY' = @('la cle de webhook (App webhook key)', '^[\x21-\x7E]{8,300}$')
}
foreach ($name in $asks.Keys) {
    while ($true) {
        $value = Read-Host -AsSecureString ('Colle ' + $asks[$name][0])
        $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
        try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
        $plain = $plain.Trim()
        $length = $plain.Length
        if ($length -eq 0) { $plain = $null; Write-Host '  Rien n a ete colle. Clique sur Copy dans CamPay puis recommence.'; continue }
        if (-not ($plain -cmatch $asks[$name][1])) {
            $kinds = @()
            if ($plain -match '\s') { $kinds += 'des espaces au milieu' }
            if ($plain -match '[^\x21-\x7E\s]') { $kinds += 'des lettres accentuees ou signes speciaux' }
            if ($length -lt 8) { $kinds += 'trop court' }
            $plain = $null
            Write-Host ('  Refuse : ' + $length + ' caracteres' + $(if ($kinds) { ', ' + ($kinds -join ', ') } else { '' }) + '. Ce n est sans doute pas la bonne case : recopie avec le bouton Copy.')
            continue
        }
        ConvertTo-SecureString $plain -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets ($name + '.dpapi'))
        $plain = $null
        Write-Host ('  OK (' + $length + ' caracteres).')
        break
    }
}
Write-Host 'Cles CamPay enregistrees sur ce poste. Le serveur local utilisera CamPay (demo) au prochain demarrage avec -LocalApp.'
