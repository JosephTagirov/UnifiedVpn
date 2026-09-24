using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class BrowserHelper
{
    internal const int MaximumMessageBytes = 32768;
    internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer {
        MaxJsonLength = MaximumMessageBytes, RecursionLimit = 12
    };
    // Volga readiness permits cookie bootstrap only; native authorization still checks write access.
    internal const string BootstrapReadyScript = @"() => {
      try {
        const nodes = document.querySelectorAll('script#client-config');
        if (nodes.length !== 1 || !nodes[0].textContent || nodes[0].textContent.length > 2097152) return false;
        const root = JSON.parse(nodes[0].textContent);
        const office = root && root.officeActionData;
        const editor = office && office.editor_config;
        const doc = editor && editor.document;
        const nonempty = value => typeof value === 'string' && value.length > 0;
        if (!office || !editor) return false;
        if (office.office_online_editor_type !== 'volga') {
          return !!(doc && nonempty(office.balancer_url) && nonempty(editor.token) &&
            nonempty(doc.key) && doc.permissions && doc.permissions.edit === true);
        }
        const denied = rights => Array.isArray(rights) && !rights.includes('write');
        if (denied(root.rights) || denied(root.editorParams && root.editorParams.rights) ||
            (doc && doc.permissions && doc.permissions.edit === false)) return false;
        if (!nonempty(office.access_token) || editor.documentType !== 'text' ||
            !nonempty(office.action_url) || office.action_url.length > 8192 ||
            /[\u0000-\u0020\u007f]/.test(office.action_url)) return false;
        const action = new URL(office.action_url);
        return action.protocol === 'https:' && action.hostname === 'volga.yandex.ru' &&
          (action.port === '' || action.port === '443') && action.username === '' &&
          action.password === '' && action.hash === '';
      } catch (_) { return false; }
    }";
    private static IntPtr ownedJob;

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 1 && args[0] == "--self-test") return SelfTest();
        bool offline = args.Length == 1 && args[0] == "--self-test-browser";
        if (args.Length != 0 && !offline) return 2;
        Console.InputEncoding = new UTF8Encoding(false, true);
        Console.OutputEncoding = new UTF8Encoding(false, true);
        string id = null;
        try {
            var requestTask = Task.Run(() => ReadRequest());
            if (!requestTask.Wait(TimeSpan.FromSeconds(10))) return 2;
            var request = requestTask.Result;
            id = Text(request, "id");
            if (!ValidId(id) || request.Count != 3) return 2;
            var url = DocumentUri(Text(request, "document_url"));
            var directory = OwnedDirectory(Text(request, "user_data_dir"));
            if (url == null || directory == null) throw new InvalidOperationException();
            if (new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator))
                throw new InvalidOperationException();
            // No environment-supplied browser flags, user profile, SSO, or CDP endpoint.
            foreach (System.Collections.DictionaryEntry entry in Environment.GetEnvironmentVariables()) {
                string name = (string)entry.Key;
                if (name.StartsWith("WEBVIEW2_", StringComparison.OrdinalIgnoreCase))
                    Environment.SetEnvironmentVariable(name, null);
            }
            CreateOwnedJob();
            CreatePrivateDirectory(directory);
            using (var deadline = new CancellationTokenSource()) {
                Task.Run(async () => {
                    try { await Task.Delay(TimeSpan.FromSeconds(48), deadline.Token); }
                    catch (OperationCanceledException) { return; }
                    Environment.Exit(24);
                });
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                using (var form = new VerificationForm(id, url, directory, offline)) {
                    Application.Run(form);
                    deadline.Cancel();
                    Console.WriteLine(Json.Serialize(form.Response ?? Failure(id)));
                    return form.ExitCode;
                }
            }
        } catch (Exception error) {
            if (offline) Console.Error.WriteLine("OFFLINE_WEBVIEW2_SMOKE failure=" + error.GetType().Name + " hresult=" + error.HResult);
            if (ValidId(id)) Console.WriteLine(Json.Serialize(Failure(id)));
            return 22;
        }
    }

    private static Dictionary<string, object> ReadRequest()
    {
        var text = new StringBuilder();
        while (text.Length <= MaximumMessageBytes) {
            int value = Console.In.Read();
            if (value < 0) throw new InvalidDataException();
            if (value == '\n') return Json.Deserialize<Dictionary<string, object>>(text.ToString());
            if (value != '\r') text.Append((char)value);
        }
        throw new InvalidDataException();
    }

    internal static Dictionary<string, object> Failure(string id)
    {
        return new Dictionary<string, object> { { "id", id }, { "error", "verification_failed" } };
    }

    private static string Text(Dictionary<string, object> value, string key)
    {
        object result;
        return value.TryGetValue(key, out result) ? result as string : null;
    }

    internal static bool ValidId(string value)
    {
        return value != null && Regex.IsMatch(value, "\\A[0-9a-f]{32}\\z");
    }

    internal static Uri DocumentUri(string value)
    {
        Uri uri;
        if (value == null || value.Length > 8192 || value.Any(Char.IsWhiteSpace) ||
            !Uri.TryCreate(value, UriKind.Absolute, out uri)) return null;
        return uri.Scheme == "https" && uri.Port == 443 && uri.UserInfo.Length == 0 &&
            uri.Fragment.Length == 0 && uri.AbsolutePath.Length > 1 &&
            (uri.Host == "docs.yandex.ru" || uri.Host == "disk.yandex.ru") ? uri : null;
    }

    internal static bool NavigationAllowed(string value)
    {
        Uri uri;
        if (!Uri.TryCreate(value, UriKind.Absolute, out uri) || uri.Scheme != "https" ||
            uri.Port != 443 || uri.UserInfo.Length != 0) return false;
        return uri.Host == "docs.yandex.ru" || uri.Host == "disk.yandex.ru" ||
            uri.Host == "yandex.ru" || uri.Host == "captcha.yandex.ru" ||
            uri.Host == "smartcaptcha.yandexcloud.net";
    }

    internal static bool IsAccountCookie(string name)
    {
        return new[] { "session_id", "sessionid2", "yandex_login" }
            .Contains(name, StringComparer.OrdinalIgnoreCase);
    }

    internal static bool ResourceAllowed(string value)
    {
        Uri uri;
        if (value == null || value.Length > 8192 || value.Any(c => Char.IsWhiteSpace(c) || Char.IsControl(c)) ||
            !Uri.TryCreate(value, UriKind.Absolute, out uri) || uri.Scheme != "https" ||
            uri.Port != 443 || uri.UserInfo.Length != 0) return false;
        string host = uri.Host.ToLowerInvariant();
        if (host.Split('.').Any(label => label == "passport" || label == "oauth" || label == "auth"))
            return false;
        return new[] { "yandex.ru", "yandex.net", "yastatic.net", "yandexcloud.net" }
            .Any(root => host == root || host.EndsWith("." + root, StringComparison.Ordinal));
    }

    internal static bool CookieAllowed(string name, string value, string domain, string path,
        long expires, string documentHost)
    {
        if (String.IsNullOrEmpty(name) || name.Length > 128 || value == null ||
            Encoding.UTF8.GetByteCount(value) > 4096 || IsAccountCookie(name) ||
            name.Any(c => c <= 32 || c >= 127 || "()<>@,;:\\\"/[]?={}".Contains(c)) ||
            value.Any(c => c < 32 || c >= 127 || c == ';' || c == '"' || c == '\\') ||
            path != "/" || domain == null) return false;
        string host = domain.ToLowerInvariant();
        return (host == "" || host == "yandex.ru" || host == ".yandex.ru" ||
            host == documentHost || host == "." + documentHost) &&
            (expires == 0 || expires > DateTimeOffset.UtcNow.ToUnixTimeSeconds());
    }

    private static string OwnedDirectory(string value)
    {
        if (String.IsNullOrWhiteSpace(value)) return null;
        string full = Path.GetFullPath(value);
        string root = Path.GetFullPath(Path.GetTempPath()).TrimEnd(Path.DirectorySeparatorChar);
        return String.Equals(Path.GetDirectoryName(full), root, StringComparison.OrdinalIgnoreCase) &&
            Regex.IsMatch(Path.GetFileName(full), "\\Aunifiedvpn-browser-[0-9a-f]{32}\\z") &&
            !Directory.Exists(full) && !File.Exists(full) ? full : null;
    }

    private static void CreatePrivateDirectory(string path)
    {
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false);
        var identity = WindowsIdentity.GetCurrent().User;
        security.AddAccessRule(new FileSystemAccessRule(identity, FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        Directory.CreateDirectory(path, security);
    }

    private static void CreateOwnedJob()
    {
        ownedJob = CreateJobObject(IntPtr.Zero, null);
        if (ownedJob == IntPtr.Zero) throw new InvalidOperationException();
        var limits = new ExtendedLimits();
        limits.Basic.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if (!SetInformationJobObject(ownedJob, 9, ref limits, Marshal.SizeOf(limits)) ||
            !AssignProcessToJobObject(ownedJob, Process.GetCurrentProcess().Handle))
            throw new InvalidOperationException();
        // Kept until process exit: abrupt parent cancellation also kills only this job's browser.
    }

    internal static bool BrowserIsOwned(Process browser)
    {
        bool inJob;
        return IsProcessInJob(browser.Handle, ownedJob, out inJob) && inJob;
    }

    private static int SelfTest()
    {
        string id = new string('a', 32);
        var checks = new[] {
            ValidId(id), !ValidId(id.ToUpperInvariant()), !ValidId(id + "\n"),
            DocumentUri("https://docs.yandex.ru/docs/view?test=1") != null,
            DocumentUri("https://disk.yandex.ru/i/test") != null,
            DocumentUri("http://docs.yandex.ru/x") == null,
            DocumentUri("https://docs.yandex.ru.example/x") == null,
            DocumentUri("https://user@docs.yandex.ru/x") == null,
            DocumentUri("https://docs.yandex.ru:444/x") == null,
            !NavigationAllowed("https://passport.yandex.ru/auth"),
            !NavigationAllowed("file:///test"),
            CookieAllowed("anonymous", "value", ".yandex.ru", "/", 0, "docs.yandex.ru"),
            !CookieAllowed("Session_id", "value", ".yandex.ru", "/", 0, "docs.yandex.ru"),
            !CookieAllowed("anonymous", "value", ".example.ru", "/", 0, "docs.yandex.ru"),
            !CookieAllowed("anonymous", "value\n", ".yandex.ru", "/", 0, "docs.yandex.ru"),
            !CookieAllowed("anonymous", "value", ".yandex.ru", "/", 1, "docs.yandex.ru"),
            !CookieAllowed("anonymous", "value", ".yandex.ru", "/other", 0, "docs.yandex.ru"),
            !CookieAllowed(new string('a', 129), "value", ".yandex.ru", "/", 0, "docs.yandex.ru"),
            !CookieAllowed("anonymous", new string('a', 4097), ".yandex.ru", "/", 0, "docs.yandex.ru"),
            !CookieAllowed("anonymous", "value", "..yandex.ru", "/", 0, "docs.yandex.ru"),
            !CookieAllowed("anonymous", "a\\b", ".yandex.ru", "/", 0, "docs.yandex.ru"),
            ResourceAllowed("https://hdrc.yandex.net/test"),
            ResourceAllowed("https://yastatic.net/test"),
            ResourceAllowed("https://smartcaptcha.yandexcloud.net/test"),
            !ResourceAllowed("https://yandex.ru.example/test"),
            !ResourceAllowed("https://notyandex.ru/test"),
            !ResourceAllowed("https://passport.yandex.ru/auth"),
            !ResourceAllowed("https://oauth.yandex.net/test"),
            !ResourceAllowed("https://auth.yandex.ru/test"),
            !ResourceAllowed("https://127.0.0.1/test"),
            !ResourceAllowed("http://docs.yandex.ru/test"),
            !ResourceAllowed("file:///test"),
            !ResourceAllowed("content://test"),
            !ResourceAllowed("https://docs.yandex.ru:444/test")
        };
        if (checks.Any(value => !value)) return 1;
        Console.WriteLine("Browser helper policy self-test: " + checks.Length + " passed");
        return 0;
    }

    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits {
        internal long ProcessTime, JobTime;
        internal uint LimitFlags;
        internal UIntPtr MinimumWorkingSet, MaximumWorkingSet;
        internal uint ActiveProcessLimit;
        internal UIntPtr Affinity;
        internal uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters {
        internal ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits {
        internal BasicLimits Basic;
        internal IoCounters Io;
        internal UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll")] private static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref ExtendedLimits info, int length);
    [DllImport("kernel32.dll")] private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll")] private static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
}

internal sealed class VerificationForm : Form
{
    private readonly string id;
    private readonly Uri document;
    private readonly string directory;
    private readonly bool offline;
    private readonly CancellationTokenSource cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(45));
    private readonly TaskCompletionSource<bool> browserExited = new TaskCompletionSource<bool>();
    private WebView2 view;
    private Process browser;
    private string stage = "initializing";
    private bool navigationSucceeded;
    private bool navigationCancelled;
    private bool nodePresent;
    private bool finished;
    internal Dictionary<string, object> Response;
    internal int ExitCode = 22;

    internal VerificationForm(string id, Uri document, string directory, bool offline)
    {
        this.id = id;
        this.document = document;
        this.directory = directory;
        this.offline = offline;
        if (offline) cancellation.CancelAfter(TimeSpan.FromSeconds(12));
        Text = "OpenFlux verification";
        ClientSize = new Size(1000, 700);
        ShowInTaskbar = false;
        Opacity = 0;
        FormBorderStyle = FormBorderStyle.FixedToolWindow;
        Shown += async (sender, args) => await VerifyAsync();
        FormClosing += (sender, args) => {
            if (!finished) { args.Cancel = true; cancellation.Cancel(); }
        };
    }

    protected override bool ShowWithoutActivation { get { return true; } }

    private void MonitorParent()
    {
        Task.Run(() => {
            try { Console.In.Read(); } catch (IOException) { }
            cancellation.Cancel();
        });
    }

    private async Task VerifyAsync()
    {
        try {
            MonitorParent();
            var options = new CoreWebView2EnvironmentOptions {
                AdditionalBrowserArguments = "--no-proxy-server" + (offline ?
                    " --disable-background-networking --disable-component-update --disable-domain-reliability --disable-sync --no-first-run" : ""),
                AllowSingleSignOnUsingOSPrimaryAccount = false,
                ExclusiveUserDataFolderAccess = true,
                AreBrowserExtensionsEnabled = false
            };
            var environment = await CoreWebView2Environment.CreateAsync(null, directory, options);
            if (!String.Equals(Path.GetFullPath(environment.UserDataFolder), directory,
                StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException();
            environment.BrowserProcessExited += (sender, args) => browserExited.TrySetResult(true);
            var controller = environment.CreateCoreWebView2ControllerOptions();
            controller.ProfileName = "AnonymousVerification";
            controller.IsInPrivateModeEnabled = true;
            view = new WebView2 { Dock = DockStyle.Fill };
            Controls.Add(view);
            stage = "creating_control";
            await view.EnsureCoreWebView2Async(environment, controller);
            cancellation.Token.ThrowIfCancellationRequested();
            var core = view.CoreWebView2;
            browser = Process.GetProcessById((int)core.BrowserProcessId);
            if (!BrowserHelper.BrowserIsOwned(browser) || !core.Profile.IsInPrivateModeEnabled)
                throw new InvalidOperationException();
            stage = "configuring";
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreBrowserAcceleratorKeysEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsWebMessageEnabled = false;
            core.Settings.AreHostObjectsAllowed = false;
            core.Settings.AreDefaultScriptDialogsEnabled = false;
            core.Profile.IsPasswordAutosaveEnabled = false;
            core.Profile.IsGeneralAutofillEnabled = false;
            core.PermissionRequested += (sender, args) => args.State = CoreWebView2PermissionState.Deny;
            core.NewWindowRequested += (sender, args) => args.Handled = true;
            core.DownloadStarting += (sender, args) => args.Cancel = true;
            core.BasicAuthenticationRequested += (sender, args) => args.Cancel = true;
            core.ClientCertificateRequested += (sender, args) => { args.Cancel = true; args.Handled = true; };
            core.LaunchingExternalUriScheme += (sender, args) => args.Cancel = true;
            core.NavigationStarting += (sender, args) => {
                // NavigateToString uses a runtime-specific synthetic URI; offline resources are denied below.
                if (!offline && !BrowserHelper.NavigationAllowed(args.Uri)) {
                    args.Cancel = true;
                    navigationCancelled = true;
                }
            };
            core.NavigationCompleted += (sender, args) => navigationSucceeded = args.IsSuccess;
            core.ServerCertificateErrorDetected += (sender, args) => args.Action = CoreWebView2ServerCertificateErrorAction.Cancel;
            core.ProcessFailed += (sender, args) => cancellation.Cancel();
            core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All,
                CoreWebView2WebResourceRequestSourceKinds.All);
            core.WebResourceRequested += (sender, args) => {
                if (offline) {
                    Uri requestUri;
                    if (Uri.TryCreate(args.Request.Uri, UriKind.Absolute, out requestUri) &&
                        (requestUri.Scheme == "about" || requestUri.Scheme == "data")) return;
                } else if (BrowserHelper.ResourceAllowed(args.Request.Uri)) return;
                args.Response = environment.CreateWebResourceResponse(null, 403, "Blocked", "");
            };
            if (offline) {
                stage = "loading_synthetic";
                if ((await core.CookieManager.GetCookiesAsync(document.AbsoluteUri)).Count != 0)
                    throw new InvalidOperationException();
                var synthetic = core.CookieManager.CreateCookie("anonymous", "synthetic", ".yandex.ru", "/");
                synthetic.IsSecure = true;
                synthetic.IsHttpOnly = true;
                core.CookieManager.AddOrUpdateCookie(synthetic);
                core.NavigateToString("<!doctype html><html><body><script type='application/json' id='client-config'>" +
                    "{\"officeActionData\":{\"balancer_url\":\"synthetic\",\"editor_config\":{\"token\":\"synthetic\"," +
                    "\"document\":{\"key\":\"synthetic\",\"permissions\":{\"edit\":true}}}}}</script></body></html>");
            } else {
                core.Navigate(document.AbsoluteUri);
            }
            stage = "waiting_document";
            while (true) {
                await Task.Delay(250, cancellation.Token);
                if (!offline && BrowserHelper.DocumentUri(core.Source) == null) continue;
                if (offline) nodePresent = await core.ExecuteScriptAsync("Boolean(document.getElementById('client-config'))") == "true";
                string ready = await core.ExecuteScriptAsync("(" + BrowserHelper.BootstrapReadyScript + ")()");
                if (ready != "true") continue;
                stage = "reading_cookies";
                var values = await core.CookieManager.GetCookiesAsync(document.AbsoluteUri);
                if (values.Any(cookie => BrowserHelper.IsAccountCookie(cookie.Name)))
                    throw new InvalidOperationException();
                var cookies = new List<Dictionary<string, object>>();
                var identities = new HashSet<string>();
                int totalBytes = 0;
                foreach (var cookie in values) {
                    long expires = cookie.IsSession ? 0 : new DateTimeOffset(cookie.Expires.ToUniversalTime()).ToUnixTimeSeconds();
                    if (!BrowserHelper.CookieAllowed(cookie.Name, cookie.Value, cookie.Domain, cookie.Path,
                        expires, document.Host)) continue;
                    string domain = cookie.Domain.ToLowerInvariant();
                    string normalizedDomain = domain == document.Host ? "" : domain;
                    if (!identities.Add(cookie.Name.ToLowerInvariant() + "\0" + normalizedDomain.TrimStart('.')))
                        throw new InvalidOperationException();
                    totalBytes += cookie.Name.Length + cookie.Value.Length + normalizedDomain.Length + 1;
                    if (totalBytes > 24576) throw new InvalidOperationException();
                    cookies.Add(new Dictionary<string, object> {
                        { "name", cookie.Name }, { "value", cookie.Value }, { "domain", cookie.Domain },
                        { "path", "/" }, { "secure", true }, { "expires", expires }
                    });
                }
                var response = new Dictionary<string, object> { { "id", id }, { "cookies", cookies } };
                if (cookies.Count == 0 || cookies.Count > 64 ||
                    Encoding.UTF8.GetByteCount(BrowserHelper.Json.Serialize(response)) > BrowserHelper.MaximumMessageBytes - 2)
                    throw new InvalidOperationException();
                Response = response;
                ExitCode = 0;
                if (offline) Console.Error.WriteLine("OFFLINE_WEBVIEW2_SMOKE in_private=true owned_job=true initial_cookies=0 document_ready=true network_requests_allowed=0");
                break;
            }
        } catch (WebView2RuntimeNotFoundException) {
            ExitCode = 20;
            if (offline) Console.Error.WriteLine("OFFLINE_WEBVIEW2_SMOKE runtime_missing=true");
        } catch (OperationCanceledException) {
            ExitCode = 24;
            if (offline) Console.Error.WriteLine("OFFLINE_WEBVIEW2_SMOKE cancelled=true stage=" + stage +
                " navigation_succeeded=" + navigationSucceeded + " navigation_cancelled=" + navigationCancelled + " node_present=" + nodePresent);
        } catch (Exception error) {
            if (offline) Console.Error.WriteLine("OFFLINE_WEBVIEW2_SMOKE failure=" + error.GetType().Name + " hresult=" + error.HResult + " stage=" + stage);
            ExitCode = 22;
        }
        try {
            if (view != null) {
                try { view.CoreWebView2.Stop(); } catch { }
                view.Dispose();
            }
            if (browser != null) {
                await Task.WhenAny(browserExited.Task, Task.Delay(3000));
                try {
                    if (!browser.HasExited && BrowserHelper.BrowserIsOwned(browser)) browser.Kill();
                    browser.Dispose();
                } catch { }
            }
        } catch {
            ExitCode = 22;
        } finally {
            if (ExitCode != 0) Response = BrowserHelper.Failure(id);
            finished = true;
            Close();
        }
    }
}
