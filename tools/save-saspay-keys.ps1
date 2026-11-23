param([string]$PrivateRoot = (& (Join-Path $PSScriptRoot 'private-root.ps1')))
# Enregistre la cle secrete SasPay, le secret de signature des notifications (facultatif) et l'adresse e-mail de contact
# des paiements, proteges par Windows (DPAPI, utilisateur courant). Les valeurs sont tapees ici, jamais affichees,
# jamais ecrites en clair, jamais dans Git ni dans une conversation.
# Une cle sk_test_ utilise le bac a sable (aucun vrai argent). Une cle sk_live_ prend du VRAI argent.
$ErrorActionPreference = 'Stop'
$secrets = Join-Path $PrivateRoot 'Secrets'
$null = New-Item -ItemType Directory -Path $secrets -Force

function Save-WakaSecret([string]$Name, [string]$Prompt, [string]$Pattern, [string]$Hint, [bool]$Optional) {
    while ($true) {
        $value = Read-Host -AsSecureString $Prompt
        $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
        try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
        $plain = $plain.Trim()
        if ($plain.Length -eq 0) {
            $plain = $null
            if ($Optional) { Write-Host '  Laisse vide.'; return $null }
            Write-Host '  Rien n a ete colle. Recommence.'
            continue
        }
        if (-not ($plain -cmatch $Pattern)) {
            $length = $plain.Length
            $plain = $null
            Write-Host ('  Refuse (' + $length + ' caracteres). ' + $Hint)
            continue
        }
        ConvertTo-SecureString $plain -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $secrets ($Name + '.dpapi'))
        $kind = if ($plain.StartsWith('sk_live_')) { 'live' } elseif ($plain.StartsWith('sk_test_')) { 'test' } else { '' }
        Write-Host ('  OK (' + $plain.Length + ' caracteres).')
        $plain = $null
        return $kind
    }
}

$kind = Save-WakaSecret 'WAKA_SASPAY_KEY' 'Colle la cle secrete SasPay (sk_test_... ou sk_live_...)' `
    '^sk_(live|test)_[\x21-\x7E]{8,300}$' 'Elle doit commencer par sk_test_ (bac a sable) ou sk_live_ (vrai argent).' $false
$null = Save-WakaSecret 'WAKA_SASPAY_WEBHOOK_SECRET' 'Colle le secret de signature des notifications (webhook), ou laisse vide' `
    '^[\x21-\x7E]{8,300}$' 'Recopie le secret entier.' $true
$null = Save-WakaSecret 'WAKA_SASPAY_EMAIL' 'Tape l adresse e-mail de contact des paiements (SasPay l exige pour chaque paiement)' `
    '^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}$' 'Une adresse e-mail valide, par exemple paiements@ton-domaine.com.' $false
if ($kind -eq 'live') {
    Write-Host 'SasPay enregistre : VRAI argent au prochain demarrage avec -LocalApp.'
    Write-Host 'Rappel : declare l adresse IP publique de ce poste dans SasPay, sinon les retraits seront refuses.'
} else {
    Write-Host 'SasPay (bac a sable) enregistre : aucun vrai argent. Au prochain demarrage avec -LocalApp, les paiements passent par SasPay.'
    Write-Host 'En bac a sable, le dernier chiffre du numero decide : 0 = reussi, 1 = echec, 2 = solde insuffisant, 9 = reste en attente.'
}
