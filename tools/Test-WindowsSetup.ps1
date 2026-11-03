param([Parameter(Mandatory=$true)][string]$Installer)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
$assembly=[Reflection.Assembly]::LoadFile([IO.Path]::GetFullPath($Installer))
$policy=$assembly.GetType('InstallerPolicy')
$checks=$policy.GetMethod('SelfTest',[Reflection.BindingFlags]'Static,Public').Invoke($null,@())
$work=[IO.Path]::GetFullPath((Join-Path (Join-Path (Split-Path -Parent $PSScriptRoot) 'build') ('setup-fixture-'+[guid]::NewGuid().ToString('N'))))
$null=New-Item -ItemType Directory -Path $work
$match=Join-Path $work 'registered';$other=Join-Path $work 'unrelated'
$null=New-Item -ItemType Directory -Path $match,$other
$code='using System;using System.IO;using System.Windows.Forms;class TestApp{[STAThread]static void Main(){var form=new Form{Text="Unified VPN test fixture",ShowInTaskbar=false,Opacity=0};form.FormClosing+=(s,e)=>File.WriteAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"closed.ok"),"clean");Application.Run(form);}}'
$source=Join-Path $work 'Fixture.cs';[IO.File]::WriteAllText($source,$code)
$compiler=Join-Path $env:SystemRoot 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
& $compiler /nologo /target:winexe /reference:System.Windows.Forms.dll "/out:$match\UnifiedVPN.exe" $source
if($LASTEXITCODE -ne 0){throw 'Fixture compiler failed'}
Copy-Item -LiteralPath "$match/UnifiedVPN.exe" -Destination "$other/UnifiedVPN.exe"
$first=Start-Process -FilePath "$match/UnifiedVPN.exe" -WindowStyle Hidden -PassThru
$second=Start-Process -FilePath "$other/UnifiedVPN.exe" -WindowStyle Hidden -PassThru
$form=$null
try {
 Start-Sleep -Milliseconds 900
 $type=$assembly.GetType('SetupWindow')
 $form=[Activator]::CreateInstance($type,[Reflection.BindingFlags]'Instance,NonPublic', $null,@($true),$null)
 $product=[Activator]::CreateInstance($assembly.GetType('InstalledProduct'),$true)
 $product.GetType().GetField('Root').SetValue($product,$match)
 $products=$type.GetField('products',[Reflection.BindingFlags]'Instance,NonPublic').GetValue($form)
 $products.Clear();$products.Add($product)
 $type.GetMethod('CloseApplication',[Reflection.BindingFlags]'Instance,NonPublic').Invoke($form,@())
 $first.Refresh();$second.Refresh()
 if(-not $first.HasExited -or $second.HasExited -or -not [IO.File]::Exists("$match/closed.ok")){throw 'Registered app shutdown or process isolation failed'}
 $assert=$type.GetMethod('AssertFilesAvailable',[Reflection.BindingFlags]'Instance,NonPublic')
 $assert.Invoke($form,@())
 $lock=[IO.File]::Open("$match/closed.ok",[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
 $rejected=$false
 try { $assert.Invoke($form,@()) } catch {$rejected=$true} finally {$lock.Dispose()}
 if(-not $rejected){throw 'Locked file was accepted'}
 $payload=$assembly.GetType('MsiPayload').GetMethod('Extract',[Reflection.BindingFlags]'Static,NonPublic')
 $payload.Invoke($null,@([IO.Path]::GetFullPath($Installer),"$work/valid.msi"))
 $corrupt=Join-Path $work 'corrupt.exe';Copy-Item -LiteralPath $Installer -Destination $corrupt
 $stream=[IO.File]::OpenWrite($corrupt)
 try {$stream.Position=$stream.Length-1;$stream.WriteByte(255)} finally {$stream.Dispose()}
 $rejected=$false
 try {$payload.Invoke($null,@($corrupt,"$work/invalid.msi"))} catch {$rejected=$true}
 if(-not $rejected){throw 'Corrupted payload accepted'}
 @{passed=$true;policyChecks=$checks;registeredHiddenAppClosedCleanly=$true;unrelatedProcessPreserved=$true;lockedFileRejected=$true;payloadHashVerified=$true;corruptedPayloadRejected=$true}|ConvertTo-Json -Compress
} finally {
 if($null -ne $form){$form.Dispose()}
 foreach($owned in @($first,$second)) {if(-not $owned.HasExited){$owned.Kill();$owned.WaitForExit(3000)|Out-Null};$owned.Dispose()}
 $parent=[IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSScriptRoot) 'build'))
 if([IO.Path]::GetDirectoryName($work) -ne $parent -or -not [IO.Path]::GetFileName($work).StartsWith('setup-fixture-')){throw 'Unsafe fixture cleanup path'}
 Remove-Item -LiteralPath $work -Recurse -Force
}
