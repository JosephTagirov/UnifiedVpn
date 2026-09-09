[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InstallerPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$ExpectedVersion,
    [Parameter(Mandatory = $true)][ValidateRange(1, [long]::MaxValue)][long]$ExpectedBuild,
    [string]$ExpectedName = 'UnifiedVPN',
    [string]$ExpectedVendor = 'Unknown',
    [guid]$ExpectedUpgradeCode = '6f0aaf78-dbed-4745-9d95-9e63f10a30de'
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
    $sequence = @{}
    foreach ($row in (Read-MsiRows $database 'SELECT `Action`, `Sequence` FROM `InstallExecuteSequence`' 2)) {
        $sequence[$row[0]] = [int]$row[1]
    }
    if (-not $sequence.ContainsKey('RemoveExistingProducts') -or -not $sequence.ContainsKey('CostInitialize') -or
        $sequence['RemoveExistingProducts'] -ge $sequence['CostInitialize']) {
        throw 'MSI replacement sequence changed'
    }
    [pscustomobject]@{
        Status = 'PASS'
        Version = $ExpectedVersion
        Build = $ExpectedBuild
        ProductCode = $productCode.ToString('B').ToUpperInvariant()
        UpgradeCode = $ExpectedUpgradeCode.ToString('B').ToUpperInvariant()
        SameVersionUpgrade = $true
        InstallerSha256 = Get-Sha256 $source
        MsiSha256 = Get-Sha256 $msi
    } | ConvertTo-Json -Compress
} finally {
    Release-ComObject $database
    Release-ComObject $installer
    if ($null -ne $temporaryMsi -and [IO.File]::Exists($temporaryMsi)) {
        [IO.File]::Delete($temporaryMsi)
    }
}
