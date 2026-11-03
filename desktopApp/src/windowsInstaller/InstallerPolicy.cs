using System;
using System.Collections.Generic;
using System.Linq;

internal enum SetupAction { Install, Update, Repair, NewerInstalled }
internal sealed class InstalledProduct {
    public string Code, Version, Root;
}
internal static class InstallerPolicy {
    public static SetupAction Decide(IEnumerable<InstalledProduct> products, string code, string version) {
        var items = products.ToArray();
        if (items.Any(p => Parse(p.Version) > Parse(version))) return SetupAction.NewerInstalled;
        if (items.Any(p => String.Equals(p.Code, code, StringComparison.OrdinalIgnoreCase))) return SetupAction.Repair;
        return items.Length == 0 ? SetupAction.Install : SetupAction.Update;
    }
    private static Version Parse(string value) {
        Version parsed;
        return Version.TryParse(value, out parsed) ? parsed : new Version(0, 0, 0);
    }
    public static string Arguments(string msi, string log, SetupAction action, string root) {
        var result = "/i " + Quote(msi) + " /qn /norestart REBOOT=ReallySuppress MSIRESTARTMANAGERCONTROL=Disable /L*v " + Quote(log);
        if (action == SetupAction.Repair) result += " REINSTALL=ALL REINSTALLMODE=vomus";
        if (!String.IsNullOrWhiteSpace(root)) result += " INSTALLDIR=" + Quote(root.TrimEnd('\\'));
        return result;
    }
    private static string Quote(string value) {
        if (String.IsNullOrWhiteSpace(value) || value.IndexOfAny(new[] {'"', '\r', '\n', '\0'}) >= 0)
            throw new ArgumentException("Invalid installer path");
        return "\"" + value + "\"";
    }
    public static string FailureMessage(int code,string log,string path) {
        if(code==1223 || code==1602) return "Установка отменена. Можно повторить.";
        if(code==1603 && log.Contains("UnifiedVpnRequireClosed. Return value 3"))
            return "Запущена установленная копия UnifiedVPN. Закройте её из трея и нажмите «Повторить». Журнал: "+path;
        return "Не удалось завершить установку ("+code+"). Журнал: "+path;
    }
    public static int SelfTest() {
        var old = new InstalledProduct {Code="old", Version="0.1.5"};
        var sameVersion = new InstalledProduct {Code="other-build", Version="0.1.6"};
        var same = new InstalledProduct {Code="current", Version="0.1.6"};
        var newer = new InstalledProduct {Code="newer", Version="0.1.7"};
        Check(Decide(new InstalledProduct[0], "current", "0.1.6") == SetupAction.Install);
        Check(Decide(new[] {old}, "current", "0.1.6") == SetupAction.Update);
        Check(Decide(new[] {sameVersion}, "current", "0.1.6") == SetupAction.Update);
        Check(Decide(new[] {same}, "current", "0.1.6") == SetupAction.Repair);
        Check(Decide(new[] {old,newer}, "current", "0.1.6") == SetupAction.NewerInstalled);
        var args = Arguments("C:\\space path\\app.msi", "C:\\logs\\setup.log", SetupAction.Repair, "C:\\Apps\\UnifiedVPN");
        Check(args.Contains("/qn /norestart REBOOT=ReallySuppress"));
        Check(args.Contains("REINSTALL=ALL REINSTALLMODE=vomus"));
        Check(args.Contains("INSTALLDIR=\"C:\\Apps\\UnifiedVPN\""));
        Check(!Arguments("C:\\app.msi", "C:\\setup.log", SetupAction.Update, null).Contains("REINSTALL="));
        bool rejected=false;
        try { Arguments("bad\"path", "log", SetupAction.Install, null); } catch(ArgumentException) { rejected=true; }
        Check(rejected);
        Check(FailureMessage(1603,"Action ended: UnifiedVpnRequireClosed. Return value 3.","setup.log").Contains("из трея"));
        Check(FailureMessage(1603,"other failure","setup.log").Contains("1603"));
        Check(FailureMessage(1602,"","setup.log").Contains("отменена"));
        return 13;
    }
    private static void Check(bool value) { if (!value) throw new InvalidOperationException("Installer policy self-test failed"); }
}
