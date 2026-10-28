# Personal receipt check, never an account/session or public SMS endpoint.
[CmdletBinding()]
param([switch]$SelfTest,[switch]$DiagnoseProvider,[switch]$AuthorizedRetry,[switch]$NewRecipientTest,
    [switch]$RecipientFromPrivateStdin,[switch]$PrivateInputProbe)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    foreach ($module in @('Microsoft.PowerShell.Security','Microsoft.PowerShell.Management','Microsoft.PowerShell.Utility')) {
        Import-Module (Join-Path $PSHOME ('Modules\' + $module + '\' + $module + '.psd1'))
    }
    Add-Type -AssemblyName System.Security
} else { Add-Type -AssemblyName System.Security.Cryptography.ProtectedData }
$script:WakaTestEntropy = [Text.Encoding]::UTF8.GetBytes('WAKA.local.personal-sms-test.v1')
$script:WakaTestRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'WAKA\Secrets\sms-test'

function Assert-WakaPersonalPrivatePath([string]$Directory, [string]$File) {
    $full = [IO.Path]::GetFullPath($Directory)
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($File)) -cne $full.TrimEnd('\')) {
        throw 'Emplacement prive inattendu.'
    }
    $cursor = [IO.Path]::GetPathRoot($full)
    foreach ($part in $full.Substring($cursor.Length).Split('\')) {
        if (-not $part) { continue }
        $cursor = Join-Path $cursor $part
        $item = Get-Item -LiteralPath $cursor -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Lien local interdit.' }
    }
    $leaf = Get-Item -LiteralPath $File -Force
    if ($leaf.PSIsContainer -or ($leaf.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $leaf.Length -lt 32 -or $leaf.Length -gt 8192) { throw 'Fichier prive non conforme.' }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $expected = @($sid,'S-1-5-18','S-1-5-32-544')
    foreach ($path in @($full,$File)) {
        $acl = Get-Acl -LiteralPath $path
        if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid -or
            @($acl.Access).Count -ne 3) { throw 'Droits prives inattendus.' }
        foreach ($rule in $acl.Access) {
            if ($rule.AccessControlType -ne 'Allow' -or $rule.FileSystemRights -ne 'FullControl' -or
                $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $expected) {
                throw 'Droits prives inattendus.'
            }
        }
        if ($path -ceq $full -and -not $acl.AreAccessRulesProtected) { throw 'Dossier prive non protege.' }
    }
}

function ConvertFrom-WakaPersonalAccess([byte[]]$Plain, [DateTimeOffset]$Now) {
    $utf8 = [Text.UTF8Encoding]::new($false,$true)
    $json = $utf8.GetString($Plain)
    $value = $json | ConvertFrom-Json
    $names = @($value.PSObject.Properties.Name | Sort-Object)
    if (($names -join ',') -cne 'clientId,clientSecret,createdAtUtc,phoneNumber,purpose,schemaVersion' -or
        $value.schemaVersion -ne 1 -or $value.purpose -cne 'waka-personal-sms-test' -or
        $value.clientId -isnot [string] -or $value.clientSecret -isnot [string] -or
        $value.phoneNumber -isnot [string] -or $value.createdAtUtc -isnot [string] -or
        $value.clientId -cnotmatch '^[\x21-\x39\x3B-\x7E]{1,200}$' -or
        $value.clientSecret -cnotmatch '^[\x21-\x7E]{1,500}$' -or
        $value.phoneNumber -cnotmatch '^\+2376[0-9]{8}$') { throw 'Contenu prive non conforme.' }
    $stamp = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($value.createdAtUtc,[Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::None,[ref]$stamp) -or
        $stamp.Offset -ne [TimeSpan]::Zero -or $stamp -gt $Now.AddMinutes(1) -or
        $stamp -lt $Now.AddDays(-7)) { throw 'Preparation privee expiree ou date non conforme.' }
    $json = $null
    return $value
}

function Read-WakaPersonalAccess {
    $file = Join-Path $script:WakaTestRoot 'access.dpapi'
    Assert-WakaPersonalPrivatePath $script:WakaTestRoot $file
    $cipher = $null; $plain = $null
    try {
        $cipher = [IO.File]::ReadAllBytes($file)
        $plain = [Security.Cryptography.ProtectedData]::Unprotect(
            $cipher,$script:WakaTestEntropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)
        return ConvertFrom-WakaPersonalAccess $plain ([DateTimeOffset]::UtcNow)
    } finally {
        foreach ($buffer in @($cipher,$plain)) { if ($buffer) { [Array]::Clear($buffer,0,$buffer.Length) } }
    }
}

function Save-WakaPersonalResult([string]$State) {
    if ($State -notin @('READY','ACCEPTED','UNKNOWN','REJECTED','ALREADY_RESERVED','VERIFIED','WRONG_CODE',
            'LOCKED','EXPIRED','CONSUMED','INVALID_INPUT','ERROR','CLOSED','CANCELLED')) {
        throw 'Etat local inattendu.'
    }
    $resultName=if($NewRecipientTest){'personal-check-recipient-03-result.json'}
        elseif($AuthorizedRetry){'personal-check-retry-02-result.json'}else{'personal-check-result.json'}
    $target = Join-Path $script:WakaTestRoot $resultName
    if (Test-Path -LiteralPath $target) {
        $item = Get-Item -LiteralPath $target -Force
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Resultat local non conforme.'
        }
    }
    # Generic outcome only; no phone, code, provider payload, credential or transaction reference.
    $result = [ordered]@{schemaVersion=1;state=$State;recordedAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
    $temp = Join-Path $script:WakaTestRoot ([Guid]::NewGuid().ToString('N') + '.result-pending')
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($result | ConvertTo-Json -Compress))
        $stream = [IO.File]::Open($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        if ([IO.File]::Exists($target)) { [IO.File]::Replace($temp,$target,[System.Management.Automation.Language.NullString]::Value) }
        else { [IO.File]::Move($temp,$target) }
        $temp = $null
    } finally {
        if ($temp -and [IO.File]::Exists($temp)) {
            if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($temp)) -cne
                [IO.Path]::GetFullPath($script:WakaTestRoot).TrimEnd('\')) { throw 'Nettoyage hors perimetre.' }
            Remove-Item -LiteralPath $temp -Force
        }
    }
}

