$ErrorActionPreference = 'Stop'
# Outils communs de la base MySQL locale de WAKA (installee par tools\mysql\install-mysql.ps1).
# Tout vit dans le dossier prive du poste (tools\private-root.ps1), jamais dans Git :
#   <dossier prive>\tools\mysql-8.4.x-winx64   le serveur MySQL officiel
#   <dossier prive>\mysql-local                my.ini, data (base + certificats), logs, run
#   <dossier prive>\Secrets\mysql-local        mots de passe proteges par Windows (DPAPI, utilisateur courant)
# Aucun mot de passe n'est affiche, passe en argument ni ecrit en clair durablement.
$wakaMysqlRoot = & (Join-Path $PSScriptRoot '..\private-root.ps1')
$wakaMysqlInstance = Join-Path $wakaMysqlRoot 'mysql-local'
$wakaMysqlServer = Get-ChildItem -LiteralPath (Join-Path $wakaMysqlRoot 'tools') -Directory -Filter 'mysql-8.4.*-winx64' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$wakaMysqlSecrets = Join-Path $wakaMysqlRoot 'Secrets\mysql-local'
Add-Type -AssemblyName System.Security

function Assert-WakaMysqlInstalled {
    if (-not $wakaMysqlServer -or -not (Test-Path -LiteralPath (Join-Path $wakaMysqlInstance 'data\ca.pem'))) {
        throw 'MySQL WAKA n est pas installe sur ce poste : lancer tools\mysql\install-mysql.ps1 (voir docs\reprise\README.md).'
    }
}

function Save-WakaMysqlSecret([string]$Name, [string]$Value) {
    $null = New-Item -ItemType Directory -Path $wakaMysqlSecrets -Force
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    try {
        $sealed = [Security.Cryptography.ProtectedData]::Protect($bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes((Join-Path $wakaMysqlSecrets ($Name + '.dpapi')), $sealed)
    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Read-WakaMysqlSecret([string]$Name) {
    $sealed = [IO.File]::ReadAllBytes((Join-Path $wakaMysqlSecrets ($Name + '.dpapi')))
    $bytes = [Security.Cryptography.ProtectedData]::Unprotect($sealed, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try { return [Text.Encoding]::UTF8.GetString($bytes) } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function New-WakaMysqlPassword {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $text = -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
    [Array]::Clear($bytes, 0, $bytes.Length)
    return $text
}

# Runs a MySQL client tool (mysql.exe or mysqladmin.exe) over TLS with the CA checked. The password goes through a
# short-lived option file readable only by this Windows user, deleted right after.
function Invoke-WakaMysqlTool([string]$Tool, [string]$User, [string]$Password, [string[]]$Arguments) {
    $run = Join-Path $wakaMysqlInstance 'run'
    $null = New-Item -ItemType Directory -Path $run -Force
    $options = Join-Path $run ([Guid]::NewGuid().ToString('N') + '.cnf')
    $ca = (Join-Path $wakaMysqlInstance 'data\ca.pem') -replace '\\', '/'
    try {
        $null = New-Item -ItemType File -Path $options
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetAccessRuleProtection($true, $false)
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.WindowsIdentity]::GetCurrent().User, 'FullControl', 'Allow')))
        Set-Acl -LiteralPath $options -AclObject $acl
        [IO.File]::WriteAllText($options, "[client]`nuser=$User`npassword=$Password`nhost=127.0.0.1`nport=3306`nssl-mode=VERIFY_CA`nssl-ca=$ca`n", (New-Object Text.UTF8Encoding $false))
        $output = & (Join-Path $wakaMysqlServer ('bin\' + $Tool)) "--defaults-extra-file=$options" @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { throw ('Commande MySQL refusee (' + $Tool + ', compte ' + $User + ') ; aucun mot de passe n est affiche.') }
        return $output
    } finally {
        if (Test-Path -LiteralPath $options) { Remove-Item -LiteralPath $options -Force }
    }
}

function Assert-WakaMysqlProcessScope {
    $expected = Join-Path $wakaMysqlServer 'bin\mysqld.exe'
    $config = [regex]::Escape((Join-Path $wakaMysqlInstance 'my.ini'))
    $processes = @(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'")
    if ($processes.Count -lt 1) { throw 'MySQL WAKA ne tourne pas : lancer tools\mysql\start-mysql.ps1.' }
    foreach ($process in $processes) {
        if ($process.ExecutablePath -ne $expected -or $process.CommandLine -notmatch ('(?i)(?:^|\s)--defaults-file="?' + $config + '"?(?:\s|$)')) {
            throw 'Une autre instance MySQL tourne sur ce poste ; aucun controle automatique.'
        }
    }
}

# MySQL must only listen on 127.0.0.1:3306 (never the local network, never the X protocol on 33060).
function Assert-WakaMysqlListeners {
    $listeners = @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object { $_.Port -in 3306, 33060 } | ForEach-Object { $_.Address.ToString() + ':' + $_.Port } | Sort-Object -Unique)
    if ($listeners.Count -eq 0) {
        # Some sandboxed shells see no listener at all: check by connecting instead.
        $probe = { param($a, $p) $c = New-Object Net.Sockets.TcpClient; try { $t = $c.ConnectAsync($a, $p); $t.Wait(1500) -and $c.Connected } catch { $false } finally { $c.Dispose() } }
        if (-not (& $probe '127.0.0.1' 3306) -or (& $probe '127.0.0.1' 33060)) { throw 'La portee reseau MySQL doit etre controlee.' }
        foreach ($lan in @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -ne '127.0.0.1' } | ForEach-Object IPAddress)) {
            if (& $probe $lan 3306) { throw 'La portee reseau MySQL doit etre controlee.' }
        }
        return
    }
    if ($listeners.Count -ne 1 -or $listeners[0] -ne '127.0.0.1:3306') { throw 'La portee reseau MySQL doit etre controlee.' }
}

function Wait-WakaMysqlPort([Diagnostics.Process]$Server) {
    for ($i = 0; $i -lt 120; $i++) {
        if ($Server -and $Server.HasExited) { throw ('MySQL n a pas demarre ; voir ' + (Join-Path $wakaMysqlInstance 'logs\mysql-error.log')) }
        $tcp = New-Object Net.Sockets.TcpClient
        try { $tcp.Connect('127.0.0.1', 3306); return } catch { } finally { $tcp.Dispose() }
        Start-Sleep -Milliseconds 500
    }
    throw 'MySQL n a pas repondu dans le delai prevu.'
}
