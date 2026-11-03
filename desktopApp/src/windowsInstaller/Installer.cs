using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

internal static class NativeMsi {
    [DllImport("msi.dll", CharSet=CharSet.Unicode)] internal static extern uint MsiEnumRelatedProducts(string upgrade, uint reserved, uint index, StringBuilder code);
    [DllImport("msi.dll", CharSet=CharSet.Unicode)] internal static extern uint MsiGetProductInfo(string code, string property, StringBuilder value, ref uint length);
    internal static string Info(string code, string key) {
        uint size=32767; var value=new StringBuilder((int)size);
        return MsiGetProductInfo(code,key,value,ref size)==0 ? value.ToString() : "";
    }
    internal static List<InstalledProduct> Installed() {
        var result=new List<InstalledProduct>();
        for(uint i=0;i<128;i++) {
            var code=new StringBuilder(39);
            var status=MsiEnumRelatedProducts(SetupProduct.UpgradeCode,0,i,code);
            if(status==259) return result;
            if(status!=0) throw new InvalidOperationException("Не удалось проверить установленную версию ("+status+").");
            result.Add(new InstalledProduct {Code=code.ToString(),Version=Info(code.ToString(),"VersionString"),Root=Info(code.ToString(),"InstallLocation")});
        }
        throw new InvalidOperationException("Слишком много записей установки UnifiedVPN.");
    }
}

internal static class MsiPayload {
    internal static void Extract(string input,string output) {
        using(var stream=File.OpenRead(input)) {
            if(stream.Length<568) throw new InvalidDataException("Повреждён установщик.");
            stream.Position=stream.Length-56;
            var footer=new byte[56]; if(stream.Read(footer,0,56)!=56) throw new EndOfStreamException();
            if(Encoding.ASCII.GetString(footer,0,16)!="UVPNMSIPAYLOAD01") throw new InvalidDataException("Нет пакета установки.");
            long size=BitConverter.ToInt64(footer,16);
            if(size<512 || size>1024L*1024*1024 || size>stream.Length-568) throw new InvalidDataException("Неверный размер пакета.");
            stream.Position=stream.Length-56-size;
            using(var sha=SHA256.Create()) using(var target=new FileStream(output,FileMode.CreateNew,FileAccess.Write,FileShare.None)) {
                var buffer=new byte[1024*1024]; long remaining=size;
                while(remaining>0) {
                    int read=stream.Read(buffer,0,(int)Math.Min(buffer.Length,remaining));
                    if(read==0) throw new EndOfStreamException();
                    sha.TransformBlock(buffer,0,read,null,0); target.Write(buffer,0,read); remaining-=read;
                }
                sha.TransformFinalBlock(new byte[0],0,0);
                if(!sha.Hash.SequenceEqual(footer.Skip(24))) throw new InvalidDataException("Контрольная сумма пакета не совпадает. Скачайте установщик заново.");
            }
        }
    }
}