function Start-WakaPersonalWorker($Access,[bool]$Retry=$false,[bool]$Recipient03=$false) {
    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    $jar = Join-Path $repo 'backend\target\waka-backend-0.0.1-SNAPSHOT.jar'
    if (-not [IO.File]::Exists($jar)) { throw 'Le serveur compile est absent.' }
    $java = Join-Path $env:ProgramFiles 'Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
    if (-not [IO.File]::Exists($java)) { throw 'Le Java 21 retenu est absent.' }
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.WorkingDirectory = Join-Path $repo 'backend'
    $psi.Arguments = '-Dloader.main=com.waka.identity.sms.orange.PersonalSmsCheckMain ' +
        '-Dloader.config.location=classpath:/waka-personal-check-loader.properties ' +
        '-Dloader.path=BOOT-INF/lib -cp "' + $jar + '" org.springframework.boot.loader.launch.PropertiesLauncher'
    if($Recipient03){$psi.Arguments+=' --authorized-recipient-03'}
    elseif($Retry){$psi.Arguments+=' --authorized-retry-02'}
    $psi.UseShellExecute=$false; $psi.CreateNoWindow=$true
    $psi.RedirectStandardInput=$true; $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true
    $psi.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $psi.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($key in @($psi.EnvironmentVariables.Keys)) {
        if ($key -match '^(LOADER_|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|JDK_JAVA_OPTIONS$|CLASSPATH$)') {
            $psi.EnvironmentVariables.Remove($key)
        }
    }
    # Match the private directory whose path and ACL were verified above.
    $psi.EnvironmentVariables['LOCALAPPDATA']=[Environment]::GetFolderPath('LocalApplicationData')
    $process = [Diagnostics.Process]::Start($psi)
    $script:WakaWorkerErrorDrain = $process.StandardError.ReadToEndAsync()
    try {
        $initial = @{clientId=$Access.clientId;clientSecret=$Access.clientSecret;phoneNumber=$Access.phoneNumber}
        $process.StandardInput.WriteLine(($initial | ConvertTo-Json -Compress))
        $process.StandardInput.Flush()
        $initial.clientId=$null; $initial.clientSecret=$null; $initial.phoneNumber=$null
        return $process
    } catch { if (-not $process.HasExited) { $process.Kill() }; $process.Dispose(); throw }
}

