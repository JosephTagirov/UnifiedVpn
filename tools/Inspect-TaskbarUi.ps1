$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class UnifiedVpnTaskbarInspect {
    public delegate bool EnumWindowsProc(IntPtr handle, IntPtr parameter);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindow(string className, string windowName);

    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetClassName(IntPtr handle, System.Text.StringBuilder className, int maxCount);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr handle);

    [DllImport("user32.dll")]
    public static extern void keybd_event(byte virtualKey, byte scanCode, uint flags, UIntPtr extraInfo);
}
"@

[UnifiedVpnTaskbarInspect]::keybd_event(0x5B, 0, 0, [UIntPtr]::Zero)
[UnifiedVpnTaskbarInspect]::keybd_event(0x42, 0, 0, [UIntPtr]::Zero)
[UnifiedVpnTaskbarInspect]::keybd_event(0x42, 0, 2, [UIntPtr]::Zero)
[UnifiedVpnTaskbarInspect]::keybd_event(0x5B, 0, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 500

function Read-AutomationElements([IntPtr]$Handle, [string]$Scope) {
    if ($Handle -eq [IntPtr]::Zero) { return }
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($Handle)
    $elements = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    foreach ($element in $elements) {
        try {
            $name = $element.Current.Name
            $type = $element.Current.ControlType.ProgrammaticName
            $className = $element.Current.ClassName
            $automationId = $element.Current.AutomationId
            $isRelevant =
                $name -match "Unified VPN|UnifiedVPN|Hidden Icons|hidden icons|скрытые значки" -or
                $className -match "Overflow|Tray|Notify|ControlCenter" -or
                $automationId -match "Tray|Notify|Overflow"
            if ($isRelevant) {
                [PSCustomObject]@{
                    scope = $Scope
                    name = $name
                    type = $type
                    className = $className
                    automationId = $automationId
                    rectangle = $element.Current.BoundingRectangle.ToString()
                }
            }
        }
        catch { }
    }
}

$taskbar = [UnifiedVpnTaskbarInspect]::FindWindow("Shell_TrayWnd", $null)
Read-AutomationElements -Handle $taskbar -Scope "taskbar"
$taskbarRoot = [System.Windows.Automation.AutomationElement]::FromHandle($taskbar)
$showHidden = $taskbarRoot.FindFirst(
    [System.Windows.Automation.TreeScope]::Descendants,
    [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::NameProperty,
        "Show Hidden Icons"
    )
)
if ($null -ne $showHidden) {
    $invoke = $showHidden.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    $invoke.Invoke()
    Start-Sleep -Milliseconds 700
}

$callback = [UnifiedVpnTaskbarInspect+EnumWindowsProc] {
    param([IntPtr]$handle, [IntPtr]$parameter)
    if ([UnifiedVpnTaskbarInspect]::IsWindowVisible($handle)) {
        $builder = [System.Text.StringBuilder]::new(256)
        $null = [UnifiedVpnTaskbarInspect]::GetClassName($handle, $builder, $builder.Capacity)
        $className = $builder.ToString()
        $automationName = ""
        try {
            $automationName = [System.Windows.Automation.AutomationElement]::FromHandle($handle).Current.Name
        }
        catch { }
        if ($className -match "Overflow|Xaml|Tray|Notify|ControlCenter" -or
            $automationName -match "Unified VPN|Hidden Icons|hidden icons|скрытые значки") {
            [PSCustomObject]@{
                scope = "top-level"
                name = $automationName
                type = "window"
                className = $className
                automationId = ""
                rectangle = ""
            }
            Read-AutomationElements -Handle $handle -Scope $className
        }
    }
    return $true
}
$null = [UnifiedVpnTaskbarInspect]::EnumWindows($callback, [IntPtr]::Zero)
