"""Exercise the production MSI preflight in an isolated per-user product family."""
from pathlib import Path
import json,os,shutil,subprocess,uuid,xml.sax.saxutils as xml

root=Path(__file__).resolve().parent.parent
uid=uuid.uuid4().hex
work=root/'build'/('msi-process-fixture-'+uid);work.mkdir()
family=str(uuid.uuid4()).upper()
assert family!='6F0AAF78-DBED-4745-9D95-9E63F10A30DE'
codes=[str(uuid.uuid4()).upper() for _ in range(2)]
wix=Path(shutil.which('candle.exe') or root/'build/wix311/candle.exe').parent
env=dict(os.environ,PATH=str(wix)+os.pathsep+os.environ['PATH'])
guard=root/'desktopApp/build/tmp'/('scoped-fixture-'+uid)/'UnifiedVpnGuard.CA.dll'
def run(args,**kwargs):
    result=subprocess.run([str(a) for a in args],capture_output=True,text=True,encoding='utf-8',errors='replace',env=env,**kwargs)
    assert result.returncode==0,result.stdout+result.stderr
    return result.stdout
run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',root/'tools/Build-WindowsMsiGuard.ps1','-OutputPath',guard],timeout=45)
fixture=work/'Fixture.cs'
fixture.write_text('''using System;using System.IO;using System.Windows.Forms;
class Fixture{[STAThread]static void Main(){var form=new Form{ShowInTaskbar=false,Opacity=0};var timer=new Timer{Interval=100};timer.Tick+=(s,e)=>{if(File.Exists(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"exit.request")))form.Close();};timer.Start();Application.Run(form);}}''')
run([Path(os.environ['SystemRoot'])/'Microsoft.NET/Framework64/v4.0.30319/csc.exe','/nologo','/target:winexe','/platform:x64','/reference:System.Windows.Forms.dll','/out:'+str(work/'UnifiedVPN.exe'),fixture])
component=str(uuid.uuid4()).upper()
for index,version in enumerate(['0.1.5','0.1.6']):
    payload=work/f'payload-{index}.txt';payload.write_text(str(index))
    scoped=''
    if index:
        scoped=f'''<Binary Id="UnifiedVpnProcessGuard" SourceFile="{xml.escape(str(guard))}"/>
<CustomAction Id="UnifiedVpnDetectInstalledApp" BinaryKey="UnifiedVpnProcessGuard" DllEntry="UnifiedVpnDetectInstalledApp" Execute="immediate" Return="check"/>
<CustomAction Id="UnifiedVpnRequireClosed" Error="Close UnifiedVPN from its installed directory and retry."/>
<InstallExecuteSequence><Custom Action="UnifiedVpnDetectInstalledApp" Before="UnifiedVpnRequireClosed">NOT REMOVE~="ALL"</Custom><Custom Action="UnifiedVpnRequireClosed" Before="InstallValidate">NOT REMOVE~="ALL" AND UNIFIEDVPN_APP_RUNNING</Custom><RemoveExistingProducts After="InstallInitialize"/></InstallExecuteSequence>'''
    else:scoped='<InstallExecuteSequence><RemoveExistingProducts After="InstallInitialize"/></InstallExecuteSequence>'
    text=f'''<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi"><Product Id="{codes[index]}" Name="UnifiedVPN Scoped Fixture {uid}" Language="1033" Version="{version}" Manufacturer="UnifiedVPN Test" UpgradeCode="{family}">
<Package InstallerVersion="500" Compressed="yes" InstallScope="perUser"/><MediaTemplate EmbedCab="yes"/>
<Property Id="REBOOT" Value="ReallySuppress"/><SetProperty Id="ARPINSTALLLOCATION" Value="[INSTALLDIR]" After="CostFinalize"/>
<Upgrade Id="{family}"><UpgradeVersion Maximum="{version}" IncludeMaximum="yes" MigrateFeatures="yes" Property="JP_UPGRADABLE_FOUND"/></Upgrade>
<Directory Id="TARGETDIR" Name="SourceDir"><Directory Id="LocalAppDataFolder"><Directory Id="INSTALLDIR" Name="UnifiedVPN-Scoped-Fixture-{uid}"><Component Id="Payload" Guid="{component}" Win64="yes"><File Id="Data" Name="payload.txt" Source="{xml.escape(str(payload))}" KeyPath="yes"/><File Id="App" Name="UnifiedVPN.exe" Source="{xml.escape(str(work/'UnifiedVPN.exe'))}"/></Component></Directory></Directory></Directory>
<Feature Id="Main" Level="1"><ComponentRef Id="Payload"/></Feature>{scoped}</Product></Wix>'''
    source=work/f'{index}.wxs';source.write_text(text)
    run([wix/'candle.exe','-nologo','-arch','x64','-out',work/f'{index}.wixobj',source])
    run([wix/'light.exe','-nologo','-sice:ICE38','-sice:ICE64','-out',work/f'{index}.msi',work/f'{index}.wixobj'])