function Read-WakaPersonalState($Process, [int]$TimeoutMs) {
    $task = $Process.StandardOutput.ReadLineAsync()
    if (-not $task.Wait($TimeoutMs)) { throw 'Delai local depasse.' }
    $line = $task.GetAwaiter().GetResult()
    if (-not $line -or $line.Length -gt 256) { throw 'Reponse locale non conforme.' }
    $reply = $line | ConvertFrom-Json
    if (@($reply.PSObject.Properties).Count -ne 1 -or $reply.state -notin @('READY','ACCEPTED','UNKNOWN',
            'REJECTED','ALREADY_RESERVED','VERIFIED','WRONG_CODE','LOCKED','EXPIRED','CONSUMED',
            'INVALID_INPUT','ERROR','CLOSED')) { throw 'Reponse locale non conforme.' }
    return [string]$reply.state
}

function Close-WakaPersonalWorker($Process) {
    if (-not $Process) { return }
    try {
        if (-not $Process.HasExited) {
            $Process.StandardInput.WriteLine('{"action":"exit"}')
            $Process.StandardInput.Close()
            if (-not $Process.WaitForExit(2500)) { $Process.Kill(); $null=$Process.WaitForExit(2500) }
        }
    } catch { try { if (-not $Process.HasExited) { $Process.Kill() } } catch {} }
    finally { $Process.Dispose(); $script:WakaWorkerErrorDrain=$null }
}

function Invoke-WakaPersonalReaderSelfTest {
    $now=[DateTimeOffset]::UtcNow
    $data=[ordered]@{schemaVersion=1;purpose='waka-personal-sms-test';createdAtUtc=$now.ToString('o');
        clientId='local-test-id';clientSecret='local-test-secret';phoneNumber=('+2376'+('0'*8))}
    $bytes=$null
    try {
        $bytes=[Text.Encoding]::UTF8.GetBytes(($data|ConvertTo-Json -Compress))
        $checked=ConvertFrom-WakaPersonalAccess $bytes $now
        if ($checked.phoneNumber -cne $data.phoneNumber) { throw 'SELFTEST_READER' }
        foreach ($field in @('schemaVersion','purpose','phoneNumber','createdAtUtc')) {
            $copy=@{}; foreach($key in $data.Keys){$copy[$key]=$data[$key]}
            $copy[$field]=switch($field){ 'schemaVersion' {2} 'purpose' {'wrong'}
                'phoneNumber' {'not-a-number'} 'createdAtUtc' {$now.AddDays(-8).ToString('o')} }
            $bad=[Text.Encoding]::UTF8.GetBytes(($copy|ConvertTo-Json -Compress))
            $refused=$false
            try { $unexpected=ConvertFrom-WakaPersonalAccess $bad $now } catch { $refused=$true }
            finally { [Array]::Clear($bad,0,$bad.Length);$unexpected=$null }
            if (-not $refused) { throw 'SELFTEST_READER_INVALID' }
        }
        # Exercise both creation and replacement: a null String must not become an empty backup path.
        $originalRoot=$script:WakaTestRoot
        $testRoot=Join-Path ([IO.Path]::GetTempPath()) ('waka-personal-result-'+[Guid]::NewGuid().ToString('N'))
        $null=[IO.Directory]::CreateDirectory($testRoot)
        try {
            $script:WakaTestRoot=$testRoot
            Save-WakaPersonalResult 'READY'
            Save-WakaPersonalResult 'REJECTED'
            $resultFile=Join-Path $testRoot 'personal-check-result.json'
            $result=[IO.File]::ReadAllText($resultFile)|ConvertFrom-Json
            if($result.state -cne 'REJECTED' -or @($result.PSObject.Properties).Count -ne 3){throw 'SELFTEST_RESULT_UPDATE'}
        } finally {
            $script:WakaTestRoot=$originalRoot
            $resultFile=Join-Path $testRoot 'personal-check-result.json'
            if([IO.File]::Exists($resultFile)){[IO.File]::Delete($resultFile)}
            # Non-recursive deletion refuses unexpected leftovers.
            [IO.Directory]::Delete($testRoot)
        }
        # Only initialization and exit with technical fields; no SEND command in this self-test.
        $process=$null
        try {
            $process=Start-WakaPersonalWorker ([pscustomobject]$data)
            $state=Read-WakaPersonalState $process 15000
            if ($state -notin @('READY','ALREADY_RESERVED')) { throw 'SELFTEST_WORKER_PROTOCOL' }
        } finally { Close-WakaPersonalWorker $process }
        try {
            $process=Start-WakaPersonalWorker ([pscustomobject]$data) $true
            $state=Read-WakaPersonalState $process 15000
            if($state -ne 'READY'){throw 'SELFTEST_RETRY_PROTOCOL'}
        } finally { Close-WakaPersonalWorker $process }
        try {
            $process=Start-WakaPersonalWorker ([pscustomobject]$data) $false $true
            $state=Read-WakaPersonalState $process 15000
            if($state -ne 'READY'){throw 'SELFTEST_RECIPIENT03_PROTOCOL'}
        } finally { Close-WakaPersonalWorker $process }
        [pscustomobject]@{ok=$true;reader_roundtrip=$true;invalid_schema_purpose_contact_date_refused=$true;
            generic_result_creation_and_replacement=$true;
            retry_worker_initialized_without_send=$true;
            recipient03_worker_initialized_without_send=$true;
            worker_protocol_started_without_send=$true;real_access_file_read=$false;network_calls=0;sms_sent=0}|
            ConvertTo-Json -Compress
    } finally { if($bytes){[Array]::Clear($bytes,0,$bytes.Length)};$data=$null;$checked=$null }
}

