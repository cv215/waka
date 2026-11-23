param([string]$PrivateRoot = (& (Join-Path $PSScriptRoot 'private-root.ps1')))
# Enregistre l'adresse API de paiement d'une application MoneyFusion et, si tu l'as, la cle privee des retraits,
# protegees par Windows (DPAPI, utilisateur courant). Les valeurs sont tapees ici, jamais affichees,
# jamais ecrites en clair, jamais dans Git ni dans une conversation.
# ATTENTION : MoneyFusion n'a pas de mode d'essai. Chaque paiement fait avec ces cles prend du VRAI argent.
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
            if ($Optional) { Write-Host '  Laisse vide : les retraits resteront refuses sans rien envoyer.'; return }
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
        Write-Host ('  OK (' + $plain.Length + ' caracteres).')
        $plain = $null
        return
    }
}

Save-WakaSecret 'WAKA_MONEYFUSION_PAY_URL' 'Colle le lien API de ton application (tableau de bord, API de paiement)' `
    '^https://([a-z0-9-]+\.)*moneyfusion\.net/[\x21\x22\x24-\x3E\x40-\x7E]{1,250}$' 'Il doit commencer par https:// et etre sur moneyfusion.net, sans ? a la fin.' $false

Save-WakaSecret 'WAKA_MONEYFUSION_PRIVATE_KEY' 'Colle la cle privee des retraits (Parametre), ou laisse vide' `
    '^[\x21-\x7E]{16,300}$' 'Recopie la cle entiere avec le bouton de copie.' $true
Write-Host 'MoneyFusion enregistre sur ce poste. Le serveur local l utilisera au prochain demarrage avec -LocalApp (VRAI argent).'
Write-Host 'Rappel : ajoute l adresse IP publique de ce poste dans ton application MoneyFusion, sinon tout sera refuse.'
