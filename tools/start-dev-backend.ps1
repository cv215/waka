param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$PrivateRoot = (Join-Path $env:LOCALAPPDATA 'WAKA'),
    [ValidateSet('dev', 'test')][string]$Profile = 'dev',
    [ValidateRange(0, 65535)][int]$Port = 8080,
    # Application complete pour essai sur ce poste : codes de connexion dans un fichier prive, distances estimees,
    # paiements simules (ou bac a sable Fapshi si ses cles privees existent). Jamais pour de vrais utilisateurs.
    [switch]$LocalApp
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
    # Passe par cmd : sous Windows PowerShell 5.1, le message de keytool sur stderr arreterait le script.
    & cmd /c "`"$wakaKeytool`" -importcert -noprompt -alias waka-local-ca -storetype PKCS12 -storepass changeit -file `"$wakaCa`" -keystore `"$wakaTemporaryStore`" >nul 2>nul"
    if ($LASTEXITCODE -ne 0) { throw 'Import du certificat local impossible.' }
    Move-Item -LiteralPath $wakaTemporaryStore -Destination $wakaTruststore -Force
} finally {
    if (Test-Path -LiteralPath $wakaTemporaryStore) { Remove-Item -LiteralPath $wakaTemporaryStore }
}
$wakaStoreUrl = ([Uri]::new($wakaTruststore)).AbsoluteUri
$wakaDatabase = if ($Profile -eq 'test') { 'waka_test' } else { 'waka_local' }
$wakaAccount = if ($Profile -eq 'test') { 'waka_test_runner' } else { 'waka_dev' }
$wakaJdbcUrl = 'jdbc:mysql://127.0.0.1:3306/' + $wakaDatabase + '?sslMode=VERIFY_CA&allowPublicKeyRetrieval=false&connectTimeout=5000&socketTimeout=5000&trustCertificateKeyStoreUrl=' + [Uri]::EscapeDataString($wakaStoreUrl) + '&trustCertificateKeyStorePassword=changeit&trustCertificateKeyStoreType=PKCS12'
$wakaFapshiNames = @('WAKA_FAPSHI_COLLECT_USER', 'WAKA_FAPSHI_COLLECT_KEY', 'WAKA_FAPSHI_PAYOUT_USER', 'WAKA_FAPSHI_PAYOUT_KEY', 'WAKA_FAPSHI_WEBHOOK_SECRET', 'WAKA_GOOGLE_SERVER_KEY')
$wakaEnvironmentNames = @('WAKA_DB_URL', 'WAKA_DB_USER', 'WAKA_DB_PASSWORD', 'WAKA_LOCAL_APP', 'WAKA_LOCAL_MASTER_KEY', 'WAKA_LOCAL_DIR') + $wakaFapshiNames
if ($LocalApp -and $Profile -ne 'dev') { throw 'L application complete locale ne demarre qu avec le profil dev.' }
$wakaPrevious = @{}
foreach ($wakaName in $wakaEnvironmentNames) { $wakaPrevious[$wakaName] = [Environment]::GetEnvironmentVariable($wakaName, 'Process') }
try {
    [Environment]::SetEnvironmentVariable('WAKA_DB_URL', $wakaJdbcUrl, 'Process')
    [Environment]::SetEnvironmentVariable('WAKA_DB_USER', $wakaAccount, 'Process')
    [Environment]::SetEnvironmentVariable('WAKA_DB_PASSWORD', (Read-WakaMysqlSecret $wakaAccount), 'Process')
    if ($LocalApp) {
        # Cle maitresse protegee par Windows (DPAPI, utilisateur courant), creee une seule fois, jamais affichee.
        $wakaSecrets = Join-Path $PrivateRoot 'Secrets'
        $null = New-Item -ItemType Directory -Path $wakaSecrets -Force
        $wakaKeyFile = Join-Path $wakaSecrets 'local-app-master.dpapi'
        if (-not (Test-Path -LiteralPath $wakaKeyFile)) {
            $wakaBytes = New-Object byte[] 32
            [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($wakaBytes)
            ConvertTo-SecureString ([Convert]::ToBase64String($wakaBytes)) -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $wakaKeyFile
            [Array]::Clear($wakaBytes, 0, $wakaBytes.Length)
        }
        $wakaSecure = Get-Content -LiteralPath $wakaKeyFile | ConvertTo-SecureString
        $wakaPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($wakaSecure)
        try { [Environment]::SetEnvironmentVariable('WAKA_LOCAL_MASTER_KEY', [Runtime.InteropServices.Marshal]::PtrToStringBSTR($wakaPointer), 'Process') }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($wakaPointer) }
        $wakaAppDir = Join-Path $wakaRuntime 'app'
        $null = New-Item -ItemType Directory -Path $wakaAppDir -Force
        [Environment]::SetEnvironmentVariable('WAKA_LOCAL_DIR', $wakaAppDir, 'Process')
        [Environment]::SetEnvironmentVariable('WAKA_LOCAL_APP', 'on', 'Process')
        # Bac a sable Fapshi et cle Google si enregistrees (tools/save-fapshi-keys.ps1, tools/save-google-maps-keys.ps1).
        foreach ($wakaName in $wakaFapshiNames) {
            $wakaFile = Join-Path $wakaSecrets ($wakaName + '.dpapi')
            if (-not (Test-Path -LiteralPath $wakaFile)) { continue }
            $wakaPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR((Get-Content -LiteralPath $wakaFile | ConvertTo-SecureString))
            try { [Environment]::SetEnvironmentVariable($wakaName, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($wakaPointer), 'Process') }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($wakaPointer) }
        }
        Write-Host ($(if (Test-Path -LiteralPath (Join-Path $wakaSecrets 'WAKA_FAPSHI_COLLECT_USER.dpapi')) { 'Paiements : bac a sable Fapshi' } else { 'Paiements : simules' }))
        Write-Host ($(if (Test-Path -LiteralPath (Join-Path $wakaSecrets 'WAKA_GOOGLE_SERVER_KEY.dpapi')) { 'Distances : Google (par la route)' } else { 'Distances : vol d oiseau x 1,3' }))
        Write-Host ('Codes de connexion : ' + (Join-Path $wakaAppDir 'codes-connexion-local.txt'))
    }
    & $wakaJava -jar $wakaJar "--spring.profiles.active=$Profile" "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw 'Arret du serveur avec une erreur ; consulter les journaux locaux proteges.' }
} finally {
    foreach ($wakaName in $wakaEnvironmentNames) { [Environment]::SetEnvironmentVariable($wakaName, $wakaPrevious[$wakaName], 'Process') }
    $wakaPrevious.Clear()
}