function Invoke-WakaPersonalProviderDiagnostic {
    # Only OAuth and two read-only administration endpoints. No messaging URL or send command.
    Add-Type -AssemblyName System.Net.Http
    $access=$null;$client=$null;$handler=$null;$basicBytes=$null;$token=$null
    $report=[ordered]@{mode='READ_ONLY_PROVIDER_DIAGNOSTIC';oauth_http=$null;
        contracts_http=$null;statistics_http=$null;cameroon_active_contracts=0;
        cameroon_available_units=$null;cameroon_usage=$null;outcome='NOT_STARTED';
        sms_requests=0;new_purchase=$false;private_values_output=$false}
    try {
        $access=Read-WakaPersonalAccess
        $handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false;$handler.UseProxy=$false
        $client=[Net.Http.HttpClient]::new($handler)
        $client.Timeout=[TimeSpan]::FromSeconds(8);$client.MaxResponseContentBufferSize=8192
        $client.DefaultRequestHeaders.Accept.ParseAdd('application/json')
        $basicBytes=[Text.Encoding]::ASCII.GetBytes($access.clientId+':'+$access.clientSecret)
        $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',[Convert]::ToBase64String($basicBytes))
        $content=[Net.Http.StringContent]::new('grant_type=client_credentials',[Text.Encoding]::ASCII,'application/x-www-form-urlencoded')
        $content.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('application/x-www-form-urlencoded')
        try{$response=$client.PostAsync('https://api.orange.com/oauth/v3/token',$content).GetAwaiter().GetResult()}
        finally{$content.Dispose()}
        try{
            $report.oauth_http=[int]$response.StatusCode
            if($report.oauth_http -ne 200){$report.outcome='OAUTH_REFUSED';return [pscustomobject]$report}
            $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
            if($body.token_type -cne 'Bearer' -or $body.access_token -isnot [string] -or
                $body.access_token -cnotmatch '^[A-Za-z0-9._~+/-]{1,4096}=*$'){throw 'INVALID_TOKEN_REPLY'}
            $token=$body.access_token;$body=$null
        }finally{$response.Dispose()}
        $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
        $response=$client.GetAsync('https://api.orange.com/sms/admin/v1/contracts?country=CMR').GetAwaiter().GetResult()
        try{
            $report.contracts_http=[int]$response.StatusCode
            if($report.contracts_http -eq 200){
                $contracts=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
                $active=@($contracts|Where-Object {$_.country -ceq 'CMR' -and $_.status -ceq 'ACTIVE'})
                $units=0L
                foreach($contract in $active){
                    if([string]$contract.availableUnits -notmatch '^[0-9]{1,9}$'){throw 'INVALID_CONTRACT_REPLY'}
                    $units+=[long]$contract.availableUnits
                }
                $report.cameroon_active_contracts=$active.Count;$report.cameroon_available_units=$units
                $contracts=$null;$active=$null
            }
        }finally{$response.Dispose()}
        $response=$client.GetAsync('https://api.orange.com/sms/admin/v1/statistics?country=CMR').GetAwaiter().GetResult()
        try{
            $report.statistics_http=[int]$response.StatusCode
            if($report.statistics_http -eq 200){
                $stats=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
                $usage=0L
                foreach($service in @($stats.partnerStatistics.statistics)){
                    foreach($country in @($service.serviceStatistics)){
                        if($country.country -cne 'CMR'){continue}
                        foreach($entry in @($country.countryStatistics)){
                            if([string]$entry.usage -notmatch '^[0-9]{1,9}$'){throw 'INVALID_STATISTICS_REPLY'}
                            $usage+=[long]$entry.usage
                        }
                    }
                }
                $report.cameroon_usage=$usage;$stats=$null
            }
        }finally{$response.Dispose()}
        $report.outcome='READ_ONLY_COMPLETED'
    }catch{$report.outcome='LOCAL_OR_NETWORK_ERROR'}
    finally{
        if($client){$client.Dispose()}elseif($handler){$handler.Dispose()}
        if($basicBytes){[Array]::Clear($basicBytes,0,$basicBytes.Length)}
        if($access){$access.clientId=$null;$access.clientSecret=$null;$access.phoneNumber=$null}
        $token=$null;$body=$null;$contracts=$null;$stats=$null
    }
    return [pscustomobject]$report
}

