param(
    # Le zip officiel "Windows (x86, 64-bit), ZIP Archive" de MySQL 8.4 LTS, telecharge sur https://dev.mysql.com/downloads/mysql/
    [Parameter(Mandatory = $true)][string]$MysqlZip
)
# Installe la base MySQL locale de WAKA sur ce poste, une seule fois :
#   - serveur officiel dans le dossier prive, configure pour n ecouter que 127.0.0.1:3306, connexions chiffrees obligatoires ;
#   - bases waka_local (application) et waka_test (tests) ;
#   - comptes limites waka_dev et waka_test_runner, mots de passe aleatoires proteges par Windows, jamais affiches.
# Ensuite : tools\mysql\start-mysql.ps1 et tools\mysql\stop-mysql.ps1. Rien n est envoye sur Internet.
$ErrorActionPreference = 'Stop'
$wakaPrivate = & (Join-Path $PSScriptRoot '..\private-root.ps1')
$wakaInstance = Join-Path $wakaPrivate 'mysql-local'
if (Test-Path -LiteralPath (Join-Path $wakaInstance 'data')) { throw 'MySQL WAKA est deja installe sur ce poste (dossier mysql-local\data present) : rien n est modifie.' }
if (-not (Test-Path -LiteralPath $MysqlZip -PathType Leaf) -or (Split-Path -Leaf $MysqlZip) -notmatch '^mysql-8\.4\.\d+-winx64\.zip$') {
    throw 'Donner le zip officiel mysql-8.4.x-winx64.zip (MySQL 8.4 LTS, Windows 64 bits).'
}
if (@(Get-Process -Name mysqld -ErrorAction SilentlyContinue).Count -gt 0) { throw 'Un autre MySQL tourne deja sur ce poste : l arreter avant l installation.' }

$wakaTools = Join-Path $wakaPrivate 'tools'
$null = New-Item -ItemType Directory -Path $wakaTools -Force
$wakaServer = Join-Path $wakaTools ([IO.Path]::GetFileNameWithoutExtension($MysqlZip))
if (-not (Test-Path -LiteralPath (Join-Path $wakaServer 'bin\mysqld.exe'))) {
    Write-Host 'Decompression de MySQL (une a deux minutes)...'
    Expand-Archive -LiteralPath $MysqlZip -DestinationPath $wakaTools
}
foreach ($wakaDir in 'logs', 'run') { $null = New-Item -ItemType Directory -Path (Join-Path $wakaInstance $wakaDir) -Force }
$wakaSlash = { param($p) $p -replace '\\', '/' }
$wakaIni = Join-Path $wakaInstance 'my.ini'
@"
[mysqld]
basedir=$(& $wakaSlash $wakaServer)
datadir=$(& $wakaSlash (Join-Path $wakaInstance 'data'))
bind-address=127.0.0.1
port=3306
mysqlx=OFF
require_secure_transport=ON
tls-version=TLSv1.2,TLSv1.3
local-infile=OFF
general-log=OFF
slow-query-log=OFF
skip-log-bin
log-error=$(& $wakaSlash (Join-Path $wakaInstance 'logs\mysql-error.log'))
pid-file=$(& $wakaSlash (Join-Path $wakaInstance 'run\mysqld.pid'))
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb-buffer-pool-size=128M
max-connections=30
"@ | Set-Content -LiteralPath $wakaIni -Encoding ASCII

Write-Host 'Creation de la base (certificats compris)...'
& (Join-Path $wakaServer 'bin\mysqld.exe') "--defaults-file=$wakaIni" --initialize-insecure
if ($LASTEXITCODE -ne 0) { throw ('Initialisation refusee ; voir ' + (Join-Path $wakaInstance 'logs\mysql-error.log') + '. Il manque peut-etre "Microsoft Visual C++ Redistributable".') }

. (Join-Path $PSScriptRoot 'mysql-common.ps1')
$wakaProcess = Start-Process -FilePath (Join-Path $wakaMysqlServer 'bin\mysqld.exe') -ArgumentList ('--defaults-file="' + $wakaIni + '"') -WindowStyle Hidden -PassThru
Wait-WakaMysqlPort $wakaProcess

$wakaPasswords = @{ root = (New-WakaMysqlPassword); waka_dev = (New-WakaMysqlPassword); waka_test_runner = (New-WakaMysqlPassword) }
$wakaGrants = 'SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX, REFERENCES'
$wakaSql = @(
    "CREATE DATABASE waka_local CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
    "CREATE DATABASE waka_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
    "CREATE USER 'waka_dev'@'localhost' IDENTIFIED BY '$($wakaPasswords.waka_dev)' REQUIRE SSL;",
    "GRANT $wakaGrants ON waka_local.* TO 'waka_dev'@'localhost';",
    "CREATE USER 'waka_test_runner'@'localhost' IDENTIFIED BY '$($wakaPasswords.waka_test_runner)' REQUIRE SSL;",
    "GRANT $wakaGrants ON waka_test.* TO 'waka_test_runner'@'localhost';",
    "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($wakaPasswords.root)' REQUIRE SSL;"
) -join ' '
# Saved before use: if anything fails after this point, the passwords are still known to this Windows account.
foreach ($wakaName in $wakaPasswords.Keys) { Save-WakaMysqlSecret $wakaName $wakaPasswords[$wakaName] }
$null = Invoke-WakaMysqlTool 'mysql.exe' 'root' '' @('-e', $wakaSql)
$wakaSql = $null

$wakaCheck = Invoke-WakaMysqlTool 'mysql.exe' 'waka_dev' $wakaPasswords.waka_dev @('-N', '-B', '-e', "SELECT CURRENT_USER(), DATABASE(); SHOW SESSION STATUS LIKE 'Ssl_cipher';", 'waka_local')
$wakaPasswords.Clear()
if (-not (($wakaCheck -join ' ') -match 'waka_dev@localhost') -or -not (($wakaCheck -join ' ') -match 'Ssl_cipher\s+\S+')) { throw 'Le compte waka_dev ne se connecte pas en chiffre comme prevu.' }
Assert-WakaMysqlProcessScope
Assert-WakaMysqlListeners
Write-Host 'MySQL WAKA installe et demarre : 127.0.0.1:3306, connexion chiffree verifiee, bases waka_local et waka_test pretes.'
Write-Host 'Les tables sont creees au premier demarrage du serveur WAKA (tools\start-dev-backend.ps1).'