body=r'''$ErrorActionPreference='Stop'
$work='__WORK__';$target=Join-Path $env:LOCALAPPDATA 'UnifiedVPN-Scoped-Fixture-__UID__'
$portable=Join-Path $work 'portable';$null=New-Item -ItemType Directory -Path $portable
Copy-Item -LiteralPath "$work/UnifiedVPN.exe" -Destination "$portable/UnifiedVPN.exe"
$other=Start-Process -FilePath "$portable/UnifiedVPN.exe" -WindowStyle Hidden -PassThru
$registered=$null;$checks=[Collections.Generic.List[string]]::new()
function Msi([string]$arguments,[int]$expected,[string]$name) {
 $p=Start-Process -FilePath "$env:SystemRoot/System32/msiexec.exe" -ArgumentList ($arguments+' /qn /norestart REBOOT=ReallySuppress MSIRESTARTMANAGERCONTROL=Disable /L*v "'+$work+'/'+$name+'.log"') -WindowStyle Hidden -Wait -PassThru
 if($p.ExitCode -ne $expected){throw "MSI $name returned $($p.ExitCode), expected $expected"};$p.Dispose()
}
function Stop-Fixture($process,[string]$directory) {
 if($null -eq $process){return}
 $process.Refresh();if(-not $process.HasExited){[IO.File]::WriteAllText((Join-Path $directory 'exit.request'),'close');if(-not $process.WaitForExit(5000)){throw 'Owned fixture did not close'}}
 Remove-Item -LiteralPath (Join-Path $directory 'exit.request') -ErrorAction SilentlyContinue
}
try {
 Msi ('/i "'+$work+'\0.msi"') 0 'install-old';$checks.Add('old-version-installed')
 $registered=Start-Process -FilePath "$target/UnifiedVPN.exe" -WindowStyle Hidden -PassThru
 Start-Sleep -Milliseconds 700
 Msi ('/i "'+$work+'\1.msi"') 1603 'registered-running'
 if(-not ([IO.File]::ReadAllText("$work/registered-running.log").Contains('UnifiedVpnRequireClosed. Return value 3'))){throw 'Wrong reason for blocked upgrade'}
 if([IO.File]::ReadAllText("$target/payload.txt") -ne '0' -or $registered.HasExited){throw 'Blocked upgrade changed or closed old installation'}
 $checks.Add('registered-process-blocked-before-removal')
 Msi ('/i "'+$work+'\1.msi" INSTALLDIR="'+$work+'\different-target"') 1603 'old-location-running'
 $checks.Add('old-registered-location-still-checked')
 Stop-Fixture $registered $target;$registered.Dispose();$registered=$null
 Msi ('/i "'+$work+'\1.msi"') 0 'upgrade-with-portable'
 $other.Refresh();if($other.HasExited -or [IO.File]::ReadAllText("$target/payload.txt") -ne '1'){throw 'Portable copy interfered with upgrade'}
 $checks.Add('upgrade-passed-with-portable-running')
 Msi ('/i "'+$work+'\1.msi" REINSTALL=ALL REINSTALLMODE=vomus') 0 'repair-with-portable'
 $checks.Add('repair-passed-with-portable-running')
 $registered=Start-Process -FilePath "$target/UnifiedVPN.exe" -WindowStyle Hidden -PassThru
 Start-Sleep -Milliseconds 700
 Msi ('/i "'+$work+'\1.msi" REINSTALL=ALL REINSTALLMODE=vomus') 1603 'repair-registered-running'
 $checks.Add('repair-blocked-for-registered-process')
 Stop-Fixture $registered $target;$registered.Dispose();$registered=$null
 $other.Refresh();if($other.HasExited){throw 'Unrelated portable process was stopped'}
 $checks.Add('portable-process-preserved')
} finally {
 if($null -ne $registered){Stop-Fixture $registered $target;$registered.Dispose()}
 Stop-Fixture $other $portable;$other.Dispose()
 foreach($code in @('__OLD__','__NEW__')){
  $p=Start-Process -FilePath "$env:SystemRoot/System32/msiexec.exe" -ArgumentList ('/x {'+$code+'} /qn /norestart REBOOT=ReallySuppress') -WindowStyle Hidden -Wait -PassThru
  if($p.ExitCode -notin @(0,1605)){throw "Fixture uninstall failed: $($p.ExitCode)"};$p.Dispose()
 }
}
if(Test-Path -LiteralPath $target){throw 'Fixture installation directory remains'}
@{passed=$true;checks=$checks;productionFamilyUntouched=$true;fixtureRemoved=$true}|ConvertTo-Json -Depth 4 -Compress
'''
body=body.replace('__WORK__',str(work)).replace('__UID__',uid).replace('__OLD__',codes[0]).replace('__NEW__',codes[1])
script=work/'exercise.ps1';script.write_text(body,encoding='utf-8-sig')
result=json.loads(run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',script],timeout=180))
(root/'build/msi-process-guard-20261103.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