function Read-WakaOtherRecipientForm {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $dialog=[Windows.Forms.Form]::new()
    $dialog.Text='WAKA - autre destinataire pour 1 SMS'
    $dialog.ClientSize=[Drawing.Size]::new(700,370)
    $dialog.StartPosition='CenterScreen';$dialog.FormBorderStyle='FixedDialog';$dialog.MaximizeBox=$false
    $dialog.Font=[Drawing.Font]::new('Segoe UI',10)
    $label=[Windows.Forms.Label]::new();$label.SetBounds(20,16,660,100)
    $label.Text="Un seul SMS vers une autre personne qui accepte cet essai.`r`nUtilise les credits deja payes, sans achat ni renvoi automatique.`r`nLe numero reste en memoire pour cet essai ; le numero enregistre est conserve.`r`nFormat Cameroun : +237 puis 9 chiffres commencant par 6."
    $dialog.Controls.Add($label)
    $phone=[Windows.Forms.TextBox]::new();$phone.SetBounds(20,125,300,32)
    $phone.MaxLength=13;$phone.UseSystemPasswordChar=$true;$dialog.Controls.Add($phone)
    $reveal=[Windows.Forms.CheckBox]::new();$reveal.SetBounds(345,125,330,32)
    $reveal.Text='Afficher pour verifier le numero';$dialog.Controls.Add($reveal)
    $reveal.Add_CheckedChanged({$phone.UseSystemPasswordChar=-not $reveal.Checked})
    $consent=[Windows.Forms.CheckBox]::new();$consent.SetBounds(20,175,660,50)
    $consent.Text="Le titulaire de ce numero accepte ce SMS de test.`r`nJe demande cet unique envoi et sa consommation sur le lot paye."
    $dialog.Controls.Add($consent)
    $feedback=[Windows.Forms.Label]::new();$feedback.SetBounds(20,240,660,35)
    $feedback.Text='Ne transmets ni ce numero ni le code dans le chat.';$dialog.Controls.Add($feedback)
    $confirm=[Windows.Forms.Button]::new();$confirm.SetBounds(20,295,430,42)
    $confirm.Text='Envoyer 1 SMS a ce nouveau numero';$dialog.Controls.Add($confirm)
    $cancel=[Windows.Forms.Button]::new();$cancel.SetBounds(495,295,180,42)
    $cancel.Text='Annuler';$dialog.Controls.Add($cancel)
    $selection=@{Phone=$null}
    $cancel.Add_Click({$dialog.Close()})
    $confirm.Add_Click({
        if(-not $consent.Checked){$feedback.Text='Confirme que le titulaire accepte le SMS.';return}
        if($phone.Text -cnotmatch '^\+2376[0-9]{8}$'){$feedback.Text='Format attendu : +237 puis 9 chiffres commencant par 6.';return}
        $selection.Phone=$phone.Text;$dialog.Close()
    })
    $dialog.Add_Shown({$dialog.Activate();$dialog.BringToFront();[void]$phone.Focus()})
    try{$null=$dialog.ShowDialog();return $selection.Phone}
    finally{$phone.Clear();$selection.Phone=$null;$dialog.Dispose()}
}

function Read-WakaPrivateRecipient {
    if([Console]::IsInputRedirected){return [Console]::In.ReadLine()}
    $characters=[Collections.Generic.List[char]]::new()
    try{
        while($true){
            $key=[Console]::ReadKey($true)
            if($key.Key -eq [ConsoleKey]::Enter){break}
            if($key.Key -eq [ConsoleKey]::Backspace){
                if($characters.Count -gt 0){$characters.RemoveAt($characters.Count-1)}
            }elseif($characters.Count -lt 14){$characters.Add($key.KeyChar)}
        }
        return -join $characters
    }finally{$characters.Clear()}
}

