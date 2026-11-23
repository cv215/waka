param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$MavenHome
)
# Construit le serveur en lancant AUSSI les tests qui ont besoin de la vraie base (classes *MySqlVerificationTest),
# sur la base de test waka_test avec le compte waka_test_runner. Sans ce script, ces tests sont ignores ("skipped").
# Le mot de passe est lu depuis la protection Windows et n'est donne qu'au processus Maven, jamais affiche.
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
Assert-WakaMysqlInstalled
Assert-WakaMysqlProcessScope
$wakaRuntime = Join-Path $wakaMysqlRoot 'backend-local'
$null = New-Item -ItemType Directory -Path $wakaRuntime -Force
$wakaStore = Join-Path $wakaRuntime 'mysql-ca-tests.p12'
if (Test-Path -LiteralPath $wakaStore) { Remove-Item -LiteralPath $wakaStore -Force }
# Store holding only the PUBLIC certificate of the local CA.
& cmd /c "`"$(Join-Path $JavaHome 'bin\keytool.exe')`" -importcert -noprompt -alias waka-local-ca -storetype PKCS12 -storepass changeit -file `"$(Join-Path $wakaMysqlInstance 'data\ca.pem')`" -keystore `"$wakaStore`" >nul 2>nul"
if ($LASTEXITCODE -ne 0) { throw 'Import du certificat local impossible.' }
$wakaUrl = 'jdbc:mysql://127.0.0.1:3306/waka_test?sslMode=VERIFY_CA&allowPublicKeyRetrieval=false&connectTimeout=5000&socketTimeout=5000&trustCertificateKeyStoreUrl=' +
    [Uri]::EscapeDataString(([Uri]::new($wakaStore)).AbsoluteUri) + '&trustCertificateKeyStorePassword=changeit&trustCertificateKeyStoreType=PKCS12'
$wakaNames = 'WAKA_VERIFY_MYSQL', 'WAKA_DB_URL', 'WAKA_DB_USER', 'WAKA_DB_PASSWORD'
$wakaPrevious = @{}
foreach ($wakaName in $wakaNames) { $wakaPrevious[$wakaName] = [Environment]::GetEnvironmentVariable($wakaName, 'Process') }
try {
    [Environment]::SetEnvironmentVariable('WAKA_VERIFY_MYSQL', 'true', 'Process')
    [Environment]::SetEnvironmentVariable('WAKA_DB_URL', $wakaUrl, 'Process')
    [Environment]::SetEnvironmentVariable('WAKA_DB_USER', 'waka_test_runner', 'Process')
    [Environment]::SetEnvironmentVariable('WAKA_DB_PASSWORD', (Read-WakaMysqlSecret 'waka_test_runner'), 'Process')
    # The tests expect the tables: the server creates them (Flyway) when it starts once with the test profile.
    $wakaJar = Join-Path $PSScriptRoot '..\..\backend\target\waka-backend-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $wakaJar)) {
        [Environment]::SetEnvironmentVariable('WAKA_VERIFY_MYSQL', $null, 'Process')
        & (Join-Path $PSScriptRoot '..\build-backend.ps1') -JavaHome $JavaHome -MavenHome $MavenHome
        [Environment]::SetEnvironmentVariable('WAKA_VERIFY_MYSQL', 'true', 'Process')
    }
    $wakaLog = Join-Path $wakaRuntime 'migration-tests.log'
    $wakaServer = Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList @('-jar', ('"' + (Resolve-Path $wakaJar) + '"'), '--spring.profiles.active=test', '--server.port=0') `
        -RedirectStandardOutput $wakaLog -RedirectStandardError ($wakaLog + '.err') -WindowStyle Hidden -PassThru
    try {
        $wakaReady = $false
        for ($i = 0; $i -lt 240 -and -not $wakaServer.HasExited; $i++) {
            Start-Sleep -Milliseconds 500
            $wakaText = Get-Content -LiteralPath $wakaLog -Raw -ErrorAction SilentlyContinue
            if ($wakaText -match 'APPLICATION_READY') { $wakaReady = $true; break }
            if ($wakaText -match 'APPLICATION_STARTUP_REJECTED') { break }
        }
        if (-not $wakaReady) { throw ('Les tables de test n ont pas pu etre creees ; voir ' + $wakaLog) }
        Write-Host 'Base de test a jour (tables creees ou deja presentes).'
    } finally {
        if (-not $wakaServer.HasExited) { Stop-Process -Id $wakaServer.Id -Force -Confirm:$false }
    }
    & (Join-Path $PSScriptRoot '..\build-backend.ps1') -JavaHome $JavaHome -MavenHome $MavenHome
} finally {
    foreach ($wakaName in $wakaNames) { [Environment]::SetEnvironmentVariable($wakaName, $wakaPrevious[$wakaName], 'Process') }
}
