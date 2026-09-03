param(
    [string]$Tooltip = "Unified VPN",
    [Parameter(Mandatory = $true)]
    [string]$Output
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class UnifiedVpnTrayInput {
    [DllImport("user32.dll")]
    public static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);

    [DllImport("user32.dll")]
    public static extern bool SetProcessDPIAware();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindow(string className, string windowName);

    [DllImport("user32.dll")]
    public static extern void keybd_event(byte virtualKey, byte scanCode, uint flags, UIntPtr extraInfo);
}
"@

$null = [UnifiedVpnTrayInput]::SetProcessDPIAware()

function Send-Key([byte]$VirtualKey) {
    [UnifiedVpnTrayInput]::keybd_event($VirtualKey, 0, 0, [UIntPtr]::Zero)
    [UnifiedVpnTrayInput]::keybd_event($VirtualKey, 0, 2, [UIntPtr]::Zero)
}

function Find-TrayElement([IntPtr]$RootHandle, [string]$NameFragment) {
    if ($RootHandle -eq [IntPtr]::Zero) { return $null }
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($RootHandle)
    $elements = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    foreach ($element in $elements) {
        try {
            $name = $element.Current.Name
            $rect = $element.Current.BoundingRectangle
            $controlType = $element.Current.ControlType
            $isClickableControl =
                $controlType -eq [System.Windows.Automation.ControlType]::Button -or
                $controlType -eq [System.Windows.Automation.ControlType]::Image
            $matchesName = $name -and
                $name.IndexOf($NameFragment, [StringComparison]::OrdinalIgnoreCase) -ge 0
            if ($isClickableControl -and $matchesName -and
                $rect.Width -gt 0 -and $rect.Height -gt 0) {
                return $element
            }
        }
        catch { }
    }
    return $null
}

function Get-FocusedTrayElement([string]$NameFragment) {
    try {
        $element = [System.Windows.Automation.AutomationElement]::FocusedElement
        $name = $element.Current.Name
        $rect = $element.Current.BoundingRectangle
        if ($name -and
            $name.IndexOf($NameFragment, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $rect.Width -gt 0 -and $rect.Height -gt 0) {
            return $element
        }
    }
    catch { }
    return $null
}

function Find-TrayElementByKeyboard([string]$NameFragment) {
    [UnifiedVpnTrayInput]::keybd_event($vkLeftWindows, 0, 0, [UIntPtr]::Zero)
    Send-Key -VirtualKey $vkB
    [UnifiedVpnTrayInput]::keybd_event($vkLeftWindows, 0, 2, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 400

    for ($index = 0; $index -lt 24; $index++) {
        $matched = Get-FocusedTrayElement -NameFragment $NameFragment
        if ($null -ne $matched) { return $matched }

        $focusedName = ""
        try {
            $focusedName = [System.Windows.Automation.AutomationElement]::FocusedElement.Current.Name
        }
        catch { }
        if ($focusedName -match "Show Hidden Icons|Отображать скрытые значки|Hidden icons") {
            Send-Key -VirtualKey 0x0D
            Start-Sleep -Milliseconds 500
            for ($overflowIndex = 0; $overflowIndex -lt 60; $overflowIndex++) {
                $matched = Get-FocusedTrayElement -NameFragment $NameFragment
                if ($null -ne $matched) { return $matched }
                Send-Key -VirtualKey 0x27
            }
            return $null
        }
        Send-Key -VirtualKey 0x27
    }
    return $null
}

$vkLeftWindows = 0x5B
$vkB = 0x42
[UnifiedVpnTrayInput]::keybd_event($vkLeftWindows, 0, 0, [UIntPtr]::Zero)
Send-Key -VirtualKey $vkB
[UnifiedVpnTrayInput]::keybd_event($vkLeftWindows, 0, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 500

$taskbarHandle = [UnifiedVpnTrayInput]::FindWindow("Shell_TrayWnd", $null)
$element = Find-TrayElement -RootHandle $taskbarHandle -NameFragment $Tooltip
if ($null -eq $element) {
    $overflowNames = @("Show hidden icons", "Отображать скрытые значки", "Hidden icons")
    foreach ($name in $overflowNames) {
        $overflow = Find-TrayElement -RootHandle $taskbarHandle -NameFragment $name
        if ($null -ne $overflow) {
            $pattern = $overflow.GetCurrentPattern(
                [System.Windows.Automation.InvokePattern]::Pattern
            )
            $pattern.Invoke()
            Start-Sleep -Milliseconds 700
            break
        }
    }
    if ($null -eq $overflow) {
        Send-Key -VirtualKey 0x0D
        Start-Sleep -Milliseconds 700
    }

    $overflowHandles = @(
        [UnifiedVpnTrayInput]::FindWindow("NotifyIconOverflowWindow", $null),
        [UnifiedVpnTrayInput]::FindWindow("TopLevelWindowForOverflowXamlIsland", $null)
    )
    foreach ($handle in $overflowHandles) {
        $element = Find-TrayElement -RootHandle $handle -NameFragment $Tooltip
        if ($null -ne $element) { break }
    }
}

if ($null -eq $element) {
    $element = Find-TrayElementByKeyboard -NameFragment $Tooltip
}
if ($null -eq $element) { throw "Tray element '$Tooltip' was not found" }
$rect = $element.Current.BoundingRectangle
$x = [int]($rect.Left + ($rect.Width / 2))
$y = [int]($rect.Top + ($rect.Height / 2))
$null = [UnifiedVpnTrayInput]::SetCursorPos($x, $y)
[UnifiedVpnTrayInput]::mouse_event(0x0008, 0, 0, 0, [UIntPtr]::Zero)
[UnifiedVpnTrayInput]::mouse_event(0x0010, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 1200

$screen = [System.Windows.Forms.Screen]::FromPoint([System.Drawing.Point]::new($x, $y)).Bounds
$captureLeft = [Math]::Max($screen.Left, $x - 420)
$captureTop = [Math]::Max($screen.Top, $y - 460)
$captureRight = [Math]::Min($screen.Right, $x + 420)
$captureBottom = [Math]::Min($screen.Bottom, $y + 80)
$captureWidth = $captureRight - $captureLeft
$captureHeight = $captureBottom - $captureTop
$bitmap = [System.Drawing.Bitmap]::new($captureWidth, $captureHeight)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CopyFromScreen($captureLeft, $captureTop, 0, 0, $bitmap.Size)
    $outputPath = [System.IO.Path]::GetFullPath($Output)
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force | Out-Null
    $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    [PSCustomObject]@{
        output = $outputPath
        elementName = $element.Current.Name
        x = $x
        y = $y
        width = $rect.Width
        height = $rect.Height
    } | ConvertTo-Json -Compress
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}