function Show-WakaPersonalSmsForm([string]$RecipientOverride) {
    $access=$null; $process=$null; $form=$null; $timer=$null
    # One mutable holder shared by every event handler, including failure/expiry cleanup.
    $worker=@{Process=$null}
    try {
        $access=Read-WakaPersonalAccess
        if($NewRecipientTest){
            if($RecipientOverride -cnotmatch '^\+2376[0-9]{8}$'){throw 'INVALID_NEW_RECIPIENT'}
            $access.phoneNumber=$RecipientOverride
            $RecipientOverride=$null
        }elseif($RecipientOverride){throw 'UNEXPECTED_RECIPIENT_OVERRIDE'}
        $automaticRequest=[bool]($AuthorizedRetry -or $NewRecipientTest)
        $process=Start-WakaPersonalWorker $access ([bool]$AuthorizedRetry) ([bool]$NewRecipientTest)
        $worker.Process=$process
        $initial=Read-WakaPersonalState $process 15000
        if ($initial -ne 'READY') { Save-WakaPersonalResult $initial; Write-Output ('WAKA_PERSONAL_SMS_'+$initial); return }
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        [Windows.Forms.Application]::EnableVisualStyles()
        $form=New-Object Windows.Forms.Form
        $form.Text='WAKA - recevoir et verifier mon SMS de test'
        $form.ClientSize=New-Object Drawing.Size(710,500)
        $form.StartPosition='CenterScreen';$form.FormBorderStyle='FixedDialog';$form.MaximizeBox=$false
        $form.Font=New-Object Drawing.Font('Segoe UI',10)
        $intro=New-Object Windows.Forms.Label;$intro.SetBounds(20,16,670,120)
        $intro.Text="Test personnel vers : "+$access.phoneNumber+"`r`nVerifie que ce numero est le tien et que le telephone recoit les SMS.`r`nUne seule demande SMS au maximum, sur les credits deja achetes.`r`nPas de nouvel achat ni renvoi automatique. Code valable 5 minutes.`r`nCe test n'ouvre aucun compte WAKA et ne valide pas le lancement public."
        if($AuthorizedRetry){
            $intro.Text="Nouvel essai demande vers : "+$access.phoneNumber+"`r`nUn seul second SMS, autorise dans le chat, sur le lot deja paye.`r`nLe premier essai et son verrou sont conserves. Aucun achat.`r`nContenu : Code WAKA : [6 chiffres]. Valable 5 minutes. Ne le partagez pas.`r`nLe code recu est a saisir uniquement ici ; aucun compte WAKA cree."
        }
        if($NewRecipientTest){
            $intro.Text="Essai vers le nouveau numero confirme : "+$access.phoneNumber+"`r`nUn seul SMS a cette autre personne, sur le lot deja paye.`r`nLes deux premiers essais restent conserves. Aucun achat.`r`nContenu : Code WAKA : [6 chiffres]. Valable 5 minutes. Ne le partagez pas.`r`nLe code recu est a saisir uniquement ici ; aucun compte WAKA cree."
        }
        $form.Controls.Add($intro)
        $consent=New-Object Windows.Forms.CheckBox;$consent.SetBounds(20,146,670,48)
        $consent.Text="Je demande ce SMS sur mon propre numero affiche ci-dessus.`r`nJ'autorise la consommation correspondante dans mon lot de test."
        if($AuthorizedRetry){$consent.Text='Second essai unique explicitement demande par le fondateur dans le chat.';$consent.Enabled=$false}
        if($NewRecipientTest){$consent.Text='Nouveau numero et accord du titulaire confirmes dans la fenetre precedente.';$consent.Enabled=$false}
        $form.Controls.Add($consent)
        $send=New-Object Windows.Forms.Button;$send.SetBounds(20,205,300,38)
        $send.Text='Envoyer 1 SMS de test';$send.Enabled=$false;$form.Controls.Add($send)
        $status=New-Object Windows.Forms.Label;$status.SetBounds(20,255,670,70)
        $status.Text='Pret. Aucun SMS envoye. Verifie le numero avant de confirmer.';$form.Controls.Add($status)
        $codeLabel=New-Object Windows.Forms.Label;$codeLabel.SetBounds(20,335,670,24)
        $codeLabel.Text='Code recu (6 chiffres), a saisir ici uniquement :';$form.Controls.Add($codeLabel)
        $codeBox=New-Object Windows.Forms.TextBox;$codeBox.SetBounds(20,367,230,30)
        $codeBox.MaxLength=6;$codeBox.UseSystemPasswordChar=$true;$codeBox.Enabled=$false;$form.Controls.Add($codeBox)
        $verify=New-Object Windows.Forms.Button;$verify.SetBounds(270,363,260,38)
        $verify.Text='Verifier le code recu';$verify.Enabled=$false;$form.Controls.Add($verify)
        $close=New-Object Windows.Forms.Button;$close.SetBounds(540,444,150,34);$close.Text='Fermer'
        $close.Add_Click({$form.Close()});$form.Controls.Add($close)
        $script:WakaPersonalRequested=$false;$script:WakaPersonalState='READY'
        $script:WakaPersonalWatch=[Diagnostics.Stopwatch]::new()
        $consent.Add_CheckedChanged({if(-not $script:WakaPersonalRequested){$send.Enabled=$consent.Checked}})
        $sendAction={
            if((-not $automaticRequest -and -not $consent.Checked) -or $script:WakaPersonalRequested){return}
            $script:WakaPersonalRequested=$true;$send.Enabled=$false;$consent.Enabled=$false
            $status.Text='Demande en cours. Aucun nouvel essai automatique.';$form.Refresh()
            $script:WakaPersonalWatch.Start()
            # Persist an indeterminate state BEFORE submitting, so a crash cannot leave READY.
            try{Save-WakaPersonalResult 'UNKNOWN'}catch{
                $script:WakaPersonalState='ERROR';$status.Text='Resultat local impossible a enregistrer. Aucun SMS demande.';return
            }
            try {
                $worker.Process.StandardInput.WriteLine('{"action":"send","consent":true}');$worker.Process.StandardInput.Flush()
                $script:WakaPersonalState=Read-WakaPersonalState $worker.Process 30000
            } catch { $script:WakaPersonalState='UNKNOWN';Close-WakaPersonalWorker $worker.Process;$worker.Process=$null }
            $recorded=$true
            try{Save-WakaPersonalResult $script:WakaPersonalState}catch{$recorded=$false}
            switch($script:WakaPersonalState){
                'ACCEPTED' {$status.Text='Demande acceptee par Orange. Saisis le code si tu le recois.'}
                'UNKNOWN' {
                    if($worker.Process){$status.Text='Retour incertain. Aucun renvoi. Si un code arrive, tu peux encore le verifier.'}
                    else{$status.Text='Retour incertain et processus ferme. Aucun renvoi ; verification locale indisponible.'}
                }
                'ALREADY_RESERVED' {$status.Text='Un essai est deja reserve. Aucun nouvel envoi autorise.'}
                default {$status.Text='Essai refuse ou interrompu. Aucun renvoi. Les acces peuvent necessiter une correction.'}
            }
            if(-not $recorded){$status.Text+="`r`nResultat local non enregistre. Ne relance pas l'envoi."}
            $canVerify=($worker.Process -and $script:WakaPersonalState -in @('ACCEPTED','UNKNOWN'))
            $codeBox.Enabled=[bool]$canVerify;$verify.Enabled=[bool]$canVerify
            if($canVerify){$codeBox.Focus()}
        }
        $send.Add_Click($sendAction)
        $verify.Add_Click({
            if(-not $worker.Process -or $codeBox.Text -cnotmatch '^[0-9]{6}$'){$status.Text='Saisis les six chiffres du SMS.';return}
            $verify.Enabled=$false
            try {
                $command=@{action='verify';code=$codeBox.Text}|ConvertTo-Json -Compress
                $codeBox.Clear();$worker.Process.StandardInput.WriteLine($command);$worker.Process.StandardInput.Flush();$command=$null
                $script:WakaPersonalState=Read-WakaPersonalState $worker.Process 5000
            } catch {$script:WakaPersonalState='ERROR';Close-WakaPersonalWorker $worker.Process;$worker.Process=$null}
            $recorded=$true
            try{Save-WakaPersonalResult $script:WakaPersonalState}catch{$recorded=$false}
            switch($script:WakaPersonalState){
                'VERIFIED' {$status.Text='Code correct : reception personnelle verifiee. Aucun compte ou session WAKA cree.';$codeBox.Enabled=$false}
                'WRONG_CODE' {$status.Text='Code incorrect. Verifie le SMS, sans le partager dans le chat.';$verify.Enabled=$true}
                'LOCKED' {$status.Text='Cinq essais incorrects : verification bloquee. Aucun renvoi.';$codeBox.Enabled=$false}
                'EXPIRED' {$status.Text='Code expire. Aucun renvoi automatique.';$codeBox.Enabled=$false}
                default {$status.Text='Verification terminee ou interrompue.';$codeBox.Enabled=$false}
            }
            if(-not $recorded){$status.Text+="`r`nResultat local non enregistre. Ne relance pas l'envoi."}
        })
        $timer=New-Object Windows.Forms.Timer;$timer.Interval=1000
        $timer.Add_Tick({
            if($script:WakaPersonalWatch.IsRunning -and $script:WakaPersonalWatch.Elapsed.TotalMinutes -ge 5 -and
                $script:WakaPersonalState -in @('ACCEPTED','UNKNOWN','WRONG_CODE')) {
                $script:WakaPersonalState='EXPIRED';$codeBox.Clear();$codeBox.Enabled=$false;$verify.Enabled=$false
                $status.Text='Code expire. Aucun renvoi automatique.'
                try{Save-WakaPersonalResult 'EXPIRED'}catch{$status.Text+="`r`nResultat local non enregistre."}
                Close-WakaPersonalWorker $worker.Process;$worker.Process=$null
            }
        })
        $form.Add_Shown({
            $form.Activate();$form.BringToFront();$timer.Start()
            # Invoked only for the explicit retry command authorized by the human on 17 October.
            # This is the application's send action, not a simulated UI click or retry loop.
            if($automaticRequest){& $sendAction}
        })
        Save-WakaPersonalResult 'READY'
        $null=$form.ShowDialog()
        if(-not $script:WakaPersonalRequested){Save-WakaPersonalResult 'CANCELLED'}
        Write-Output ('WAKA_PERSONAL_SMS_'+$script:WakaPersonalState)
    } finally {
        if($timer){$timer.Stop();$timer.Dispose()}
        Close-WakaPersonalWorker $worker.Process
        if($form){$form.Dispose()}
        if($access){$access.clientId=$null;$access.clientSecret=$null;$access.phoneNumber=$null}
        $script:WakaPersonalWatch=$null
    }
}

