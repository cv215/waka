# Demarre la base MySQL locale de WAKA (apres tools\mysql\install-mysql.ps1) et verifie la connexion chiffree.
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
Assert-WakaMysqlInstalled
$wakaIni = Join-Path $wakaMysqlInstance 'my.ini'
$wakaExisting = @(Get-Process -Name mysqld -ErrorAction SilentlyContinue)
if ($wakaExisting.Count -gt 0) {
    Assert-WakaMysqlProcessScope
    Write-Host 'MySQL WAKA tourne deja.'
} else {
    $wakaProcess = Start-Process -FilePath (Join-Path $wakaMysqlServer 'bin\mysqld.exe') -ArgumentList ('--defaults-file="' + $wakaIni + '"') -WindowStyle Hidden -PassThru
    Wait-WakaMysqlPort $wakaProcess
}
$wakaCheck = Invoke-WakaMysqlTool 'mysql.exe' 'waka_dev' (Read-WakaMysqlSecret 'waka_dev') @('-N', '-B', '-e', "SHOW SESSION STATUS LIKE 'Ssl_cipher';", 'waka_local')
if (-not (($wakaCheck -join ' ') -match 'Ssl_cipher\s+\S+')) { throw 'La connexion a MySQL n est pas chiffree comme prevu.' }
Assert-WakaMysqlProcessScope
Assert-WakaMysqlListeners
Write-Host 'MySQL WAKA pret : 127.0.0.1:3306, connexion chiffree verifiee.'
