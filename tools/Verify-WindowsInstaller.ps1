[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InstallerPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$ExpectedVersion,
    [Parameter(Mandatory = $true)][ValidateRange(1, [long]::MaxValue)][long]$ExpectedBuild,
    [string]$ExpectedName = 'UnifiedVPN',
    [string]$ExpectedVendor = 'Unknown',
    [guid]$ExpectedUpgradeCode = '6f0aaf78-dbed-4745-9d95-9e63f10a30de',
    [string]$ExportMsiPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Parse PE resources as data. Never load or execute the installer/bootstrapper.
Add-Type -TypeDefinition @'
using System;
using System.IO;

public static class UnifiedVpnMsiPayload {
    private static void Bounds(byte[] bytes, long offset, long count) {
        if (offset < 0 || count < 0 || offset > bytes.LongLength - count)
            throw new InvalidDataException("Invalid installer resource bounds");
    }
    private static ushort U16(byte[] bytes, long offset) {
        Bounds(bytes, offset, 2);
        return BitConverter.ToUInt16(bytes, (int)offset);
    }
    private static uint U32(byte[] bytes, long offset) {
        Bounds(bytes, offset, 4);
        return BitConverter.ToUInt32(bytes, (int)offset);
    }
    private static void Walk(byte[] bytes, long section, uint sectionSize, uint sectionRva,
        uint node, int depth, ref long start, ref uint size, ref int found, ref int visited) {
        if (depth > 8 || ++visited > 65536 || (long)node + 16 > sectionSize)
            throw new InvalidDataException("Invalid installer resource directory");
        long directory = section + node;
        int count = U16(bytes, directory + 12) + U16(bytes, directory + 14);
        if ((long)node + 16 + count * 8L > sectionSize)
            throw new InvalidDataException("Invalid installer resource entries");
        for (int i = 0; i < count; ++i) {
            uint value = U32(bytes, directory + 16 + i * 8L + 4);
            if ((value & 0x80000000U) != 0) {
                Walk(bytes, section, sectionSize, sectionRva, value & 0x7fffffffU,
                    depth + 1, ref start, ref size, ref found, ref visited);
                continue;
            }
            if ((long)value + 16 > sectionSize)
                throw new InvalidDataException("Invalid installer resource data entry");
            uint rva = U32(bytes, section + value);
            uint length = U32(bytes, section + value + 4);
            long relative = (long)rva - sectionRva;
            if (relative < 0 || relative + length > sectionSize)
                throw new InvalidDataException("Invalid installer resource payload");
            long payload = section + relative;
            Bounds(bytes, payload, length);
            if (length >= 8 && U32(bytes, payload) == 0xe011cfd0U && U32(bytes, payload + 4) == 0xe11ab1a1U) {
                start = payload;
                size = length;
                ++found;
            }
        }
    }
    public static void Extract(string input, string output) {
        var info = new FileInfo(input);
        if (info.Length < 512 || info.Length > 1024L * 1024 * 1024)
            throw new InvalidDataException("Unexpected installer size");
        byte[] bytes = File.ReadAllBytes(input);
        // UnifiedVPN's branded bootstrapper appends the audited MSI and a SHA-256 footer.
        long footer = bytes.LongLength - 56;
        if (footer > 512 && System.Text.Encoding.ASCII.GetString(bytes, (int)footer, 16) == "UVPNMSIPAYLOAD01") {
            long length = BitConverter.ToInt64(bytes, (int)footer + 16);
            long payload = footer - length;
            if (length < 512 || length > 1024L * 1024 * 1024 || payload < 512)
                throw new InvalidDataException("Invalid branded installer payload");
            using (var sha = System.Security.Cryptography.SHA256.Create()) {
                byte[] actual = sha.ComputeHash(bytes, (int)payload, (int)length);
                for (int i=0; i<32; i++) if (actual[i] != bytes[(int)footer+24+i])
                    throw new InvalidDataException("Branded installer payload digest mismatch");
            }
            using (var stream = new FileStream(output, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                stream.Write(bytes, (int)payload, (int)length);
            return;
        }
        if (U16(bytes, 0) != 0x5a4d)
            throw new InvalidDataException("Installer is not a PE executable");
        uint pe = U32(bytes, 0x3c);
        if (U32(bytes, pe) != 0x00004550)
            throw new InvalidDataException("Invalid installer PE header");
        ushort count = U16(bytes, pe + 6L);
        if (count < 1 || count > 96)
            throw new InvalidDataException("Invalid installer section count");
        long table = pe + 24L + U16(bytes, pe + 20L);
        long start = 0;
        uint size = 0;
        int found = 0, sections = 0, visited = 0;
        for (int i = 0; i < count; ++i) {
            long header = table + i * 40L;
            Bounds(bytes, header, 40);
            string name = System.Text.Encoding.ASCII.GetString(bytes, (int)header, 8).TrimEnd('\0');
            if (name != ".rsrc") continue;
            ++sections;
            uint sectionRva = U32(bytes, header + 12);
            uint sectionSize = U32(bytes, header + 16);
            uint section = U32(bytes, header + 20);
            Bounds(bytes, section, sectionSize);
            Walk(bytes, section, sectionSize, sectionRva, 0, 0, ref start, ref size, ref found, ref visited);
        }
        if (sections != 1 || found != 1)
            throw new InvalidDataException("Expected exactly one embedded MSI payload");
        using (var stream = new FileStream(output, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            stream.Write(bytes, (int)start, (int)size);
    }
}
'@

function Release-ComObject([object]$Value) {
    if ($null -ne $Value -and [Runtime.InteropServices.Marshal]::IsComObject($Value)) {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($Value)
    }
}

function Get-Sha256([string]$Path) {
    $hash = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try { return ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-', '') }
    finally { $stream.Dispose(); $hash.Dispose() }
}

function Read-MsiRows([object]$Database, [string]$Query, [int]$Columns) {
    $view = $null
    $record = $null
    $rows = [Collections.Generic.List[object]]::new()
    try {
        $view = $Database.OpenView($Query)
        [void]$view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            $row = [string[]]::new($Columns)
            for ($i = 0; $i -lt $Columns; $i++) { $row[$i] = $record.StringData($i + 1) }
            $rows.Add($row)
            Release-ComObject $record
            $record = $null
        }
        return ,$rows.ToArray()
    } finally {
        Release-ComObject $record
        if ($null -ne $view) { [void]$view.Close() }
        Release-ComObject $view
    }
}

function Assert-WindowsReplacementPolicy(
    [object[]]$SequenceRows,
    [object[]]$CustomActionRows,
    [object[]]$CloseApplicationRows,
    [string]$ApplicationName
) {
    $sequence = @{}
    $conditions = @{}
    foreach ($row in $SequenceRows) {
        if ($sequence.ContainsKey($row[0])) { throw 'Duplicate MSI sequence action' }
        $sequence[$row[0]] = [int]$row[2]
        $conditions[$row[0]] = $row[1]
    }
    $ordered = @('CostInitialize', 'WixCloseApplications', 'UnifiedVpnRequireClosed',
        'InstallValidate', 'InstallInitialize', 'RemoveExistingProducts', 'ProcessComponents')
    $previous = 0
    foreach ($action in $ordered) {
        if (-not $sequence.ContainsKey($action) -or $sequence[$action] -le $previous) {
            throw "MSI replacement/preflight sequence is unsafe at $action"
        }
        $previous = $sequence[$action]
    }
    $installOrRepair = 'NOT REMOVE~="ALL"'
    $expectedConditions = @{
        WixCloseApplications = $installOrRepair
        UnifiedVpnRequireClosed = "$installOrRepair AND UNIFIEDVPN_APP_RUNNING"
    }
    foreach ($action in $expectedConditions.Keys) {
        if ($conditions[$action] -cne $expectedConditions[$action]) {
            throw "MSI preflight condition changed for $action"
        }
    }
    $customActions = @{}
    foreach ($row in $CustomActionRows) {
        if ($customActions.ContainsKey($row[0])) { throw 'Duplicate MSI custom action' }
        $customActions[$row[0]] = $row
    }
    if ($sequence.ContainsKey('UnifiedVpnRequireReboot') -or $customActions.ContainsKey('UnifiedVpnRequireReboot')) {
        throw 'An unrelated pending Windows reboot must not block UnifiedVPN installation'
    }
    foreach ($action in @('UnifiedVpnRequireClosed')) {
        $row = $customActions[$action]
        if ($null -eq $row -or [int]$row[1] -ne 19 -or $row[2] -ne '' -or [string]::IsNullOrWhiteSpace($row[3])) {
            throw "MSI preflight must stop with an actionable error: $action"
        }
    }
    if ($customActions['UnifiedVpnRequireClosed'][3] -notlike '*Close UnifiedVPN*') {
        throw 'MSI preflight messages must explain how to retry safely'
    }
    $detectAction = $customActions['WixCloseApplications']
    if ($null -eq $detectAction -or [int]$detectAction[1] -ne 1 -or
        [string]::IsNullOrWhiteSpace($detectAction[2]) -or $detectAction[3] -cne 'WixCloseApplications') {
        throw 'MSI running-application detection must be immediate and checked'
    }
    if ($CloseApplicationRows.Count -ne 1) { throw 'Expected one non-destructive MSI application detector' }
    $detector = $CloseApplicationRows[0]
    if ($detector[0] -cne 'UnifiedVpnDetectRunningApp' -or $detector[1] -cne "$ApplicationName.exe" -or
        $detector[2] -cne $installOrRepair -or [int]$detector[3] -ne 0 -or
        $detector[4] -cne 'UNIFIEDVPN_APP_RUNNING' -or $detector[5] -ne '' -or
        ($detector[6] -ne '' -and [int]$detector[6] -ne 0)) {
        throw 'MSI application guard must only detect: no close, terminate, prompt-to-ignore, or reboot actions'
    }
}

$identity = @('UnifiedVPN.WindowsInstaller.ProductCode.v1', $ExpectedVendor, $ExpectedName,
    $ExpectedVersion, $ExpectedBuild.ToString([Globalization.CultureInfo]::InvariantCulture))
foreach ($part in $identity) {
    if ($part.Contains([string][char]0)) { throw 'Invalid installer identity' }
}
$md5 = [Security.Cryptography.MD5]::Create()
try { $uuid = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes(($identity -join [char]0))) }
finally { $md5.Dispose() }
$uuid[6] = ($uuid[6] -band 0x0f) -bor 0x30
$uuid[8] = ($uuid[8] -band 0x3f) -bor 0x80
$hex = ([BitConverter]::ToString($uuid)).Replace('-', '')
$productCode = [guid]::ParseExact($hex, 'N')

$source = (Get-Item -LiteralPath $InstallerPath).FullName
$temporaryMsi = $null
$installer = $null
$database = $null
try {
    switch ([IO.Path]::GetExtension($source).ToLowerInvariant()) {
        '.exe' {
            $temporaryMsi = [IO.Path]::Combine([IO.Path]::GetTempPath(), 'unifiedvpn-msi-audit-' + [guid]::NewGuid().ToString('N') + '.msi')
            [UnifiedVpnMsiPayload]::Extract($source, $temporaryMsi)
            $msi = $temporaryMsi
        }
        '.msi' { $msi = $source }
        default { throw 'Expected a Windows EXE or MSI installer' }
    }
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($msi, 0)
    $properties = @{}
    foreach ($row in (Read-MsiRows $database 'SELECT `Property`, `Value` FROM `Property`' 2)) {
        if ($properties.ContainsKey($row[0])) { throw 'Duplicate MSI property' }
        $properties[$row[0]] = $row[1]
    }
    if ([guid]$properties['ProductCode'] -ne $productCode) { throw 'MSI ProductCode does not match version/build identity' }
    if ([guid]$properties['UpgradeCode'] -ne $ExpectedUpgradeCode) { throw 'MSI UpgradeCode changed' }
    if ($properties['ProductVersion'] -cne $ExpectedVersion) { throw 'MSI ProductVersion does not match' }
    if ($properties['ProductName'] -cne $ExpectedName -or $properties['Manufacturer'] -cne $ExpectedVendor) {
        throw 'MSI application identity does not match'
    }
    if ($properties['REBOOT'] -cne 'ReallySuppress') { throw 'MSI must suppress automatic Windows reboots' }

    $ranges = Read-MsiRows $database 'SELECT `UpgradeCode`, `VersionMin`, `VersionMax`, `Attributes`, `ActionProperty` FROM `Upgrade`' 5
    if ($ranges.Count -ne 2) { throw 'Expected exactly two MSI upgrade ranges' }
    $seen = @{}
    foreach ($range in $ranges) {
        if ([guid]$range[0] -ne $ExpectedUpgradeCode) { throw 'MSI upgrade range has a different family' }
        if ($seen.ContainsKey($range[4])) { throw 'Duplicate MSI upgrade action' }
        $seen[$range[4]] = $true
        $attributes = [int]$range[3]
        if (($attributes -band 2) -ne 0 -or ($attributes -band 1) -eq 0) { throw 'MSI must migrate, not only detect, related installations' }
        switch ($range[4]) {
            'JP_UPGRADABLE_FOUND' {
                if ($range[1] -ne '' -or $range[2] -cne $ExpectedVersion -or ($attributes -band 512) -eq 0) {
                    throw 'MSI same-version upgrade range is not inclusive'
                }
            }
            'JP_DOWNGRADABLE_FOUND' {
                if ($range[1] -cne $ExpectedVersion -or $range[2] -ne '' -or ($attributes -band 256) -ne 0) {
                    throw 'MSI downgrade range must exclude the same version'
                }
            }
            default { throw 'Unexpected MSI upgrade action' }
        }
    }
    $sequenceRows = Read-MsiRows $database 'SELECT `Action`, `Condition`, `Sequence` FROM `InstallExecuteSequence`' 3
    $customActionRows = Read-MsiRows $database 'SELECT `Action`, `Type`, `Source`, `Target` FROM `CustomAction`' 4
    $closeApplicationRows = Read-MsiRows $database 'SELECT `WixCloseApplication`, `Target`, `Condition`, `Attributes`, `Property`, `Timeout`, `TerminateExitCode` FROM `WixCloseApplication`' 7
    Assert-WindowsReplacementPolicy $sequenceRows $customActionRows $closeApplicationRows $ExpectedName
    [pscustomobject]@{
        Status = 'PASS'
        Version = $ExpectedVersion
        Build = $ExpectedBuild
        ProductCode = $productCode.ToString('B').ToUpperInvariant()
        UpgradeCode = $ExpectedUpgradeCode.ToString('B').ToUpperInvariant()
        SameVersionUpgrade = $true
        SafeReplacementSequence = $true
        RunningApplicationGuard = 'detect-only'
        PendingFileRebootGuard = $false
        AutomaticRebootSuppressed = $true
        InstallerSha256 = Get-Sha256 $source
        MsiSha256 = Get-Sha256 $msi
    } | ConvertTo-Json -Compress
    if (-not [string]::IsNullOrWhiteSpace($ExportMsiPath)) {
        [IO.File]::Copy($msi, [IO.Path]::GetFullPath($ExportMsiPath), $false)
    }
} finally {
    Release-ComObject $database
    Release-ComObject $installer
    if ($null -ne $temporaryMsi -and [IO.File]::Exists($temporaryMsi)) {
        [IO.File]::Delete($temporaryMsi)
    }
}
