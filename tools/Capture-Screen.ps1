param(
    [Parameter(Mandatory = $true)]
    [string]$Output
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System.Runtime.InteropServices;
public static class UnifiedVpnScreenCapture {
    [DllImport("user32.dll")]
    public static extern bool SetProcessDPIAware();
}
"@
$null = [UnifiedVpnScreenCapture]::SetProcessDPIAware()
$bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bitmap = [System.Drawing.Bitmap]::new($bounds.Width, $bounds.Height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, $bitmap.Size)
    $outputPath = [System.IO.Path]::GetFullPath($Output)
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force | Out-Null
    $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $outputPath
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}
