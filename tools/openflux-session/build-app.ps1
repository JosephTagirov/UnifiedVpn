param([string]$SourcePath='', [string]$ArtifactsPath='', [string]$AndroidNdk='', [switch]$SkipAndroid, [switch]$TestsOnly)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$pin='f492a1a33b7581ad8f88118f97e0b866adda3cdc'
if(-not $SourcePath){$SourcePath=Join-Path $repo '.downloads\openflux\upstream-v0.17.1-20261101'}
if(-not $ArtifactsPath){$ArtifactsPath=Join-Path $repo '.downloads\openflux\session-app-2026110105'}
$SourcePath=(Resolve-Path -LiteralPath $SourcePath).Path;$ArtifactsPath=[IO.Path]::GetFullPath($ArtifactsPath)
if((& git -C $SourcePath rev-parse HEAD).Trim() -ne $pin){throw 'OpenFlux source pin mismatch'}
$destination=Join-Path $SourcePath 'cmd\unified-session';New-Item -ItemType Directory -Force -Path $destination|Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot 'overlay\cmd\unified-session\*.go') -Destination $destination
function Invoke-Go([string[]]$Arguments){& go @Arguments;if($LASTEXITCODE -ne 0){throw 'OpenFlux build/check failed'}}
$saved=@{}
foreach($name in @('GOTOOLCHAIN','GOMAXPROCS','GOOS','GOARCH','GOARM','CGO_ENABLED','CC')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
Push-Location -LiteralPath $SourcePath
try{
 $env:GOTOOLCHAIN='go1.26.6';$env:GOMAXPROCS='2';$env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
 Invoke-Go @('test','-p=2','-short','./transport','./tunnel','./socks5','./share','./cmd/unified-session')
 if($TestsOnly){return}
 New-Item -ItemType Directory -Force -Path $ArtifactsPath|Out-Null
 $files=[ordered]@{}
 foreach($os in @('windows','linux')){
  $env:GOOS=$os;$name=if($os -eq 'windows'){'openflux-session-windows-amd64.exe'}else{'openflux-session-linux-amd64'}
  Invoke-Go @('build','-p=2','-trimpath','-buildvcs=false','-ldflags=-s -w','-o',(Join-Path $ArtifactsPath $name),'./cmd/unified-session')
  $files[$name]=(Get-FileHash -LiteralPath (Join-Path $ArtifactsPath $name) -Algorithm SHA256).Hash.ToLowerInvariant()
 }
 $expected='unified-openflux-session 3 upstream='+$pin+' protocol=upstream-openflux-session'
 if((& (Join-Path $ArtifactsPath 'openflux-session-windows-amd64.exe') --version).Trim() -ne $expected){throw 'OpenFlux provenance mismatch'}
 if(-not $SkipAndroid){
  if(-not $AndroidNdk){$AndroidNdk=(Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE 'Android\Sdk\ndk') -Directory|Sort-Object Name -Descending|Select-Object -First 1).FullName}
  $compilers=Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
  foreach($t in @(@{abi='arm64-v8a';arch='arm64';cc='aarch64-linux-android24-clang.cmd'},@{abi='armeabi-v7a';arch='arm';cc='armv7a-linux-androideabi24-clang.cmd'},@{abi='x86_64';arch='amd64';cc='x86_64-linux-android24-clang.cmd'})){
   $env:GOOS='android';$env:GOARCH=$t.arch;$env:CGO_ENABLED='1';$env:CC=Join-Path $compilers $t.cc
   if($t.arch -eq 'arm'){$env:GOARM='7'}else{Remove-Item Env:GOARM -ErrorAction SilentlyContinue}
   $relative='android/'+$t.abi+'/libopenflux_session.so';$output=Join-Path $ArtifactsPath $relative;New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output)|Out-Null
   Invoke-Go @('build','-p=2','-trimpath','-buildvcs=false','-ldflags=-s -w -checklinkname=0','-o',$output,'./cmd/unified-session')
   $files[$relative]=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
  }
 }
 $source=Join-Path $ArtifactsPath 'openflux-session-source.tar.gz'
 & tar -czf $source --exclude=.git --exclude=build --exclude='*.exe' --exclude='*.so' --exclude='cookies*.json' -C $SourcePath .
 if($LASTEXITCODE -ne 0){throw 'OpenFlux source archive failed'}
 $files['openflux-session-source.tar.gz']=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
 $overlay=[ordered]@{};Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'overlay\cmd\unified-session') -File|ForEach-Object{$overlay['overlay/cmd/unified-session/'+$_.Name]=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
 $manifest=@{schema=1;upstream=$pin;version='0.17.1';protocol='upstream-openflux-session';version_text=$expected;go_version='go1.26.6';files=$files;overlay=$overlay}
 [IO.File]::WriteAllText((Join-Path $ArtifactsPath 'manifest.json'),($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}finally{Pop-Location;foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
