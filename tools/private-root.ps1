# Dossier prive WAKA de ce poste (base MySQL locale, outils, cles protegees par Windows), jamais dans Git.
# Ordre : variable WAKA_PRIVATE_ROOT, puis %USERPROFILE%\WAKA-local, puis %LOCALAPPDATA%\WAKA.
# %USERPROFILE%\WAKA-local est prefere : une app Windows empaquetee range ce qu'elle ecrit sous AppData
# dans son propre espace, que Windows supprime quand l'app change d'identite. Hors d'AppData, rien n'est redirige.
if ($env:WAKA_PRIVATE_ROOT) { return $env:WAKA_PRIVATE_ROOT }
$durable = Join-Path $env:USERPROFILE 'WAKA-local'
if (Test-Path -LiteralPath $durable) { return $durable }
return (Join-Path $env:LOCALAPPDATA 'WAKA')
