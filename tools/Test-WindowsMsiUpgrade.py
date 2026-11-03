from pathlib import Path
import json,subprocess,uuid,xml.sax.saxutils as xml
root=Path(__file__).resolve().parent.parent
uid=uuid.uuid4().hex
work=root/'build'/('msi-upgrade-fixture-'+uid)
work.mkdir()
family=str(uuid.uuid4()).upper()
assert family!='6F0AAF78-DBED-4745-9D95-9E63F10A30DE'
component=str(uuid.uuid4()).upper()
codes=[]
paths=[]
for number,version in enumerate(['0.1.5','0.1.6','0.1.6']):
    code=str(uuid.uuid4()).upper();codes.append(code)
    payload=work/f'payload-{number}.txt';payload.write_text(str(number))
    text=f'''<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi"><Product Id="{code}" Name="UnifiedVPN Upgrade Fixture {uid}" Language="1033" Version="{version}" Manufacturer="UnifiedVPN Test" UpgradeCode="{family}">
<Package InstallerVersion="500" Compressed="yes" InstallScope="perUser"/>
<MediaTemplate EmbedCab="yes"/><Property Id="REBOOT" Value="ReallySuppress"/>
<MajorUpgrade AllowSameVersionUpgrades="yes" Schedule="afterInstallInitialize" DowngradeErrorMessage="Newer fixture already installed"/>
<Directory Id="TARGETDIR" Name="SourceDir"><Directory Id="LocalAppDataFolder"><Directory Id="INSTALLDIR" Name="UnifiedVPN-Upgrade-Fixture-{uid}"><Component Id="Payload" Guid="{component}" Win64="yes"><File Id="Data" Name="payload.txt" Source="{xml.escape(str(payload))}" KeyPath="yes"/></Component></Directory></Directory></Directory>
<Feature Id="Main" Level="1"><ComponentRef Id="Payload"/></Feature></Product></Wix>'''
    source=work/f'{number}.wxs';source.write_text(text)
    for tool,args in [('candle',['-nologo','-arch','x64','-out',str(work/f'{number}.wixobj'),str(source)]),('light',['-nologo','-sice:ICE38','-sice:ICE64','-out',str(work/f'{number}.msi'),str(work/f'{number}.wixobj')])]:
        result=subprocess.run([str(root/'build/wix311'/f'{tool}.exe'),*args],capture_output=True)
        assert result.returncode==0,result.stdout.decode(errors='replace')
    paths.append(work/f'{number}.msi')

script=work/'exercise.ps1'
body=r'''$ErrorActionPreference='Stop'
$paths=ConvertFrom-Json @'
__PATHS__
'@
$codes=ConvertFrom-Json @'
__CODES__
'@
$target=Join-Path $env:LOCALAPPDATA 'UnifiedVPN-Upgrade-Fixture-__UID__'
$results=[Collections.Generic.List[object]]::new()
function Run-Msi([string]$arguments) {
 $process=Start-Process -FilePath "$env:SystemRoot/System32/msiexec.exe" -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
 if($process.ExitCode -ne 0){throw "Fixture MSI failed: $($process.ExitCode)"}
}
try {
 for($i=0;$i -lt 3;$i++) {
  Run-Msi ('/i "'+$paths[$i]+'" /qn /norestart REBOOT=ReallySuppress /L*v "__WORK__/install-'+$i+'.log"')
  if([IO.File]::ReadAllText((Join-Path $target 'payload.txt')) -ne $i.ToString()){throw 'Fixture content was not replaced'}
  $results.Add(@{step=@('install','upgrade','same-version-new-build')[$i];passed=$true})
 }
 Run-Msi ('/i "'+$paths[2]+'" /qn /norestart REBOOT=ReallySuppress REINSTALL=ALL REINSTALLMODE=vomus')
 $results.Add(@{step='same-build-repair';passed=$true})
} finally {
 foreach($code in $codes) {
  $process=Start-Process -FilePath "$env:SystemRoot/System32/msiexec.exe" -ArgumentList ('/x {'+$code+'} /qn /norestart REBOOT=ReallySuppress') -WindowStyle Hidden -Wait -PassThru
  if($process.ExitCode -notin @(0,1605)){throw 'Fixture cleanup failed'}
 }
}
if(Test-Path -LiteralPath $target){throw 'Fixture installation directory remains'}
@{passed=$true;transactions=$results;productionFamilyUntouched=$true;fixtureRemoved=$true}|ConvertTo-Json -Depth 4 -Compress
'''
body=body.replace('__PATHS__',json.dumps([str(p) for p in paths])).replace('__CODES__',json.dumps(codes)).replace('__UID__',uid).replace('__WORK__',work.as_posix())
script.write_text(body,encoding='utf-8-sig')
result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(script)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
assert result.returncode==0,result.stdout+result.stderr
report=json.loads(result.stdout)
(root/'build/msi-upgrade-20261103.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
