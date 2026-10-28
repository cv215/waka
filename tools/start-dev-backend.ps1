param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'),
    [ValidateSet('dev', 'test')][string]$Profile = 'dev',
    [ValidateRange(0, 65535)][int]$Port = 8080
)
$ErrorActionPreference = 'Stop'
$wakaRepository = Split-Path -Parent $PSScriptRoot
$wakaJar = Join-Path $wakaRepository 'backend\target\waka-backend-0.0.1-SNAPSHOT.jar'
$wakaJava = Join-Path $JavaHome 'bin\java.exe'
$wakaKeytool = Join-Path $JavaHome 'bin\keytool.exe'
$wakaPrivateHelper = Join-Path $PrivateRoot 'mysql-local\mysql-common.ps1'
$wakaCa = Join-Path $PrivateRoot 'mysql-local\data\ca.pem'
foreach ($wakaRequired in @($wakaJar, $wakaJava, $wakaKeytool, $wakaPrivateHelper, $wakaCa)) {
    if (-not (Test-Path -LiteralPath $wakaRequired -PathType Leaf)) {
        throw 'Un fichier requis manque : compiler le serveur et préparer MySQL selon le guide local.'
    }
}
# Le helper privé lit DPAPI en mémoire ; aucune valeur n'est écrite ou passée en argument.
. $wakaPrivateHelper
Assert-WakaMysqlProcessScope
Assert-WakaMysqlListeners
$wakaRuntime = Join-Path $PrivateRoot 'backend-local'
$null = New-Item -ItemType Directory -Path $wakaRuntime -Force
$wakaTruststore = Join-Path $wakaRuntime 'mysql-ca.p12'
# Ce magasin ne contient que le certificat PUBLIC, jamais une clé privée.
# Il est recréé dans un fichier temporaire pour prendre en compte une rotation de CA.
$wakaTemporaryStore = Join-Path $wakaRuntime ([Guid]::NewGuid().ToString('N') + '.p12')
try {
    & $wakaKeytool -importcert -noprompt -alias waka-local-ca -storetype PKCS12 -storepass changeit -file $wakaCa -keystore $wakaTemporaryStore *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Import du certificat local impossible.' }
    Move-Item -LiteralPath $wakaTemporaryStore -Destination $wakaTruststore -Force
} finally {
    if (Test-Path -LiteralPath $wakaTemporaryStore) { Remove-Item -LiteralPath $wakaTemporaryStore }
}
$wakaStoreUrl = ([Uri]::new($wakaTruststore)).AbsoluteUri
$wakaDatabase = if ($Profile -eq 'test') { 'waka_test' } else { 'waka_local' }
$wakaAccount = if ($Profile -eq 'test') { 'waka_test_runner' } else { 'waka_dev' }
$wakaJdbcUrl = 'jdbc:mysql://127.0.0.1:3306/' + $wakaDatabase + '?sslMode=VERIFY_CA&allowPublicKeyRetrieval=false&connectTimeout=5000&socketTimeout=5000&trustCertificateKeyStoreUrl=' + [Uri]::EscapeDataString($wakaStoreUrl) + '&trustCertificateKeyStorePassword=changeit&trustCertificateKeyStoreType=PKCS12'
$wakaEnvironmentNames = @('WAKA_DB_URL', 'WAKA_DB_USER', 'WAKA_DB_PASSWORD')
$wakaPrevious = @{}
foreach ($wakaName in $wakaEnvironmentNames) { $wakaPrevious[$wakaName] = [Environment]::GetEnvironmentVariable($wakaName, 'Process') }
try {
    [Environment]::SetEnvironmentVariable('WAKA_DB_URL', $wakaJdbcUrl, 'Process')
    [Environment]::SetEnvironmentVariable('WAKA_DB_USER', $wakaAccount, 'Process')
    [Environment]::SetEnvironmentVariable('WAKA_DB_PASSWORD', (Read-WakaMysqlSecret $wakaAccount), 'Process')
    & $wakaJava -jar $wakaJar "--spring.profiles.active=$Profile" "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw 'Arret du serveur avec une erreur ; consulter les journaux locaux proteges.' }
} finally {
    foreach ($wakaName in $wakaEnvironmentNames) { [Environment]::SetEnvironmentVariable($wakaName, $wakaPrevious[$wakaName], 'Process') }
    $wakaPrevious.Clear()
}
