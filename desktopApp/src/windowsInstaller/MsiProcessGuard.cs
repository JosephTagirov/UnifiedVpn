using System;
using System.Collections.Generic;
using Microsoft.Deployment.WindowsInstaller;

public static class MsiProcessGuard {
    [CustomAction]
    public static ActionResult UnifiedVpnDetectInstalledApp(Session session) {
        try {
            // The execute sequence runs after costing, so INSTALLDIR is resolved.
            // Include old registered locations when an upgrade changes the target path.
            var roots=new List<string>{session["INSTALLDIR"]};
            var codes=(session["JP_UPGRADABLE_FOUND"]+";"+session["ProductCode"]).Split(';');
            foreach(var code in codes) if(!String.IsNullOrWhiteSpace(code)) roots.Add(InstallationScope.ProductRoot(code));
            session["UNIFIEDVPN_APP_RUNNING"]=InstallationScope.HasRunning(roots) ? "1" : "";
            session.Log("UnifiedVPN scoped preflight completed; installed application running: {0}",session["UNIFIEDVPN_APP_RUNNING"]=="1");
            return ActionResult.Success;
        } catch(Exception error) {
            session.Log("UnifiedVPN scoped preflight failed: {0}",error.Message);
            using(var record=new Record(0)) {
                record.FormatString="Could not check the installed UnifiedVPN process. Close that copy from its tray icon and retry setup.";
                session.Message(InstallMessage.Error,record);
            }
            return ActionResult.Failure;
        }
    }
}
