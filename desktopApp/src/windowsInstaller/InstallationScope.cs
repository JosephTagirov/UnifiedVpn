using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

internal static class InstallationScope {
    [DllImport("kernel32.dll", SetLastError=true)] private static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] private static extern bool QueryFullProcessImageName(IntPtr process,uint flags,StringBuilder path,ref uint size);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    [DllImport("msi.dll", CharSet=CharSet.Unicode)] private static extern uint MsiGetProductInfo(string code,string property,StringBuilder value,ref uint length);

    internal static string ProductRoot(string code) {
        uint length=32767;var value=new StringBuilder((int)length);
        return MsiGetProductInfo(code,"InstallLocation",value,ref length)==0 ? value.ToString() : "";
    }
    internal static bool Matches(string executable,System.Collections.Generic.IEnumerable<string> roots) {
        if(String.IsNullOrWhiteSpace(executable)) return false;
        var full=Path.GetFullPath(executable);
        return roots.Where(r=>!String.IsNullOrWhiteSpace(r)).Any(root=>
            String.Equals(full,Path.Combine(Path.GetFullPath(root),"UnifiedVPN.exe"),StringComparison.OrdinalIgnoreCase));
    }
    internal static string Executable(Process process) {
        if(process.HasExited) return null;
        // Query limited information also works for an elevated app owned by this user.
        var handle=OpenProcess(0x1000,false,process.Id);
        if(handle==IntPtr.Zero) {
            if(process.HasExited) return null;
            throw new Win32Exception(Marshal.GetLastWin32Error(),"Не удалось проверить путь запущенного UnifiedVPN. Закройте его из трея и повторите установку.");
        }
        try {
            uint length=32768;var value=new StringBuilder((int)length);
            if(QueryFullProcessImageName(handle,0,value,ref length)) return value.ToString();
            if(process.HasExited) return null;
            throw new Win32Exception(Marshal.GetLastWin32Error(),"Не удалось проверить путь запущенного UnifiedVPN.");
        } finally {CloseHandle(handle);}
    }
    internal static bool HasRunning(System.Collections.Generic.IEnumerable<string> roots) {
        bool found=false;
        foreach(var process in Process.GetProcessesByName("UnifiedVPN")) using(process) {
            if(Matches(Executable(process),roots)) found=true;
        }
        return found;
    }
}
