param([string]$Cloudflared = (Join-Path $env:LOCALAPPDATA 'WAKA\tools\cloudflared-2026.10.1\cloudflared.exe'))
# Lien de test temporaire (https://xxxx.trycloudflare.com) vers l'apercu web de ce poste (port 8090), pour un testeur
# qui n'est pas sur le meme Wi-Fi. Gratuit, sans compte. Le lien change a chaque lancement et s'arrete avec Ctrl+C.
# A lancer apres le serveur (start-dev-backend.ps1 -LocalApp) et l'apercu (start-web-preview.ps1). Donnees de test seulement.
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Cloudflared)) { throw 'cloudflared absent : voir tools/README.md.' }
Write-Host 'Lien de test en cours de creation : l adresse https://...trycloudflare.com s affiche ci-dessous.'
Write-Host 'Ne la donnez qu a vos testeurs ; Ctrl+C pour l arreter.'
& cmd /c "`"$Cloudflared`" tunnel --no-autoupdate --url http://localhost:8090 2>&1"
