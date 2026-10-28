param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$MavenHome,
    [string]$CacheDirectory = (Join-Path $env:LOCALAPPDATA 'WAKA\caches\maven'),
    [string]$SettingsFile = (Join-Path (Split-Path -Parent $PSScriptRoot) 'backend\.mvn\settings.xml'),
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$wakaBackend = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend'
$wakaJar = Join-Path $wakaBackend 'target\waka-backend-0.0.1-SNAPSHOT.jar'
$wakaMaven = Join-Path $MavenHome 'bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $wakaMaven -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe') -PathType Leaf)) {
    throw 'Java JDK et Maven doivent etre prepares avant la construction.'
}
if ((Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw) -notmatch 'JAVA_VERSION="21[.]') {
    throw 'Le serveur WAKA utilise le JDK 21 retenu.'
}
# Windows peut verrouiller ou alterer un JAR reconstruit pendant son execution.
# Lire seulement les processus Java ; aucune ligne de commande n'est affichee.
$wakaRunning = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
    Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($wakaJar, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
if ($wakaRunning.Count -gt 0) {
    throw 'Arreter WAKA avec Ctrl+C dans son terminal avant de reconstruire le serveur.'
}
$wakaArguments = @('-B', '-ntp', '-C', "-Dmaven.repo.local=$CacheDirectory")
if ($Offline) { $wakaArguments += '-o' }
if ($SettingsFile) {
    if (-not (Test-Path -LiteralPath $SettingsFile -PathType Leaf)) { throw 'Parametres Maven absents.' }
    $wakaArguments += @('-s', $SettingsFile, '-gs', $SettingsFile)
}
$wakaArguments += 'verify'
$wakaPreviousJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
Push-Location $wakaBackend
try {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'Process')
    & $wakaMaven @wakaArguments
    if ($LASTEXITCODE -ne 0) { throw 'Compilation ou verification du serveur echouee.' }
} finally {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $wakaPreviousJava, 'Process')
    Pop-Location
}
