# Windows PowerShell 5.1. No network, no provider call, no SMS, no secret in arguments.
[CmdletBinding()]
param([switch]$SelfTest)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    # A parent PowerShell 7 process can expose incompatible module search paths to 5.1.
    foreach ($module in @('Microsoft.PowerShell.Security', 'Microsoft.PowerShell.Management', 'Microsoft.PowerShell.Utility')) {
        Import-Module (Join-Path $PSHOME ('Modules\' + $module + '\' + $module + '.psd1'))
    }
    Add-Type -AssemblyName System.Security
} else {
    Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
}
$script:WakaEntropy = [Text.Encoding]::UTF8.GetBytes('WAKA.local.personal-sms-test.v1')
$script:WakaAccessRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'WAKA\Secrets\sms-test'

function Assert-WakaNoReparse([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Un lien de fichiers est interdit pour cet acces local.'
    }
}

function Initialize-WakaPrivateDirectory([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $cursor = [IO.Path]::GetPathRoot($full)
    foreach ($part in $full.Substring($cursor.Length).Split('\')) {
        if (-not $part) { continue }
        $cursor = Join-Path $cursor $part
        if (Test-Path -LiteralPath $cursor) {
            Assert-WakaNoReparse $cursor
        } else {
            $null = [IO.Directory]::CreateDirectory($cursor)
            Assert-WakaNoReparse $cursor
        }
    }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $old = Get-Acl -LiteralPath $full
    if ($old.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value) {
        throw 'Proprietaire du dossier local inattendu.'
    }
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($allowed in @($sid.Value, 'S-1-5-18', 'S-1-5-32-544')) {
        $identity = New-Object Security.Principal.SecurityIdentifier($allowed)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    }
    Set-Acl -LiteralPath $full -AclObject $acl
    $checked = Get-Acl -LiteralPath $full
    $rules = @($checked.Access)
    $expected = @($sid.Value, 'S-1-5-18', 'S-1-5-32-544')
    if (-not $checked.AreAccessRulesProtected -or $rules.Count -ne 3) {
        throw 'Droits locaux non confirmes.'
    }
    foreach ($rule in $rules) {
        if ($rule.IsInherited -or $rule.AccessControlType -ne 'Allow' -or
            $rule.FileSystemRights -ne 'FullControl' -or
            $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $expected) {
            throw 'Droits locaux inattendus.'
        }
    }
}

function Assert-WakaSmsFields([string]$ClientId, [string]$ClientSecret, [string]$Phone) {
    if ($ClientId -cnotmatch '^[\x21-\x39\x3B-\x7E]{1,200}$' -or
        $ClientSecret -cnotmatch '^[\x21-\x7E]{1,500}$' -or
        $Phone -cnotmatch '^\+2376[0-9]{8}$') {
        throw 'Champs incomplets ou format non pris en charge.'
    }
}

function Write-WakaSmsAccess([string]$Directory, [string]$ClientId, [string]$ClientSecret, [string]$Phone) {
    Assert-WakaSmsFields $ClientId $ClientSecret $Phone
    Initialize-WakaPrivateDirectory $Directory
    $target = Join-Path ([IO.Path]::GetFullPath($Directory)) 'access.dpapi'
    if (Test-Path -LiteralPath $target) { throw 'Un acces existe deja : remplacement automatique refuse.' }
    $payload = [ordered]@{
        schemaVersion = 1
        purpose = 'waka-personal-sms-test'
        createdAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        clientId = $ClientId
        clientSecret = $ClientSecret
        phoneNumber = $Phone
    }
    $plain = $null; $cipher = $null; $roundtrip = $null; $stream = $null; $temp = $null
    try {
        $plain = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress))
        $cipher = [Security.Cryptography.ProtectedData]::Protect(
            $plain, $script:WakaEntropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $roundtrip = [Security.Cryptography.ProtectedData]::Unprotect(
            $cipher, $script:WakaEntropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        if ($plain.Length -ne $roundtrip.Length) { throw 'Controle du chiffrement interrompu.' }
        $difference = 0
        for ($index = 0; $index -lt $plain.Length; $index++) {
            $difference = $difference -bor ($plain[$index] -bxor $roundtrip[$index])
        }
        if ($difference -ne 0) { throw 'Controle du chiffrement interrompu.' }
        $temp = Join-Path ([IO.Path]::GetFullPath($Directory)) ([Guid]::NewGuid().ToString('N') + '.pending')
        $stream = [IO.File]::Open($temp, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Write($cipher, 0, $cipher.Length)
        $stream.Flush($true)
        $stream.Dispose(); $stream = $null
        # File.Move refuses an existing destination. Only ciphertext is ever written.
        [IO.File]::Move($temp, $target)
        $temp = $null
        Assert-WakaNoReparse $target
    } finally {
        if ($stream) { $stream.Dispose() }
        foreach ($buffer in @($plain, $cipher, $roundtrip)) {
            if ($buffer) { [Array]::Clear($buffer, 0, $buffer.Length) }
        }
        $payload.clientId = $null; $payload.clientSecret = $null; $payload.phoneNumber = $null
        # No recursive cleanup; this exact, newly generated ciphertext path belongs to this operation.
        if ($temp -and (Test-Path -LiteralPath $temp)) {
            $safeParent = [IO.Path]::GetFullPath($Directory).TrimEnd('\')
            if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($temp)) -cne $safeParent) {
                throw 'Nettoyage local hors perimetre refuse.'
            }
            Remove-Item -LiteralPath $temp -Force
        }
    }
}

function Invoke-WakaSmsAccessSelfTest {
    $testDir = Join-Path ([IO.Path]::GetTempPath()) ('waka-sms-access-check-' + [Guid]::NewGuid().ToString('N'))
    $file = Join-Path $testDir 'access.dpapi'
    $id = 'local-test-id'; $secret = [Guid]::NewGuid().ToString('N'); $phone = '+2376' + ('0' * 8)
    $decoded = $null; $encrypted = $null; $altered = $null
    try {
        Write-WakaSmsAccess $testDir $id $secret $phone
        $encrypted = [IO.File]::ReadAllBytes($file)
        $decoded = [Security.Cryptography.ProtectedData]::Unprotect(
            $encrypted, $script:WakaEntropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $value = [Text.Encoding]::UTF8.GetString($decoded) | ConvertFrom-Json
        if ($value.clientId -cne $id -or $value.clientSecret -cne $secret -or $value.phoneNumber -cne $phone) {
            throw 'SELFTEST_ROUNDTRIP'
        }
        $text = [Text.Encoding]::UTF8.GetString($encrypted)
        if ($text.Contains($secret) -or $text.Contains($phone)) { throw 'SELFTEST_PLAINTEXT' }
        $before = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
        $refused = $false
        try { Write-WakaSmsAccess $testDir $id ($secret + 'new') $phone } catch { $refused = $true }
        if (-not $refused -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -cne $before) {
            throw 'SELFTEST_OVERWRITE'
        }
        $altered = [byte[]]$encrypted.Clone(); $altered[$altered.Length - 1] = $altered[$altered.Length - 1] -bxor 1
        $tamperRefused = $false
        try {
            $unexpected = [Security.Cryptography.ProtectedData]::Unprotect(
                $altered, $script:WakaEntropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Array]::Clear($unexpected, 0, $unexpected.Length)
        } catch { $tamperRefused = $true }
        if (-not $tamperRefused) { throw 'SELFTEST_TAMPER' }
        foreach ($fields in @(@('', $secret, $phone), @($id, "bad`nvalue", $phone), @($id, $secret, '+2375' + ('0' * 8)))) {
            $invalidRefused = $false
            try { Assert-WakaSmsFields $fields[0] $fields[1] $fields[2] } catch { $invalidRefused = $true }
            if (-not $invalidRefused) { throw 'SELFTEST_VALIDATION' }
        }
        [pscustomobject]@{ok=$true;roundtrip=$true;no_plaintext_file=$true;overwrite_refused=$true;
            tamper_refused=$true;invalid_fields_refused=$true;private_acl_checked=$true;network_calls=0;sms_sent=0} |
            ConvertTo-Json -Compress
    } catch {
        $script:WakaTestFailureType = $_.Exception.GetType().FullName
        $script:WakaTestFailureLine = $_.InvocationInfo.ScriptLineNumber
        throw
    } finally {
        foreach ($buffer in @($decoded, $encrypted, $altered)) {
            if ($buffer) { [Array]::Clear($buffer, 0, $buffer.Length) }
        }
        $value = $null; $secret = $null
        # Verify absolute locations before removing only the two isolated self-test objects.
        $safeTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
        $fullDir = [IO.Path]::GetFullPath($testDir)
        if ([IO.Path]::GetDirectoryName($fullDir) -cne $safeTemp -or
            [IO.Path]::GetFileName($fullDir) -notmatch '^waka-sms-access-check-[a-f0-9]{32}$') {
            throw 'SELFTEST_CLEANUP_SCOPE'
        }
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
        if (Test-Path -LiteralPath $fullDir) { Remove-Item -LiteralPath $fullDir -Force }
    }
}

function Show-WakaSmsAccessForm {
    $target = Join-Path $script:WakaAccessRoot 'access.dpapi'
    if (Test-Path -LiteralPath $target) {
        Write-Output 'WAKA_SMS_ACCESS_ALREADY_PRESENT_NO_CHANGE'
        return
    }
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $form = New-Object Windows.Forms.Form
    $form.Text = 'WAKA - acces prive pour le test SMS'
    $form.ClientSize = New-Object Drawing.Size(680, 480)
    $form.StartPosition = 'CenterScreen'; $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false; $form.MinimizeBox = $true
    $form.Font = New-Object Drawing.Font('Segoe UI', 10)
    $intro = New-Object Windows.Forms.Label
    $intro.SetBounds(20, 15, 640, 85)
    $intro.Text = "Saisie sur ce PC uniquement : jamais dans le chat.`r`nCopie Client ID et Client Secret depuis ton application Orange WAKA.`r`nLes trois champs seront chiffres avec ton profil Windows, hors Git.`r`nCet outil n'envoie aucun SMS et ne verifie pas encore les acces Orange."
    $form.Controls.Add($intro)
    $inputs = @()
    $names = @('Client ID Orange', 'Client Secret Orange', 'Numero de test : +237 suivi des 9 chiffres, sans espace')
    for ($i = 0; $i -lt 3; $i++) {
        $label = New-Object Windows.Forms.Label
        $label.SetBounds(20, 108 + 70 * $i, 640, 25); $label.Text = $names[$i]
        $box = New-Object Windows.Forms.TextBox
        $box.SetBounds(20, 136 + 70 * $i, 640, 28)
        $box.UseSystemPasswordChar = $true
        $box.MaxLength = @(200, 500, 13)[$i]
        $box.ShortcutsEnabled = $true
        $inputs += $box; $form.Controls.Add($label); $form.Controls.Add($box)
    }
    $consent = New-Object Windows.Forms.CheckBox
    $consent.SetBounds(20, 327, 640, 45)
    $consent.Text = "Je dispose de l'accord du titulaire pour utiliser ce numero lors du futur essai.`r`nEnregistrer ces acces ne declenche aucun envoi."
    $form.Controls.Add($consent)
    $feedback = New-Object Windows.Forms.Label
    $feedback.SetBounds(20, 376, 640, 42); $form.Controls.Add($feedback)
    $save = New-Object Windows.Forms.Button
    $save.SetBounds(355, 430, 305, 34); $save.Text = 'Enregistrer les acces sur ce PC'
    $cancel = New-Object Windows.Forms.Button
    $cancel.SetBounds(20, 430, 140, 34); $cancel.Text = 'Annuler'
    $cancel.DialogResult = [Windows.Forms.DialogResult]::Cancel
    $form.Controls.Add($save); $form.Controls.Add($cancel); $form.CancelButton = $cancel
    $script:WakaAccessSaved = $false
    $save.Add_Click({
        if (-not $consent.Checked) { $feedback.Text = 'Confirme l''accord du titulaire du numero.'; return }
        try {
            Assert-WakaSmsFields $inputs[0].Text $inputs[1].Text $inputs[2].Text
            $save.Enabled = $false
            Write-WakaSmsAccess $script:WakaAccessRoot $inputs[0].Text $inputs[1].Text $inputs[2].Text
            foreach ($box in $inputs) { $box.Clear() }
            $script:WakaAccessSaved = $true
            $form.Close()
        } catch {
            $feedback.Text = 'Enregistrement refuse. Verifie les champs ; aucun detail prive affiche.'
            $save.Enabled = $true
        }
    })
    $form.Add_Shown({ $form.Activate(); $form.BringToFront(); $inputs[0].Focus() })
    try { $null = $form.ShowDialog() } finally {
        foreach ($box in $inputs) { $box.Clear() }
        $form.Dispose()
    }
    if ($script:WakaAccessSaved) { Write-Output 'WAKA_SMS_ACCESS_SAVED_NO_NETWORK' }
    else { Write-Output 'WAKA_SMS_ACCESS_CANCELLED_NO_CHANGE' }
}

try {
    if ($SelfTest) { Invoke-WakaSmsAccessSelfTest } else { Show-WakaSmsAccessForm }
} catch {
    # Never print the underlying error, user fields, credential, or phone.
    Write-Output 'WAKA_SMS_ACCESS_FAILED_NO_PRIVATE_OUTPUT'
    if ($SelfTest) {
        [pscustomobject]@{exception_type=$_.Exception.GetType().FullName;line=$_.InvocationInfo.ScriptLineNumber;
            original_type=$script:WakaTestFailureType;original_line=$script:WakaTestFailureLine} |
            ConvertTo-Json -Compress
    }
    exit 1
}
