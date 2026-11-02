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
    internal const int MaximumClassicDocumentBytes = 24576;
    private static readonly UTF8Encoding StrictUtf8 = new UTF8Encoding(false, true);
    internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer {
        MaxJsonLength = MaximumMessageBytes, RecursionLimit = 12
    };
    // Volga readiness permits cookie bootstrap only; native authorization still checks write access.
    internal const string BootstrapReadyScript = @"(capture = false) => {
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
          const ready = !!(doc && nonempty(office.balancer_url) && nonempty(editor.token) &&
            nonempty(doc.key) && doc.permissions && doc.permissions.edit === true);
          if (!ready || !capture) return ready;
          return {balancer_url: office.balancer_url, token: editor.token,
            document_key: doc.key, file_type: doc.fileType, download_url: doc.url,
            title: doc.title, edit: doc.permissions.edit};
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
        bool interactive = args.Length == 1 && args[0] == "--interactive";
        if (args.Length != 0 && !offline && !interactive) return 2;
        Console.InputEncoding = new UTF8Encoding(false, true);
        Console.OutputEncoding = new UTF8Encoding(false, true);
        string id = null;
        try {
            var requestTask = Task.Run(() => ReadRequest());
            if (!requestTask.Wait(TimeSpan.FromSeconds(10))) return 2;
            var request = requestTask.Result;
            id = Text(request, "id");
            if (!ValidId(id) || request.Count != 3) return 2;
            string sourceUrl = Text(request, "document_url");
            var url = DocumentUri(sourceUrl);
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
                using (var form = new VerificationForm(id, sourceUrl, url, directory, offline, interactive)) {
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
        Uri uri = StrictHttpsUri(value);
        return uri != null && uri.AbsolutePath.Length > 1 &&
            (uri.Host == "docs.yandex.ru" || uri.Host == "disk.yandex.ru") ? uri : null;
    }

    private static int Utf8Length(string value)
    {
        try { return value == null ? -1 : StrictUtf8.GetByteCount(value); }
        catch (EncoderFallbackException) { return -1; }
    }

    private static bool UrlTextAllowed(string value)
    {
        int bytes = Utf8Length(value);
        return bytes >= 1 && bytes <= 8192 && !value.Any(c => Char.IsWhiteSpace(c) || Char.IsControl(c) || c == '\\') &&
            !Regex.IsMatch(value, "%(?![0-9a-fA-F]{2})");
    }

    private static Uri StrictHttpsUri(string value)
    {
        if (!UrlTextAllowed(value)) return null;
        var match = Regex.Match(value, "\\Ahttps://([A-Za-z0-9.-]+)(?::443)?(?:[/?][^#]*)?\\z",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        if (!match.Success) return null;
        string host = match.Groups[1].Value;
        if (host.Length > 253 || host.Split('.').Any(label => !Regex.IsMatch(label,
            "\\A[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\z"))) return null;
        Uri uri;
        return Uri.TryCreate(value, UriKind.Absolute, out uri) && uri.Scheme == "https" &&
            uri.Port == 443 && uri.UserInfo.Length == 0 && uri.Fragment.Length == 0 ? uri : null;
    }

    internal static bool ClassicResourceAllowed(string value, bool originOnly)
    {
        Uri uri = StrictHttpsUri(value);
        return uri != null && (uri.Host.EndsWith(".yandex.ru", StringComparison.Ordinal) ||
            uri.Host.EndsWith(".yandex.net", StringComparison.Ordinal)) &&
            (!originOnly || Regex.IsMatch(value, "\\Ahttps://[A-Za-z0-9.-]+(?::443)?\\z",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant));
    }

    internal static bool ClassicBalancerAllowed(string value)
    {
        if (!UrlTextAllowed(value)) return false;
        var match = Regex.Match(value, "\\Ahttps://([A-Za-z0-9_.-]+)(?::443)?\\z",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        if (!match.Success) return false;
        string host = match.Groups[1].Value;
        return host.Length <= 253 && host.Split('.').All(label => Regex.IsMatch(label,
            "\\A[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?\\z")) &&
            (host.EndsWith(".yandex.ru", StringComparison.OrdinalIgnoreCase) ||
             host.EndsWith(".yandex.net", StringComparison.OrdinalIgnoreCase));
    }

    internal static bool ClassicDocumentReferenceAllowed(string value)
    {
        if (ClassicResourceAllowed(value, false)) return true;
        const string authority = "http://localhost:12701";
        const string prefix = authority + "/disk/";
        if (!UrlTextAllowed(value) || !value.StartsWith(prefix, StringComparison.Ordinal) || value.Contains("#")) return false;
        Uri uri;
        if (!Uri.TryCreate(value, UriKind.Absolute, out uri) || uri.Scheme != "http" ||
            uri.Authority != "localhost:12701" || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0) return false;
        // This reference is forwarded to OnlyOffice, never fetched locally. Inspect the raw
        // path because System.Uri normalizes dot segments before exposing AbsolutePath.
        int query = value.IndexOf('?');
        string rawPath = value.Substring(authority.Length, (query < 0 ? value.Length : query) - authority.Length);
        foreach (Match encoded in Regex.Matches(rawPath, "(?:%[0-9a-fA-F]{2})+")) {
            var bytes = new byte[encoded.Value.Length / 3];
            for (int index = 0; index < bytes.Length; index++)
                bytes[index] = Convert.ToByte(encoded.Value.Substring(index * 3 + 1, 2), 16);
            try { StrictUtf8.GetString(bytes); }
            catch (DecoderFallbackException) { return false; }
        }
        string path = Uri.UnescapeDataString(rawPath);
        return path.StartsWith("/disk/", StringComparison.Ordinal) && path.Length > 6 && Utf8Length(path) >= 0 &&
            !path.Any(c => Char.IsWhiteSpace(c) || Char.IsControl(c) || "\\%?#".Contains(c)) &&
            !path.Split('/').Any(segment => segment == "." || segment == "..");
    }

    private static bool VisibleAscii(string value, int maximum)
    {
        return value != null && value.Length >= 1 && value.Length <= maximum &&
            value.All(c => c >= 0x21 && c <= 0x7e);
    }

    private static bool JsonFits(object value, int maximum)
    {
        try {
            int bytes = Utf8Length(Json.Serialize(value));
            return bytes >= 0 && bytes <= maximum;
        } catch (InvalidOperationException) { return false; }
        catch (ArgumentException) { return false; }
    }

    internal static bool ClassicDocumentAllowed(Dictionary<string, object> value, string sourceUrl, long now)
    {
        string[] fields = { "source_url", "captured_at", "balancer_url", "token", "document_key",
            "file_type", "download_url", "title", "edit" };
        if (value == null || value.Count != fields.Length || fields.Any(key => !value.ContainsKey(key)) ||
            Text(value, "source_url") != sourceUrl || DocumentUri(sourceUrl) == null) return false;
        object timestamp = value["captured_at"];
        if (!(timestamp is int) && !(timestamp is long)) return false;
        long capturedAt = Convert.ToInt64(timestamp);
        string keyValue = Text(value, "document_key");
        string fileType = Text(value, "file_type");
        string title = Text(value, "title");
        int titleBytes = Utf8Length(title);
        return capturedAt >= now - 120 && capturedAt <= now + 5 &&
            ClassicBalancerAllowed(Text(value, "balancer_url")) &&
            VisibleAscii(Text(value, "token"), 16384) && VisibleAscii(keyValue, 512) &&
            keyValue != "." && keyValue != ".." && fileType != null &&
            Regex.IsMatch(fileType, "\\A[A-Za-z0-9._-]{1,32}\\z") &&
            ClassicDocumentReferenceAllowed(Text(value, "download_url")) &&
            titleBytes >= 0 && titleBytes <= 1024 && !title.Any(Char.IsControl) &&
            value["edit"] is bool && (bool)value["edit"] &&
            JsonFits(value, MaximumClassicDocumentBytes);
    }

    internal static bool CaptureStillCurrent(long capturedRevision, long currentRevision,
        string capturedSource, string currentSource, bool navigationSucceeded, bool offline)
    {
        return capturedRevision == currentRevision && navigationSucceeded &&
            String.Equals(capturedSource, currentSource, StringComparison.Ordinal) &&
            (offline || DocumentUri(currentSource) != null);
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

    internal static bool UserAgentAllowed(string value)
    {
        return value != null && value.Length >= 1 && value.Length <= 1024 &&
            value.Trim() == value && value.All(ch => ch >= 0x20 && ch <= 0x7e);
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
        var checks = new List<bool> {
            ValidId(id), !ValidId(id.ToUpperInvariant()), !ValidId(id + "\n"),
            UserAgentAllowed("SyntheticBrowser/1.0"), UserAgentAllowed(new string('a', 1024)),
            !UserAgentAllowed(null), !UserAgentAllowed(""), !UserAgentAllowed(" leading"),
            !UserAgentAllowed("trailing "), !UserAgentAllowed("a\r\nb"), !UserAgentAllowed("a\tb"),
            !UserAgentAllowed("a\u007fb"), !UserAgentAllowed("a\u0080b"), !UserAgentAllowed(new string('a', 1025)),
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
        checks.AddRange(ClassicDocumentChecks());
        if (checks.Any(value => !value)) return 1;
        Console.WriteLine("Browser helper policy self-test: " + checks.Count + " passed");
        return 0;
    }

    private static IEnumerable<bool> ClassicDocumentChecks()
    {
        const string source = "https://docs.yandex.ru/docs/view?url=synthetic%2fvalue";
        const long now = 1000;
        var valid = new Dictionary<string, object> {
            { "source_url", source }, { "captured_at", now },
            { "balancer_url", "https://docs.yandex.ru" }, { "token", "synthetic-token" },
            { "document_key", "synthetic-key" }, { "file_type", "docx" },
            { "download_url", "https://disk.yandex.net/synthetic?value=1" }, { "title", "" }, { "edit", true }
        };
        Func<string, object, bool> changedAllowed = (key, value) => {
            var changed = new Dictionary<string, object>(valid);
            changed[key] = value;
            return ClassicDocumentAllowed(changed, source, now);
        };
        yield return ClassicDocumentAllowed(valid, source, now);
        yield return !ClassicDocumentAllowed(valid, source.Replace("%2f", "%2F"), now);
        foreach (string field in valid.Keys) {
            var missing = new Dictionary<string, object>(valid);
            missing.Remove(field);
            yield return !ClassicDocumentAllowed(missing, source, now);
            yield return !changedAllowed(field, null);
        }
        yield return !changedAllowed("unexpected", "synthetic");
        yield return changedAllowed("captured_at", now - 120);
        yield return changedAllowed("captured_at", now + 5);
        foreach (object timestamp in new object[] { now - 121, now + 6, "1000", 1000.0, true })
            yield return !changedAllowed("captured_at", timestamp);
        yield return changedAllowed("token", new string('a', 16384));
        foreach (object token in new object[] { "", "a b", "a\tb", "a\u007fb", "a\u0080b", new string('a', 16385), true })
            yield return !changedAllowed("token", token);
        yield return changedAllowed("document_key", new string('a', 512));
        foreach (string key in new[] { "", ".", "..", "a b", "a\nb", new string('a', 513) })
            yield return !changedAllowed("document_key", key);
        yield return changedAllowed("file_type", new string('a', 32));
        foreach (string type in new[] { "", "a/b", "a b", "\u00e9", new string('a', 33) })
            yield return !changedAllowed("file_type", type);
        yield return changedAllowed("title", new string('\u00e9', 512));
        foreach (object title in new object[] { new string('\u00e9', 513), "a\nb", "a\u007fb", "a\u0085b", "\ud800", true })
            yield return !changedAllowed("title", title);
        yield return !changedAllowed("edit", false);
        yield return !changedAllowed("edit", "true");
        yield return !changedAllowed("edit", 1);
        foreach (string url in new[] {
            "http://docs.yandex.ru", "https://yandex.ru", "https://yandex.net", "https://docs.yandex.ru.example",
            "https://docs.yandex.ru.", "https://.yandex.ru", "https://a..yandex.ru", "https://-a.yandex.ru",
            "https://a-.yandex.ru", "https://a_b.yandex.ru", "https://user@docs.yandex.ru", "https://docs.yandex.ru:444",
            "https://docs.yandex.ru:0443", "https://docs.yandex.ru:", "https://docs.yandex.ru#", "https://docs.yandex.ru#x",
            "https://docs.yandex.ru\\test", "https://docs.yandex.ru/\t", "https://docs.yandex.ru/\u00a0", "https://docs.yandex.ru/%zz",
            "https://" + new string('a', 64) + ".yandex.ru", "https://\u044f.yandex.ru"
        }) {
            yield return !ClassicResourceAllowed(url, true);
            yield return !ClassicResourceAllowed(url, false);
        }
        yield return ClassicResourceAllowed("https://docs.yandex.ru:443", true);
        yield return ClassicResourceAllowed("https://docs.yandex.net/synthetic?value=1", false);
        yield return !ClassicResourceAllowed("https://docs.yandex.ru/", true);
        yield return !ClassicResourceAllowed("https://docs.yandex.ru?", true);
        yield return !ClassicResourceAllowed("https://docs.yandex.ru?value=1", true);
        yield return !ClassicResourceAllowed("https://docs.yandex.ru/" + new string('a', 8192), false);
        foreach (string balancer in new[] { "https://classic_a.yandex.ru", "https://_classic.yandex.net:443",
            "HTTPS://CLASSIC_.YANDEX.RU", "https://_.yandex.ru",
            "https://" + String.Join(".", Enumerable.Repeat(new string('a', 60), 4)) + ".yandex.ru" }) {
            yield return ClassicBalancerAllowed(balancer);
            yield return changedAllowed("balancer_url", balancer);
        }
        yield return !ClassicResourceAllowed("https://classic_a.yandex.ru", false);
        yield return !changedAllowed("download_url", "https://classic_a.yandex.ru/file");
        foreach (string balancer in new[] { "http://a_b.yandex.ru", "https://a_b.yandex.ru/",
            "https://a_b.yandex.ru/path", "https://a_b.yandex.ru?", "https://a_b.yandex.ru?q=1",
            "https://a_b.yandex.ru#", "https://a_b.yandex.ru#x", "https://user@a_b.yandex.ru",
            "https://a_b.yandex.ru:0443", "https://a_b.yandex.ru:", "https://a_b.yandex.ru:444",
            "https://a_b.yandex.ru.", "https://a_b..yandex.ru", "https://-a_b.yandex.ru",
            "https://a_b-.yandex.ru", "https://a_b.yandex.ru.example", "https://yandex.ru",
            "https://a%5fb.yandex.ru", "https://a+b.yandex.ru", "https://\u044f_b.yandex.ru",
            "https://" + new string('_', 64) + ".yandex.ru",
            "https://" + new string('a', 61) + "." + String.Join(".", Enumerable.Repeat(new string('a', 60), 3)) + ".yandex.ru" })
            yield return !changedAllowed("balancer_url", balancer);
        const string internalPrefix = "http://localhost:12701/disk/";
        foreach (string reference in new[] { internalPrefix + "x", internalPrefix + "x?",
            internalPrefix + "x?signature=synthetic%2fvalue&fragment=%23",
            internalPrefix + "%D1%84?signature=%ff", internalPrefix + "\u0444",
            internalPrefix + "x%2Fy", internalPrefix + new string('a', 8192 - internalPrefix.Length) }) {
            yield return ClassicDocumentReferenceAllowed(reference);
            yield return changedAllowed("download_url", reference);
            yield return !ClassicResourceAllowed(reference, false);
            yield return !ResourceAllowed(reference);
            yield return !NavigationAllowed(reference);
            yield return DocumentUri(reference) == null;
        }
        foreach (string reference in new[] { "HTTP://localhost:12701/disk/x", "http://LOCALHOST:12701/disk/x",
            "http://127.0.0.1:12701/disk/x", "http://localhost/disk/x", "http://localhost:12702/disk/x",
            "http://localhost:012701/disk/x", "http://user@localhost:12701/disk/x",
            "http://localhost:12701/other/x", internalPrefix, internalPrefix + "?x=1",
            internalPrefix + "x#", internalPrefix + "x#fragment", internalPrefix + "x y",
            internalPrefix + "x\\y", internalPrefix + ".", internalPrefix + "..", internalPrefix + "a/../x",
            internalPrefix + "%2e", internalPrefix + "%2e%2e/x", internalPrefix + "a/%2E%2E/x",
            internalPrefix + "%252e%252e/x", internalPrefix + "x%5Cy", internalPrefix + "x%20y",
            internalPrefix + "x%C2%A0y", internalPrefix + "x%00y", internalPrefix + "x%7Fy",
            internalPrefix + "x%3Fy", internalPrefix + "x%23y", internalPrefix + "%ff",
            internalPrefix + "%c0%af", internalPrefix + "%ed%a0%80", internalPrefix + "%C3%28",
            internalPrefix + "%", internalPrefix + "x?bad=%zz", internalPrefix + "x?bad= ",
            internalPrefix + new string('a', 8193 - internalPrefix.Length) })
            yield return !changedAllowed("download_url", reference);
        var oversized = new Dictionary<string, object>(valid);
        oversized["token"] = new string('a', 16384);
        oversized["download_url"] = "https://docs.yandex.ru/" + new string('a', 8000);
        oversized["title"] = new string('a', 1024);
        yield return !ClassicDocumentAllowed(oversized, source, now);
        yield return CaptureStillCurrent(1, 1, source, source, true, false);
        yield return !CaptureStillCurrent(1, 2, source, source, true, false);
        yield return !CaptureStillCurrent(1, 1, source, source + "x", true, false);
        yield return !CaptureStillCurrent(1, 1, source, source, false, false);
        yield return !CaptureStillCurrent(1, 1, "https://captcha.yandex.ru/", "https://captcha.yandex.ru/", true, false);
        yield return CaptureStillCurrent(1, 1, "about:blank", "about:blank", true, true);
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
    private readonly string sourceUrl;
    private readonly Uri document;
    private readonly string directory;
    private readonly bool offline;
    private readonly CancellationTokenSource cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(45));
    private readonly TaskCompletionSource<bool> browserExited = new TaskCompletionSource<bool>();
    private WebView2 view;
    private Process browser;
    private string stage = "initializing";
    private bool navigationSucceeded;
    private long navigationRevision;
    private ulong navigationId;
    private bool navigationCancelled;
    private bool nodePresent;
    private bool finished;
    private readonly bool interactive;
    internal Dictionary<string, object> Response;
    internal int ExitCode = 22;

    internal VerificationForm(string id, string sourceUrl, Uri document, string directory, bool offline, bool interactive)
    {
        this.id = id;
        this.sourceUrl = sourceUrl;
        this.document = document;
        this.directory = directory;
        this.offline = offline;
        this.interactive = interactive;
        if (offline) cancellation.CancelAfter(TimeSpan.FromSeconds(12));
        Text = System.Globalization.CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ru"
            ? "UnifiedVPN — проверка Яндекса для OpenFlux" : "UnifiedVPN — Yandex verification for OpenFlux";
        ClientSize = new Size(1000, 700);
        ShowInTaskbar = interactive;
        Opacity = interactive ? 1 : 0;
        FormBorderStyle = interactive ? FormBorderStyle.Sizable : FormBorderStyle.FixedToolWindow;
        Shown += async (sender, args) => await VerifyAsync();
        FormClosing += (sender, args) => {
            if (!finished) { args.Cancel = true; cancellation.Cancel(); }
        };
    }

    protected override bool ShowWithoutActivation { get { return !interactive; } }

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
                navigationRevision++;
                navigationId = args.NavigationId;
                navigationSucceeded = false;
                // NavigateToString uses a runtime-specific synthetic URI; offline resources are denied below.
                if (!offline && !BrowserHelper.NavigationAllowed(args.Uri)) {
                    args.Cancel = true;
                    navigationCancelled = true;
                }
            };
            core.SourceChanged += (sender, args) => navigationRevision++;
            core.NavigationCompleted += (sender, args) => {
                if (args.NavigationId == navigationId) navigationSucceeded = args.IsSuccess;
            };
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
                if ((await core.CookieManager.GetCookiesAsync(sourceUrl)).Count != 0)
                    throw new InvalidOperationException();
                var synthetic = core.CookieManager.CreateCookie("anonymous", "synthetic", ".yandex.ru", "/");
                synthetic.IsSecure = true;
                synthetic.IsHttpOnly = true;
                core.CookieManager.AddOrUpdateCookie(synthetic);
                core.NavigateToString("<!doctype html><html><body><script type='application/json' id='client-config'>" +
                    "{\"officeActionData\":{\"balancer_url\":\"https://docs.yandex.ru\",\"editor_config\":{\"token\":\"synthetic\"," +
                    "\"document\":{\"key\":\"synthetic\",\"fileType\":\"docx\",\"url\":\"https://docs.yandex.ru/synthetic\"," +
                    "\"title\":\"Synthetic\",\"permissions\":{\"edit\":true}}}}}</script></body></html>");
            } else {
                core.Navigate(sourceUrl);
            }
            stage = "waiting_document";
            while (true) {
                await Task.Delay(250, cancellation.Token);
                long capturedRevision = navigationRevision;
                string capturedSource = core.Source;
                if (!BrowserHelper.CaptureStillCurrent(capturedRevision, navigationRevision,
                    capturedSource, core.Source, navigationSucceeded, offline)) continue;
                if (offline) {
                    nodePresent = await core.ExecuteScriptAsync("Boolean(document.getElementById('client-config'))") == "true";
                    cancellation.Token.ThrowIfCancellationRequested();
                    if (!BrowserHelper.CaptureStillCurrent(capturedRevision, navigationRevision,
                        capturedSource, core.Source, navigationSucceeded, offline)) continue;
                }
                string ready = await core.ExecuteScriptAsync("(" + BrowserHelper.BootstrapReadyScript + ")(true)");
                cancellation.Token.ThrowIfCancellationRequested();
                if (!BrowserHelper.CaptureStillCurrent(capturedRevision, navigationRevision,
                    capturedSource, core.Source, navigationSucceeded, offline)) continue;
                if (ready == "false" || ready == "null") continue;
                Dictionary<string, object> classicDocument = null;
                if (ready != "true") {
                    classicDocument = BrowserHelper.Json.Deserialize<Dictionary<string, object>>(ready);
                    if (classicDocument == null) throw new InvalidOperationException();
                    long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                    classicDocument.Add("source_url", sourceUrl);
                    classicDocument.Add("captured_at", now);
                    if (!BrowserHelper.ClassicDocumentAllowed(classicDocument, sourceUrl, now))
                        throw new InvalidOperationException();
                }
                stage = "reading_cookies";
                var values = await core.CookieManager.GetCookiesAsync(sourceUrl);
                cancellation.Token.ThrowIfCancellationRequested();
                if (!BrowserHelper.CaptureStillCurrent(capturedRevision, navigationRevision,
                    capturedSource, core.Source, navigationSucceeded, offline)) continue;
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
                string userAgent = core.Settings.UserAgent;
                if (!BrowserHelper.UserAgentAllowed(userAgent)) throw new InvalidOperationException();
                var response = new Dictionary<string, object> {
                    { "id", id }, { "cookies", cookies }, { "user_agent", userAgent }
                };
                if (classicDocument != null) response.Add("classic_document", classicDocument);
                if (cookies.Count == 0 || cookies.Count > 64 ||
                    Encoding.UTF8.GetByteCount(BrowserHelper.Json.Serialize(response)) > BrowserHelper.MaximumMessageBytes - 2)
                    throw new InvalidOperationException();
                cancellation.Token.ThrowIfCancellationRequested();
                if (!BrowserHelper.CaptureStillCurrent(capturedRevision, navigationRevision,
                    capturedSource, core.Source, navigationSucceeded, offline)) continue;
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