try {
    $modes=0;foreach($mode in @($SelfTest,$DiagnoseProvider,$AuthorizedRetry,$NewRecipientTest,$PrivateInputProbe)){if($mode){$modes++}}
    if($modes -gt 1){throw 'MODES_EXCLUSIVE'}
    if($RecipientFromPrivateStdin -and -not $NewRecipientTest){throw 'PRIVATE_INPUT_MODE_REQUIRED'}
    if($SelfTest){Invoke-WakaPersonalReaderSelfTest}
    elseif($PrivateInputProbe){
        $probe=Read-WakaPrivateRecipient
        try{[pscustomobject]@{valid=($probe -cmatch '^\+2376[0-9]{8}$');network_calls=0;sms_sent=0}|ConvertTo-Json -Compress}
        finally{$probe=$null}
    }
    elseif($DiagnoseProvider){Invoke-WakaPersonalProviderDiagnostic|ConvertTo-Json -Compress}
    elseif($NewRecipientTest){
        if($RecipientFromPrivateStdin){
            # Phone travels only in redirected stdin, never in process arguments/environment/files.
            $newPhone=Read-WakaPrivateRecipient
            if($newPhone -cnotmatch '^\+2376[0-9]{8}$'){throw 'INVALID_PRIVATE_RECIPIENT'}
        }else{$newPhone=Read-WakaOtherRecipientForm}
        try{if($newPhone){Show-WakaPersonalSmsForm $newPhone}else{Write-Output 'WAKA_PERSONAL_SMS_CANCELLED'}}
        finally{$newPhone=$null}
    }
    else{Show-WakaPersonalSmsForm}
} catch {
    Write-Output 'WAKA_PERSONAL_SMS_ERROR_NO_PRIVATE_OUTPUT'
    [pscustomobject]@{exception_type=$_.Exception.GetType().FullName;line=$_.InvocationInfo.ScriptLineNumber}|ConvertTo-Json -Compress
    elseif(-not $DiagnoseProvider) {
        Add-Type -AssemblyName System.Windows.Forms
        $null=[Windows.Forms.MessageBox]::Show('Le test ne peut pas demarrer. Aucun nouvel envoi automatique. Conserve les fichiers prives et signale cette erreur dans le chat, sans tes acces ni ton numero.','WAKA - test SMS indisponible')
    }
    exit 1
}