internal sealed class SetupWindow : Form {
    private readonly Label heading=new Label(), detail=new Label(), status=new Label();
    private readonly Button primary=new Button(), cancel=new Button();
    private readonly ProgressBar progress=new ProgressBar();
    private List<InstalledProduct> products;
    private SetupAction action;
    private bool busy, complete;
    private string launchRoot, logPath;
    internal SetupWindow(bool preview=false) {
        SuspendLayout();
        Text="UnifiedVPN — установка и обновление"; ClientSize=new Size(780,484);
        Icon=Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        FormBorderStyle=FormBorderStyle.FixedSingle; MaximizeBox=false;
        StartPosition=FormStartPosition.CenterScreen; BackColor=ColorTranslator.FromHtml("#F4F5EF");
        Font=new Font("Segoe UI",10);
        var sidebar=new Panel {Bounds=new Rectangle(0,0,230,484),BackColor=ColorTranslator.FromHtml("#203B2C")};
        var brand=new Label {Text="UnifiedVPN",ForeColor=Color.White,Font=new Font("Segoe UI Semibold",25),Bounds=new Rectangle(24,54,205,54)};
        sidebar.Controls.Add(brand);
        sidebar.Controls.Add(new Label {Text="Ваше соединение.\nВаши правила.",ForeColor=ColorTranslator.FromHtml("#C3D4AF"),Bounds=new Rectangle(28,118,184,62),Font=new Font("Segoe UI",12)});
        sidebar.Controls.Add(new Label {Text="Версия "+SetupProduct.Version+"\nСборка "+SetupProduct.Build+"\n"+SetupProduct.Edition,ForeColor=ColorTranslator.FromHtml("#C3D4AF"),Bounds=new Rectangle(28,385,190,74),Font=new Font("Segoe UI",9)});
        Controls.Add(sidebar);
        heading.Bounds=new Rectangle(265,48,478,76); heading.Font=new Font("Segoe UI Semibold",20); heading.ForeColor=ColorTranslator.FromHtml("#21382B");
        detail.Bounds=new Rectangle(267,133,468,76); detail.ForeColor=ColorTranslator.FromHtml("#64735C");
        var card=new Panel {Bounds=new Rectangle(265,225,478,90),BackColor=Color.White};
        card.Controls.Add(new Label {Text="Настройки и подписки сохранятся",Bounds=new Rectangle(18,16,442,28),Font=new Font("Segoe UI Semibold",11),ForeColor=ColorTranslator.FromHtml("#21382B")});
        card.Controls.Add(new Label {Text="Можно подключаться сразу после установки.",Bounds=new Rectangle(18,48,442,25),ForeColor=ColorTranslator.FromHtml("#64735C"),Font=new Font("Segoe UI",9)});
        progress.Bounds=new Rectangle(267,333,472,7); progress.Style=ProgressBarStyle.Marquee; progress.Visible=false;
        status.Bounds=new Rectangle(267,350,468,50); status.ForeColor=ColorTranslator.FromHtml("#64735C"); status.Font=new Font("Segoe UI",9);
        primary.Bounds=new Rectangle(486,416,255,42); primary.FlatStyle=FlatStyle.Flat; primary.FlatAppearance.BorderSize=0;
        primary.BackColor=ColorTranslator.FromHtml("#203B2C"); primary.ForeColor=Color.White; primary.Font=new Font("Segoe UI Semibold",11);
        cancel.Bounds=new Rectangle(266,416,198,42); cancel.FlatStyle=FlatStyle.Flat; cancel.FlatAppearance.BorderColor=ColorTranslator.FromHtml("#DCE1D4"); cancel.Text="Отмена";
        cancel.Click+=(s,e)=>Close(); primary.Click+=async(s,e)=>await Apply();
        Controls.AddRange(new Control[]{heading,detail,card,progress,status,primary,cancel});
        FormClosing+=(s,e)=>{if(busy)e.Cancel=true;};
        try {
            products=preview ? new List<InstalledProduct>{new InstalledProduct{Code="preview",Version="0.1.5"}} : NativeMsi.Installed();
            action=InstallerPolicy.Decide(products,SetupProduct.ProductCode,SetupProduct.Version);
            heading.Text=action==SetupAction.Install ? "Установить UnifiedVPN" : action==SetupAction.NewerInstalled ? "Уже есть новая версия" : "Обновить UnifiedVPN";
            detail.Text=action==SetupAction.Install ? "Настроим приложение на этом компьютере. После установки можно сразу подключиться." : action==SetupAction.NewerInstalled ? "На компьютере установлена более новая версия. Она и ваши настройки сохранены." : "Найдена версия "+products.First().Version+". Приложение закроется на время обновления, затем его можно будет открыть снова.";
            primary.Text=action==SetupAction.NewerInstalled ? "Готово" : action==SetupAction.Install ? "Установить" : action==SetupAction.Repair ? "Восстановить эту версию" : "Обновить";
            launchRoot=products.Select(p=>p.Root).FirstOrDefault(r=>!String.IsNullOrWhiteSpace(r));
        } catch(Exception e) { heading.Text="Не удалось проверить версию"; status.Text=e.Message; primary.Enabled=false; }
        AutoScaleDimensions=new SizeF(96F,96F); AutoScaleMode=AutoScaleMode.Dpi;
        ResumeLayout(true);
    }
    private async Task Apply() {
        if(complete) { try {Launch();Close();} catch(Exception e) {status.Text=e.Message;} return; }
        if(action==SetupAction.NewerInstalled) {Close();return;}
        busy=true; primary.Enabled=cancel.Enabled=false; progress.Visible=true;
        status.Text="Подготовка обновления…";
        try {
            await Task.Run(()=>Install());
            complete=true; heading.Text="UnifiedVPN готов"; detail.Text="Версия "+SetupProduct.Version+" установлена. Можно продолжать работу.";
            status.Text="Настройки и подписки сохранены."; primary.Text="Открыть UnifiedVPN"; cancel.Text="Закрыть";
        } catch(Exception e) {status.Text=e.Message; primary.Text="Повторить";}
        finally { busy=false; primary.Enabled=cancel.Enabled=true; progress.Visible=false; }
    }
    private void Status(string message) { BeginInvoke((Action)(()=>status.Text=message)); }
    private void Install() {
        var temp=Path.Combine(Path.GetTempPath(),"UnifiedVPN-setup-"+Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temp);
        logPath=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"UnifiedVPN","Setup","setup-"+DateTime.UtcNow.ToString("yyyyMMdd-HHmmss")+".log");
        Directory.CreateDirectory(Path.GetDirectoryName(logPath));
        try {
            var msi=Path.Combine(temp,"UnifiedVPN.msi"); MsiPayload.Extract(Application.ExecutablePath,msi);
            Status("Закрываем UnifiedVPN и освобождаем файлы…");
            CloseApplication(); AssertFilesAvailable();
            Status(action==SetupAction.Install ? "Установка приложения…" : "Установка новой версии…");
            var start=new ProcessStartInfo(Path.Combine(Environment.SystemDirectory,"msiexec.exe"),InstallerPolicy.Arguments(msi,logPath,action,launchRoot)) {UseShellExecute=true,Verb="runas",WindowStyle=ProcessWindowStyle.Hidden};
            int code;
            using(var process=Process.Start(start)) { process.WaitForExit(); code=process.ExitCode; }
            if(code==3010) throw new InvalidOperationException("Windows отложила замену занятого файла. Автоматическая перезагрузка отключена. Журнал: "+logPath);
            if(code!=0) throw new InvalidOperationException(code==1223 || code==1602 ? "Установка отменена. Можно повторить." : "Не удалось завершить установку ("+code+"). Журнал: "+logPath);
            var installed=NativeMsi.Installed().FirstOrDefault(p=>String.Equals(p.Code,SetupProduct.ProductCode,StringComparison.OrdinalIgnoreCase));
            if(installed==null) throw new InvalidOperationException("Windows не подтвердила установку. Журнал: "+logPath);
            launchRoot=installed.Root;
        } finally {
            // Only this random, locally created directory is removed. No application data is touched.
            try { Directory.Delete(temp,true); } catch(IOException) {} catch(UnauthorizedAccessException) {}
        }
    }
    private void CloseApplication() {
        foreach(var process in Process.GetProcessesByName("UnifiedVPN")) using(process) {
            string path;
            try {path=process.MainModule.FileName;} catch(Exception) { throw new InvalidOperationException("Закройте UnifiedVPN из трея, затем нажмите «Повторить».");}
            // Match the registered installation, never other VPN applications or similarly named programs.
            if(!products.Any(p=>!String.IsNullOrWhiteSpace(p.Root) && String.Equals(Path.GetFullPath(path),Path.Combine(Path.GetFullPath(p.Root),"UnifiedVPN.exe"),StringComparison.OrdinalIgnoreCase))) continue;
            process.CloseMainWindow();
            int pid=process.Id;
            EnumWindows((window,data)=> {uint owner;GetWindowThreadProcessId(window,out owner);if(owner==pid)PostMessage(window,0x0010,IntPtr.Zero,IntPtr.Zero);return true;},IntPtr.Zero);
            if(!process.WaitForExit(20000)) throw new InvalidOperationException("UnifiedVPN ещё закрывается. Завершите его из трея и нажмите «Повторить».");
        }
    }
    private void AssertFilesAvailable() {
        foreach(var root in products.Select(p=>p.Root).Where(r=>!String.IsNullOrWhiteSpace(r)).Distinct(StringComparer.OrdinalIgnoreCase)) {
            if(!Directory.Exists(root)) continue;
            if((File.GetAttributes(root)&FileAttributes.ReparsePoint)!=0) throw new IOException("Каталог приложения является ссылкой. Обновление остановлено.");
            CheckDirectory(root);
        }
    }
    private static void CheckDirectory(string root) {
        foreach(var path in Directory.EnumerateFileSystemEntries(root)) {
            var attributes=File.GetAttributes(path);
            if((attributes&FileAttributes.ReparsePoint)!=0) throw new IOException("В каталоге приложения обнаружена ссылка. Обновление остановлено.");
            if((attributes&FileAttributes.Directory)!=0) {CheckDirectory(path);continue;}
            try {using(File.Open(path,FileMode.Open,FileAccess.Read,FileShare.None)) {}}
            catch(IOException) {throw new IOException("Файл UnifiedVPN занят. Закройте приложение из трея и нажмите «Повторить».");}
        }
    }
    private void Launch() {
        if(String.IsNullOrWhiteSpace(launchRoot)) return;
        var app=Path.Combine(launchRoot,"UnifiedVPN.exe");
        if(File.Exists(app)) Process.Start(new ProcessStartInfo(app){UseShellExecute=true,WorkingDirectory=launchRoot});
    }
    private delegate bool EnumWindow(IntPtr window,IntPtr data);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindow callback,IntPtr data);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr window,uint message,IntPtr wParam,IntPtr lParam);
}

internal static class Program {
    [STAThread] private static int Main(string[] args) {
        if(args.Length==1 && args[0]=="--self-test") {InstallerPolicy.SelfTest();return 0;}
        Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
        if(args.Length==2 && args[0]=="--render-preview") {
            using(var window=new SetupWindow(true)) {window.Show();Application.DoEvents();using(var bitmap=new Bitmap(window.Width,window.Height)){window.DrawToBitmap(bitmap,new Rectangle(0,0,window.Width,window.Height));bitmap.Save(args[1]);}}
            return 0;
        }
        if(args.Length!=0) return 2;
        using(var window=new SetupWindow()) Application.Run(window);
        return 0;
    }
}
