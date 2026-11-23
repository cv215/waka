# Arrete proprement la base MySQL locale de WAKA.
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
Assert-WakaMysqlInstalled
$wakaOwn = @(Get-Process -Name mysqld -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq (Join-Path $wakaMysqlServer 'bin\mysqld.exe') })
if ($wakaOwn.Count -eq 0) { Write-Host 'MySQL WAKA est deja arrete.'; exit 0 }
Assert-WakaMysqlProcessScope
$null = Invoke-WakaMysqlTool 'mysqladmin.exe' 'root' (Read-WakaMysqlSecret 'root') @('shutdown')
foreach ($wakaProcess in $wakaOwn) { if (-not $wakaProcess.WaitForExit(30000)) { throw 'MySQL termine encore son arret ; aucun arret force.' } }
Write-Host 'MySQL WAKA arrete proprement.'
