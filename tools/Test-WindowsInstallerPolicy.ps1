[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Load only the pure policy assertion; never execute an installer or open an MSI session.
$verifier = Join-Path $PSScriptRoot 'Verify-WindowsInstaller.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($verifier, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'Installer verifier has PowerShell syntax errors' }
$assertions = @($ast.EndBlock.Statements | Where-Object {
    $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $_.Name -eq 'Assert-WindowsReplacementPolicy'
})
if ($assertions.Count -ne 1) { throw 'Expected one installer policy assertion' }
. ([scriptblock]::Create($assertions[0].Extent.Text))

function New-PolicyFixture {
    return @{
        Sequence = @(
            @('CostInitialize', '', '800'),
            @('CostFinalize', '', '1000'),
            @('UnifiedVpnDetectInstalledApp', 'NOT REMOVE~="ALL"', '1397'),
            @('UnifiedVpnRequireClosed', 'NOT REMOVE~="ALL" AND UNIFIEDVPN_APP_RUNNING', '1398'),
            @('InstallValidate', '', '1400'),
            @('InstallInitialize', '', '1500'),
            @('RemoveExistingProducts', '', '1501'),
            @('ProcessComponents', '', '1600')
        )
        Actions = @(
            @('UnifiedVpnDetectInstalledApp', '1', 'UnifiedVpnProcessGuard', 'UnifiedVpnDetectInstalledApp'),
            @('UnifiedVpnRequireClosed', '19', '', 'Close UnifiedVPN, then run this installer again.')
        )
        Detectors = @()
    }
}

$script:passed = 0
function Test-Policy([string]$Name, [scriptblock]$Mutate, [string]$ExpectedError = '') {
    $fixture = New-PolicyFixture
    & $Mutate $fixture
    $failure = $null
    try {
        Assert-WindowsReplacementPolicy $fixture.Sequence $fixture.Actions $fixture.Detectors 'UnifiedVPN'
    } catch { $failure = $_.Exception.Message }
    if ($ExpectedError -eq '') {
        if ($null -ne $failure) { throw "$Name failed: $failure" }
    } elseif ($null -eq $failure -or $failure -notlike $ExpectedError) {
        throw "$Name did not reject its unsafe fixture as expected: $failure"
    }
    $script:passed++
}

Test-Policy 'supported replacement order with detection-only guard' { param($f) }
Test-Policy 'legacy removal before costing' { param($f) $f.Sequence[6][2] = '799' } '*sequence is unsafe*'
Test-Policy 'removal outside transaction' { param($f) $f.Sequence[6][2] = '1499' } '*sequence is unsafe*'
Test-Policy 'removal after script generation' { param($f) $f.Sequence[6][2] = '1601' } '*sequence is unsafe*'
Test-Policy 'missing detector action' { param($f) $f.Sequence = @($f.Sequence | Where-Object { $_[0] -ne 'UnifiedVpnDetectInstalledApp' }) } '*sequence is unsafe*'
Test-Policy 'guard after validation' { param($f) $f.Sequence[3][2] = '1401' } '*sequence is unsafe*'
Test-Policy 'duplicate sequence action' { param($f) $f.Sequence += ,@('CostInitialize', '', '801') } '*Duplicate MSI sequence*'
Test-Policy 'guard must run in silent execute sequence' { param($f) $f.Sequence[2][1] = 'UILevel > 2' } '*preflight condition changed*'
Test-Policy 'running application condition cannot be disabled' { param($f) $f.Sequence[3][1] = '0' } '*preflight condition changed*'
Test-Policy 'unrelated pending reboot must not block installation' { param($f) $f.Actions += ,@('UnifiedVpnRequireReboot','19','','Restart Windows') } '*pending Windows reboot*'
Test-Policy 'uninstall bypass is intentional' { param($f) $f.Sequence[3][1] = 'UNIFIEDVPN_APP_RUNNING' } '*preflight condition changed*'
Test-Policy 'missing stop action' { param($f) $f.Actions = @($f.Actions | Where-Object { $_[0] -ne 'UnifiedVpnRequireClosed' }) } '*must stop with an actionable error*'
Test-Policy 'stop action must be MSI error type' { param($f) $f.Actions[1][1] = '51' } '*must stop with an actionable error*'
Test-Policy 'empty stop message' { param($f) $f.Actions[1][3] = '' } '*must stop with an actionable error*'
Test-Policy 'message tells user how to retry' { param($f) $f.Actions[1][3] = 'Installation cannot continue.' } '*messages must explain*'
Test-Policy 'detector return code cannot be ignored' { param($f) $f.Actions[0][1] = '65' } '*must be immediate and checked*'
Test-Policy 'detector cannot be deferred' { param($f) $f.Actions[0][1] = '1025' } '*must be immediate and checked*'
Test-Policy 'unexpected detector entry point' { param($f) $f.Actions[0][3] = 'OtherAction' } '*must be immediate and checked*'
Test-Policy 'legacy filename-only detector rejected' { param($f) $f.Detectors = @(,@('legacy','UnifiedVPN.exe')) } '*must not block portable*'
Test-Policy 'legacy WiX action rejected' { param($f) $f.Actions += ,@('WixCloseApplications','1','WixCA','WixCloseApplications') } '*must not block portable*'
Test-Policy 'detector must use scoped binary' { param($f) $f.Actions[0][2] = 'WixCA' } '*must be immediate and checked*'
Test-Policy 'costing must resolve target before detection' { param($f) $f.Sequence[1][2] = '1399' } '*sequence is unsafe*'

[pscustomobject]@{ Status = 'PASS'; Tests = $script:passed; InstallerExecuted = $false } | ConvertTo-Json -Compress
