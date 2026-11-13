<#
.SYNOPSIS
Stages unchanged Windows native resources from the verified official 0.1.8 release.
.DESCRIPTION
Only the 19 pinned native/data/notice files are copied. Old UI classes and JARs
are never staged. Engine pins, helper sources, archive SHA-256 and every extracted
file are checked; changed native inputs require a normal native rebuild.

Run once before Gradle, never concurrently with a build in the same directory:
  .\tools\Stage-VerifiedWindowsReleaseNatives.ps1
  .\gradlew.bat :sharedUI:jvmTest :desktopApp:test :desktopApp:jar `
    :desktopApp:packageReleaseExe :desktopApp:packageReleasePortableZip `
    :desktopApp:packageReleaseUpdateBundle --no-daemon --no-configuration-cache `
    --no-parallel --max-workers=2 -x :desktopApp:stageHostDesktopNativeResources

The sole excluded task is the native producer/Sync aggregate. Keep
verifyDesktopNativeResources, verifyDesktopPackageVersion, verifyDesktopAppImage
and the installer-policy checks enabled. Packaging does not install the app,
change network settings or authorize publication. -VerifyOnly writes nothing
and requires an existing archive. Normal mode downloads a missing default cache
only from the fixed official release URL, then verifies its pinned digest.
#>
[CmdletBinding()]
param([string]$ArchivePath, [switch]$VerifyOnly)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$project = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$archiveSha = 'ccc2997eaf9bba475837897b471ada5d56a58db341ccef84f377d08e5172a0a3'
$archiveName = 'UnifiedVPN-0.1.8-build.2026110305-portable-manual.zip'
$releaseUrl = 'https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.8/' + $archiveName
$pins = @{
 'olcbox.olcrtcSha'='5b1e6172a9f620d477624c6575c392e70449d333'
 'olcbox.awgCoreSha'='b4be66275d73b339647736d5276aa6b729d399d3'
 'olcbox.awgWireguardSha'='f60e4eda0d0839dcee8350a186f622912cd4d93e'
 'olcbox.xrayVersion'='26.10.31'
 'olcbox.xraySha'='a4a3dff5b20c2faae567b742c172c7b95e75c083'
 'olcbox.openFluxSha'='d34dc8caa70ca059cd80d8f5753499361052dabc'
}
$files = [ordered]@{
 'native/Microsoft.Web.WebView2.Core.dll'='d684f9f3d91fe7c8e210fae55a335533d49822974a59413993a2ce0c10d11a9e'
 'native/Microsoft.Web.WebView2.WinForms.dll'='e743a1f78c24c4fa8e226756088d93219b2a09550952f780954c6178c0daeb20'
 'native/WebView2-LICENSE.txt'='0af8f1b807512aae39c2ac1aa4d0cae65cabecb6fd554b8439a5162a0d6eca55'
 'native/WebView2-NOTICE.txt'='106423785c5b7eba0a8e61d1837f2132e9c828e20ad530f565d981c1df60dd90'
 'native/WebView2Loader.dll'='ae26d6ef7c8f6b533585ead76318b6cbf8efad708064953a312dd97f4761ba45'
 'native/olcrtc-vkcalls-windows-amd64.exe'='e3e4d6da0541ccf9f2be776b9e31f347cc0ef37e5ed50d2544b41ac61daa22d2'
 'native/olcrtc-windows-amd64.exe'='0cb3ef33ad6ceef36473a7ea7d4e9b88cb3572f5881a787ebc64e5349ba2365b'
 'native/openflux-session-windows-amd64.exe'='e22b0bf292ceeaf9df32ea913b2b36ef8dab8ab50a5d35d653685683a9e5be3f'
 'native/openflux-windows-amd64.exe'='a2d516c88861d8d5e1e5087a92e23633da0df09884cc182db391f56a080570ab'
 'native/sing-box-awg-windows-amd64.exe'='b561d0a108c68fbc2f4f937f6bdd5e388e7d22bfa168d59e5c01c6863ea15b67'
 'native/tun2socks-windows-amd64.exe'='a1f8ac84852ed9a9c7a50949cd0290b6f1594e118bf053416b9b55b7cd7ae414'
 'native/unifiedvpn-browser-helper.exe'='6769bb725348036e1e31be9783bf385dd8d89d6a02cc16dfbb1901db24de8afc'
 'native/unifiedvpn-tun-helper.exe'='4c4cdce880383ac0c6272efc50ce7d501b6435f4bfbf610b352cff91c50a35c2'
 'native/wintun.dll'='e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce'
 'native/xray-windows-amd64.exe'='03e73e07dc535a0d28ac848f015b9869a96a4f451edb3028361bf5538f00ba6e'
 'olcrtc-data/names'='d50499b4f3f5ca67bca3af3739e352378e1973b0e35d121b46aa04b055957c12'
 'olcrtc-data/surnames'='03601ac11edc92a06890d03e0ccac6b4b266aef7a8b7a8f0e742b5c1a9e5ee86'
 'vkcalls/LICENSE'='0ddd54d7c41176f692863282671e549c0cf4e1a564d0a4dc38c153af1a085696'
 'vkcalls/NOTICE'='7650228b163f2c8cc261e506f8f386db440b9c631bb7f129d39e5369f328bf8a'
}
# Source hashes use UTF-8 with LF; verified against the published release commit
# 65c35fe6b2624e0e47f47cbf0634a5f06b0e79fe, allowing a CRLF Git checkout.
$helperSources = @{
 'desktopApp/src/windowsTunHelper/helper.manifest'='eb08f65828fa160a9d5cc8ce903f511a045565711213c4760ea8b4039c9c9eb3'
 'desktopApp/src/windowsTunHelper/network.ps1'='dbf3a350ee57dccd6df417c35835111f5e3577ed24d538dfdb64b2c341ab0330'
 'desktopApp/src/windowsTunHelper/TunHelper.cs'='66833ee0e68dfcd32ff1ed8dadf1f4bf1db71f4aeb9b2394fe0ca506f4624fa7'
 'desktopApp/src/windowsBrowserHelper/BrowserHelper.cs'='3e4479c9c61c58602a39c2bba98a5444900714165c285aa306fd1f36ff61d85b'
 'desktopApp/src/windowsBrowserHelper/helper.manifest'='db1fdb766bbf77b288c9654a6724ae79031c08ab3e7c85a1c723c266aff3d455'
 'tools/Build-WindowsBrowserHelper.ps1'='cd6092d6f53ad0bfb39e585c95b4a9ec8efcf29dd5c9763d77fb954d3102759c'
 'tools/Build-WindowsTunHelper.ps1'='1b960c6c51427c16dd4ccdc830b71083bd21c52452744713d0dcd32e09ac018f'
}
function Hash-Bytes([byte[]]$Bytes) {
 $sha=[Security.Cryptography.SHA256]::Create()
 try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant() }
 finally { $sha.Dispose() }
}
function No-Link([string]$Path) {
 if ((Test-Path -LiteralPath $Path) -and ((Get-Item -LiteralPath $Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Reparse point rejected: $Path" }
}
function Read-Entry([IO.Compression.ZipArchiveEntry]$Entry) {
 if ($Entry.Length -le 0 -or $Entry.Length -gt 128MB -or (($Entry.ExternalAttributes -shr 16) -band 0xf000) -eq 0xa000) { throw 'Invalid native archive entry' }
 $input=$Entry.Open();$memory=[IO.MemoryStream]::new()
 try { $input.CopyTo($memory);return ,$memory.ToArray() }
 finally { $input.Dispose();$memory.Dispose() }
}
$properties=[IO.File]::ReadAllText((Join-Path $project 'gradle.properties'))
foreach($key in $pins.Keys) {
 $values=[regex]::Matches($properties,'(?m)^'+[regex]::Escape($key)+'=([^\r\n]+)\r?$')
 if($values.Count -ne 1 -or $values[0].Groups[1].Value -cne $pins[$key]) { throw "Native pin changed: $key; rebuild natives normally" }
}
foreach($relative in $helperSources.Keys) {
 $source=Join-Path $project $relative;No-Link $source
 $text=[IO.File]::ReadAllText($source).Replace("`r`n","`n")
 if((Hash-Bytes ([Text.Encoding]::UTF8.GetBytes($text))) -cne $helperSources[$relative]) { throw "Native helper source changed: $relative; rebuild natives normally" }
}
$build=[IO.File]::ReadAllText((Join-Path $project 'desktopApp/build.gradle.kts'))
$start=$build.IndexOf('fun requiredHostNativeResourcePaths()');$end=$build.IndexOf('currentBuildOs.isLinux ->',$start)
if($start -lt 0 -or $end -lt $start) { throw 'Cannot determine native resource whitelist' }
$section=[regex]::Replace($build.Substring($start,$end-$start),'(?m)^\s*//.*$','')
$required=@([regex]::Matches($section,'add\("([^"]+)"\)') | ForEach-Object {$_.Groups[1].Value} | Sort-Object)
if(($required -join "`n") -cne (($files.Keys | Sort-Object) -join "`n")) { throw 'Windows resource whitelist changed; review pinned native reuse' }
foreach($pin in @('val expectedOpenFluxWrapper = 9','val expectedOpenFluxSessionUpstream = "f492a1a33b7581ad8f88118f97e0b866adda3cdc"')) {
 if(-not $build.Contains($pin)) { throw 'OpenFlux wrapper/session pin changed; rebuild natives normally' }
}
$explicitArchive=$PSBoundParameters.ContainsKey('ArchivePath')
if(-not $explicitArchive) { $ArchivePath=Join-Path $project ('.downloads/windows-release/'+$archiveName) }
$ArchivePath=[IO.Path]::GetFullPath($ArchivePath)
if(-not (Test-Path -LiteralPath $ArchivePath)) {
 if($explicitArchive -or $VerifyOnly) { throw 'The pinned release archive is missing' }
 $cache=Split-Path -Parent $ArchivePath;No-Link $cache
 $null=New-Item -ItemType Directory -Path $cache -Force
 $partial=Join-Path $cache ('release-'+[guid]::NewGuid().ToString('N')+'.partial')
 try {
  Invoke-WebRequest -UseBasicParsing -Uri $releaseUrl -OutFile $partial -TimeoutSec 240
  if((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -cne $archiveSha) { throw 'Official archive digest mismatch' }
  Move-Item -LiteralPath $partial -Destination $ArchivePath
 } finally { if(Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force } }
}
No-Link $ArchivePath
if((Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $archiveSha) { throw 'Release archive digest mismatch' }
$generated=[IO.Path]::GetFullPath((Join-Path $project 'desktopApp/build/generated'))
$destination=Join-Path $generated 'hostDesktopNativeResources'
foreach($path in @((Join-Path $project 'desktopApp/build'),$generated,$destination)) { No-Link $path }
$stage=$null;$zip=$null;$jar=$null;$jarMemory=$null
try {
 $zip=[IO.Compression.ZipFile]::OpenRead($ArchivePath)
 $jars=@($zip.Entries | Where-Object {$_.FullName -match '(^|/)UnifiedVPN/app/desktopApp-[a-f0-9]+\.jar$'})
 if($jars.Count -ne 1) { throw 'Expected exactly one desktop application JAR in the official archive' }
 $jarMemory=[IO.MemoryStream]::new((Read-Entry $jars[0]),$false)
 $jar=[IO.Compression.ZipArchive]::new($jarMemory,[IO.Compression.ZipArchiveMode]::Read,$true)
 $nativeEntries=@($jar.Entries | Where-Object {$_.FullName -match '^(native|olcrtc-data|vkcalls)/' -and -not $_.FullName.EndsWith('/')})
 if(($nativeEntries.FullName | Sort-Object) -join "`n" -cne (($files.Keys | Sort-Object) -join "`n")) { throw 'Published native entries do not match the exact whitelist' }
 if(-not $VerifyOnly) {
  $null=New-Item -ItemType Directory -Path $generated -Force
  $stage=Join-Path $generated ('.hostDesktopNativeResources-stage-'+[guid]::NewGuid().ToString('N'))
  $null=New-Item -ItemType Directory -Path $stage
 }
 foreach($relative in $files.Keys) {
  $entries=@($jar.Entries | Where-Object {$_.FullName -ceq $relative})
  if($entries.Count -ne 1) { throw "Duplicate or missing native entry: $relative" }
  $bytes=Read-Entry $entries[0]
  if((Hash-Bytes $bytes) -cne $files[$relative]) { throw "Native checksum mismatch: $relative" }
  $goPin=switch($relative) {
   'native/olcrtc-windows-amd64.exe' {$pins['olcbox.olcrtcSha']}
   'native/olcrtc-vkcalls-windows-amd64.exe' {$pins['olcbox.olcrtcSha']}
   'native/sing-box-awg-windows-amd64.exe' {$pins['olcbox.awgCoreSha']}
   'native/xray-windows-amd64.exe' {$pins['olcbox.xraySha']}
   default {$null}
  }
  if($goPin) {
   $metadata=[Text.Encoding]::ASCII.GetString($bytes)
   if(-not $metadata.Contains('vcs.revision='+$goPin) -or -not $metadata.Contains('vcs.modified=false')) { throw "Native Go revision mismatch: $relative" }
   if($relative -eq 'native/sing-box-awg-windows-amd64.exe') {
    foreach($value in @('github.com/throneproj/wireguard-go',$pins['olcbox.awgWireguardSha'].Substring(0,12),'with_wireguard','with_gvisor','with_quic','with_utls')) {
     if(-not $metadata.Contains($value)) { throw 'AWG implementation/build tags mismatch' }
    }
   }
  }
  if($relative -like 'native/openflux*.exe') {
   $metadata=[Text.Encoding]::ASCII.GetString($bytes)
   $expected=if($relative -like '*session*') {@('unified-openflux-session 4','f492a1a33b7581ad8f88118f97e0b866adda3cdc','upstream-openflux-session')} else {@('unified-openflux 9',$pins['olcbox.openFluxSha'],'unified-openflux-aesgcm-v1')}
   foreach($value in $expected) { if(-not $metadata.Contains($value)) { throw 'OpenFlux provenance mismatch' } }
  }
  if($stage) {
   $target=[IO.Path]::GetFullPath((Join-Path $stage $relative))
   if(-not $target.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe staging target' }
   $null=New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force
   [IO.File]::WriteAllBytes($target,$bytes)
  }
 }
 if($stage) {
  $backup=$null
  if(Test-Path -LiteralPath $destination) {
   No-Link $destination
   $backup=Join-Path $generated ('hostDesktopNativeResources.previous-'+[guid]::NewGuid().ToString('N'))
   if([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($destination)) -cne $generated -or [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($backup)) -cne $generated) { throw 'Unsafe native backup target' }
   Move-Item -LiteralPath $destination -Destination $backup
  }
  try { Move-Item -LiteralPath $stage -Destination $destination;$stage=$null }
  catch { if($backup -and -not (Test-Path -LiteralPath $destination)) { Move-Item -LiteralPath $backup -Destination $destination };throw }
 }
 [pscustomobject]@{Verified=$true;Staged=(-not $VerifyOnly);Source='v0.1.8/build.2026110305';ArchiveSha256=$archiveSha;NativeResources=$files.Count;Destination=$destination;ExcludeOnly=':desktopApp:stageHostDesktopNativeResources'} | ConvertTo-Json -Compress
} finally {
 if($jar) {$jar.Dispose()};if($jarMemory) {$jarMemory.Dispose()};if($zip) {$zip.Dispose()}
 if($stage -and (Test-Path -LiteralPath $stage)) {
  $resolved=[IO.Path]::GetFullPath($stage)
  if([IO.Path]::GetDirectoryName($resolved) -cne $generated -or -not [IO.Path]::GetFileName($resolved).StartsWith('.hostDesktopNativeResources-stage-')) { throw 'Unsafe temporary cleanup target' }
  No-Link $resolved;Remove-Item -LiteralPath $resolved -Recurse -Force
 }
}
