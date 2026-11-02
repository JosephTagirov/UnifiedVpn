param([string]$SourcePath='', [string]$ArtifactsPath='', [switch]$TestsOnly)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'build-app.ps1') -SourcePath $SourcePath -ArtifactsPath $ArtifactsPath -SkipAndroid -TestsOnly:$TestsOnly
