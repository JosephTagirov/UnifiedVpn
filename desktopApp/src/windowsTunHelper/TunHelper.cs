// .NET Framework 4.x, built with the Windows compiler; no package dependencies.
// UAC consent when elevation is needed. No service, arbitrary commands or executable
// paths are accepted over IPC. Only this assembly's embedded assets may run.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using Microsoft.Win32.SafeHandles;

internal static class TunHelper
{
    const int MaxMessage = 8192;
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = MaxMessage };
    static readonly SecurityIdentifier Admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
    static readonly SecurityIdentifier SystemSid = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
    static readonly SecurityIdentifier Users = new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint pid);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetNamedPipeClientProcessId(SafePipeHandle pipe, out uint pid);

    public static int Main(string[] args)
    {
        try
        {
            if (args.Length == 1 && args[0] == "--self-test") return SelfTest();
            if (args.Length == 1 && args[0] == "--broker") return Broker();
            if (args.Length == 4 && args[0] == "--elevated")
                return Elevated(int.Parse(args[1]), long.Parse(args[2]), Guid.ParseExact(args[3], "N"));
            throw new ArgumentException("Unsupported helper invocation");
        }
        catch (Exception e)
        {
            // Do not print config, credentials, native output or destinations.
            Console.WriteLine("ERROR Windows TUN helper: " + e.GetType().Name);
            return 1;
        }
    }

    static bool IsAdmin()
    {
        return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);
    }

    static string ReadBounded(TextReader reader)
    {
        var value = new StringBuilder();
        for (int c; (c = reader.Read()) != -1 && c != '\n'; )
        {
            if (value.Length >= MaxMessage) throw new InvalidDataException("Message too large");
            if (c != '\r') value.Append((char)c);
        }
        return value.ToString();
    }

    internal static Dictionary<string, object> Validate(string text)
    {
        var c = Json.Deserialize<Dictionary<string, object>>(text);
        if (c == null || c.Count != 4 || !c.ContainsKey("port") || !c.ContainsKey("username") ||
            !c.ContainsKey("password") || !c.ContainsKey("endpoints")) throw new InvalidDataException();
        if (!(c["port"] is int) || (int)c["port"] < 1024 || (int)c["port"] > 65535)
            throw new InvalidDataException();
        foreach (var key in new[] { "username", "password" })
            if (!(c[key] is string) || ((string)c[key]).Length > 64 ||
                ((string)c[key]).IndexOf('\0') >= 0) throw new InvalidDataException();
        var endpoints = c["endpoints"] as System.Collections.ArrayList;
        if (endpoints == null || endpoints.Count != 1) throw new InvalidDataException();
        for (int index = 0; index < endpoints.Count; index++)
        {
            var entry = endpoints[index];
            IPAddress ip;
            if (!(entry is string) || !IPAddress.TryParse((string)entry, out ip) ||
                !string.Equals((string)entry, ((string)entry).Trim(), StringComparison.Ordinal) ||
                ((string)entry).Contains("[") || ((string)entry).Contains("]") || IPAddress.IsLoopback(ip) ||
                ip.Equals(IPAddress.Any) || ip.Equals(IPAddress.IPv6Any) || ip.IsIPv6LinkLocal ||
                ip.IsIPv6Multicast || ip.IsIPv4MappedToIPv6 || ((string)entry).Contains("%") ||
                (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                    (!string.Equals((string)entry, ip.ToString(), StringComparison.Ordinal) ||
                    ip.GetAddressBytes()[0] >= 224 ||
                    (ip.GetAddressBytes()[0] == 169 && ip.GetAddressBytes()[1] == 254))))
                throw new InvalidDataException();
            endpoints[index] = ip.ToString();
        }
        return c;
    }

    static int Broker()
    {
        string config = Json.Serialize(Validate(ReadBounded(Console.In)));
        string id = Guid.NewGuid().ToString("N");
        var acl = new PipeSecurity();
        acl.SetAccessRuleProtection(true, false);
        acl.AddAccessRule(new PipeAccessRule(WindowsIdentity.GetCurrent().User, PipeAccessRights.FullControl, AccessControlType.Allow));
        acl.AddAccessRule(new PipeAccessRule(Admins, PipeAccessRights.ReadWrite, AccessControlType.Allow));
        acl.AddAccessRule(new PipeAccessRule(SystemSid, PipeAccessRights.FullControl, AccessControlType.Allow));
        using (var pipe = new NamedPipeServerStream("UnifiedVPN-Tun-" + id, PipeDirection.InOut, 1,
            PipeTransmissionMode.Byte, PipeOptions.Asynchronous, MaxMessage, MaxMessage, acl))
        {
            var owner = Process.GetCurrentProcess();
            var executable = Assembly.GetExecutingAssembly().Location;
            var start = WorkerStartInfo(executable, owner.Id, owner.StartTime.ToUniversalTime().Ticks,
                Guid.ParseExact(id, "N"), IsAdmin());
            var stopped = Task.Factory.StartNew(() => ReadBounded(Console.In));
            var connected = pipe.BeginWaitForConnection(null, null);
            using (var worker = Process.Start(start))
            {
                if (worker == null) throw new IOException("UAC helper did not start");
                var timer = Stopwatch.StartNew();
                while (!connected.IsCompleted)
                {
                    if (stopped.IsCompleted || worker.HasExited || timer.ElapsedMilliseconds > 120000)
                        throw new IOException("UAC cancelled or helper timed out");
                    Thread.Sleep(50);
                }
                pipe.EndWaitForConnection(connected);
                uint client;
                if (!GetNamedPipeClientProcessId(pipe.SafePipeHandle, out client) || client != worker.Id)
                    throw new UnauthorizedAccessException();
                using (var reader = new StreamReader(pipe, Encoding.UTF8, false, 1024, true))
                using (var writer = new StreamWriter(pipe, new UTF8Encoding(false), 1024, true) { AutoFlush = true })
                {
                    writer.WriteLine(config);
                    var line = Task.Factory.StartNew(() => ReadBounded(reader));
                    while (!stopped.IsCompleted && !worker.HasExited)
                    {
                        if (line.IsCompleted)
                        {
                            string status = line.Result;
                            if (status == "READY" || status == "STOPPED" || status.StartsWith("ERROR "))
                                Console.WriteLine(status);
                            if (status == "STOPPED" || status.StartsWith("ERROR ") || status.Length == 0) break;
                            line = Task.Factory.StartNew(() => ReadBounded(reader));
                        }
                        Thread.Sleep(50);
                    }
                    // EOF also stops the worker: JVM/broker death does not leave a lease.
                    try { writer.WriteLine("STOP"); } catch (IOException) { }
                    if (!worker.WaitForExit(30000)) throw new IOException("Helper cleanup timeout");
                    return worker.ExitCode;
                }
            }
        }
    }

    internal static ProcessStartInfo WorkerStartInfo(string executable, int ownerPid, long ownerTicks,
        Guid id, bool ownerIsAdmin)
    {
        // An elevated broker already owns the required token. Keep both paths
        // on the same verified IPC protocol; only the launch mechanism differs.
        return new ProcessStartInfo(executable,
            "--elevated " + ownerPid + " " + ownerTicks + " " + id.ToString("N")) {
            UseShellExecute = !ownerIsAdmin,
            Verb = ownerIsAdmin ? "" : "runas",
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden
        };
    }

    static bool SameProcess(int id, long ticks)
    {
        try { using (var p = Process.GetProcessById(id)) return !p.HasExited && p.StartTime.ToUniversalTime().Ticks == ticks; }
        catch { return false; }
    }

    static int Elevated(int ownerPid, long ownerTicks, Guid id)
    {
        if (!IsAdmin() || !SameProcess(ownerPid, ownerTicks)) throw new UnauthorizedAccessException();
        using (var pipe = new NamedPipeClientStream(".", "UnifiedVPN-Tun-" + id.ToString("N"),
            PipeDirection.InOut, PipeOptions.Asynchronous, TokenImpersonationLevel.Identification))
        {
            pipe.Connect(15000);
            uint server;
            if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out server) || server != ownerPid)
                throw new UnauthorizedAccessException();
            using (var reader = new StreamReader(pipe, Encoding.UTF8, false, 1024, true))
            using (var writer = new StreamWriter(pipe, new UTF8Encoding(false), 1024, true) { AutoFlush = true })
            {
                string session = null;
                try
                {
                    var message = Task.Factory.StartNew(() => ReadBounded(reader));
                    if (!message.Wait(10000)) throw new TimeoutException();
                    var config = Validate(message.Result);
                    var root = ProtectedRoot();
                    session = Path.Combine(root, "session-" + id.ToString("N"));
                    Directory.CreateDirectory(session, DirectoryAcl());
                    CheckDirectory(session);
                    foreach (string name in new[] { "tun2socks.exe", "wintun.dll", "network.ps1" })
                        Extract(name, session);
                    var start = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                        "WindowsPowerShell", "v1.0", "powershell.exe"),
                        "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + Path.Combine(session, "network.ps1") + "\"") {
                        UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = session,
                        RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
                    };
                    // Elevated children must not load startup hooks or DLLs from user paths.
                    start.EnvironmentVariables.Clear();
                    string system = Environment.GetFolderPath(Environment.SpecialFolder.System);
                    start.EnvironmentVariables["SystemRoot"] = Directory.GetParent(system).FullName;
                    start.EnvironmentVariables["WINDIR"] = Directory.GetParent(system).FullName;
                    start.EnvironmentVariables["PATH"] = system;
                    start.EnvironmentVariables["PSModulePath"] = Path.Combine(system, "WindowsPowerShell", "v1.0", "Modules");
                    start.EnvironmentVariables["TEMP"] = session;
                    start.EnvironmentVariables["TMP"] = session;
                    using (var job = new ChildJob())
                    using (var network = Process.Start(start))
                    {
                        job.Add(network);
                        network.BeginErrorReadLine(); // Do not forward native details or secrets.
                        network.StandardInput.WriteLine(Json.Serialize(config));
                        network.StandardInput.Flush();
                        var stop = Task.Factory.StartNew(() => ReadBounded(reader));
                        var status = Task.Factory.StartNew(() => ReadBounded(network.StandardOutput));
                        try
                        {
                            var startup = Stopwatch.StartNew();
                            bool ready = false;
                            while (!network.HasExited && !stop.IsCompleted && SameProcess(ownerPid, ownerTicks))
                            {
                                if (status.IsCompleted)
                                {
                                    string value = status.Result;
                                    if (value == "READY") { writer.WriteLine("READY"); ready = true; }
                                    else if (value.StartsWith("ERROR ")) { writer.WriteLine(value); break; }
                                    else if (value.Length == 0) break;
                                    status = Task.Factory.StartNew(() => ReadBounded(network.StandardOutput));
                                }
                                if (!ready && startup.ElapsedMilliseconds > 60000) throw new TimeoutException();
                                Thread.Sleep(100);
                            }
                        }
                        finally
                        {
                            if (!network.HasExited)
                            {
                                try { network.StandardInput.WriteLine("STOP"); network.StandardInput.Close(); } catch { }
                                if (!network.WaitForExit(25000)) network.Kill();
                            }
                        }
                        if (network.ExitCode != 0) throw new IOException("Network helper failed");
                    }
                    writer.WriteLine("STOPPED");
                    return 0;
                }
                catch (Exception e)
                {
                    try { writer.WriteLine("ERROR Windows TUN: " + e.GetType().Name + "; check helper setup and UAC"); } catch { }
                    return 1;
                }
                finally { if (session != null) TryCleanSession(session); }
            }
        }
    }

    static DirectorySecurity DirectoryAcl()
    {
        var acl = new DirectorySecurity();
        acl.SetAccessRuleProtection(true, false);
        acl.SetOwner(Admins);
        foreach (var sid in new[] { Admins, SystemSid })
            acl.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        acl.AddAccessRule(new FileSystemAccessRule(Users, FileSystemRights.ReadAndExecute,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        return acl;
    }

    static void CheckDirectory(string path)
    {
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new UnauthorizedAccessException();
        var security = Directory.GetAccessControl(path);
        var owner = (SecurityIdentifier)security.GetOwner(typeof(SecurityIdentifier));
        if (!owner.Equals(Admins) && !owner.Equals(SystemSid)) throw new UnauthorizedAccessException();
        const FileSystemRights write = FileSystemRights.Write | FileSystemRights.Delete | FileSystemRights.DeleteSubdirectoriesAndFiles |
            FileSystemRights.ChangePermissions | FileSystemRights.TakeOwnership;
        foreach (FileSystemAccessRule rule in security.GetAccessRules(true, true, typeof(SecurityIdentifier)))
            if (rule.AccessControlType == AccessControlType.Allow && (rule.FileSystemRights & write) != 0 &&
                !rule.IdentityReference.Equals(Admins) && !rule.IdentityReference.Equals(SystemSid))
                throw new UnauthorizedAccessException();
    }

    static string ProtectedRoot()
    {
        string common = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        for (var directory = new DirectoryInfo(common); directory != null; directory = directory.Parent)
            if ((directory.Attributes & FileAttributes.ReparsePoint) != 0) throw new UnauthorizedAccessException();
        string path = Path.Combine(common, "UnifiedVPN-Tun");
        Directory.CreateDirectory(path, DirectoryAcl());
        CheckDirectory(path);
        return path;
    }

    static void Extract(string name, string directory)
    {
        using (var input = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
        using (var output = new FileStream(Path.Combine(directory, name), FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            if (input == null) throw new FileNotFoundException("Embedded asset missing");
            input.CopyTo(output);
        }
    }

    static void TryCleanSession(string directory)
    {
        try
        {
            CheckDirectory(directory);
            // Keep a failed cleanup journal for the next session's recovery.
            if (File.Exists(Path.Combine(directory, "state.json"))) return;
            foreach (var name in new[] { "tun2socks.exe", "wintun.dll", "network.ps1" })
                File.Delete(Path.Combine(directory, name));
            Directory.Delete(directory, false);
        }
        catch { /* A locked file must not obscure the connection result. */ }
    }

    static int SelfTest()
    {
        const string valid = "{\"port\":10808,\"username\":\"\",\"password\":\"\",\"endpoints\":[\"203.0.113.7\"]}";
        Validate(valid);
        Validate(valid.Replace("203.0.113.7", "2001:db8:0:0:0:0:0:7"));
        Validate(valid.Replace("203.0.113.7", "192.168.1.7"));
        string[] invalid = { valid.Replace("10808", "1"), valid.Replace("10808", "65536"),
            valid.Replace("203.0.113.7", "127.0.0.1"), valid.Replace("203.0.113.7", "host.example"),
            valid.Replace("203.0.113.7", "0.0.0.0"), valid.Replace("203.0.113.7", "224.0.0.1"),
            valid.Replace("203.0.113.7", "169.254.10.20"), valid.Replace("203.0.113.7", "203.113.7"),
            valid.Replace("203.0.113.7", "0xcb.0.113.7"), valid.Replace("203.0.113.7", "0313.0.113.7"),
            valid.Replace("203.0.113.7", "203.000.113.7"), valid.Replace("203.0.113.7", "203.0.113.7 "),
            valid.Replace("203.0.113.7", "::"), valid.Replace("203.0.113.7", "::1"),
            valid.Replace("203.0.113.7", "fe80::7"), valid.Replace("203.0.113.7", "ff02::1"),
            valid.Replace("203.0.113.7", "2001:db8::7%3"), valid.Replace("203.0.113.7", "[2001:db8::7]"),
            valid.Replace("203.0.113.7", "::ffff:127.0.0.1"), valid.Replace("203.0.113.7", "::ffff:203.0.113.7"),
            valid.Replace("203.0.113.7", "203.0.113.7'; Stop-Computer; '"),
            valid.Replace("\"port\":10808", "\"command\":\"calc.exe\""),
            valid.Replace("[\"203.0.113.7\"]", "[]"), valid.Replace("\"username\":\"\"", "\"username\":true") };
        foreach (var value in invalid)
        {
            bool rejected = false;
            try { Validate(value); } catch { rejected = true; }
            if (!rejected) throw new Exception("Validation regression");
        }
        bool bounded = false;
        try { ReadBounded(new StringReader(new string('x', MaxMessage + 1))); } catch (InvalidDataException) { bounded = true; }
        if (!bounded) throw new Exception("Message limit regression");
        string executable = @"C:\Program Files\UnifiedVPN\unifiedvpn-tun-helper.exe";
        Guid session = Guid.ParseExact("0123456789abcdef0123456789abcdef", "N");
        foreach (bool ownerIsAdmin in new[] { false, true })
        {
            var start = WorkerStartInfo(executable, 1234, 638900000000000000L, session, ownerIsAdmin);
            if (start.FileName != executable ||
                start.Arguments != "--elevated 1234 638900000000000000 0123456789abcdef0123456789abcdef")
                throw new Exception("Worker identity regression");
            if (start.UseShellExecute != !ownerIsAdmin || start.Verb != (ownerIsAdmin ? "" : "runas"))
                throw new Exception("Worker elevation regression");
            if (!start.CreateNoWindow || start.WindowStyle != ProcessWindowStyle.Hidden)
                throw new Exception("Worker visibility regression");
        }
        Console.WriteLine("PASS: " + (invalid.Length + 4) + " helper input checks, 6 launch-plan checks; no elevation, adapter or routes created");
        return 0;
    }

    // The job closes native children even if the elevated supervisor crashes.
    // Route records survive under the protected root for recovery on next start.
    sealed class ChildJob : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
            public long ProcessTime, JobTime; public uint Flags;
            public UIntPtr MinWorkingSet, MaxWorkingSet; public uint ActiveProcesses;
            public UIntPtr Affinity; public uint Priority, Scheduling;
        }
        [StructLayout(LayoutKind.Sequential)] struct IoCounters { public ulong a, b, c, d, e, f; }
        [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
            public BasicLimits Basic; public IoCounters Io;
            public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job, int info, ref ExtendedLimits value, uint size);
        [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        IntPtr handle;
        public ChildJob() {
            handle = CreateJobObject(IntPtr.Zero, null);
            var limits = new ExtendedLimits(); limits.Basic.Flags = 0x2000;
            if (handle == IntPtr.Zero || !SetInformationJobObject(handle, 9, ref limits, (uint)Marshal.SizeOf(limits)))
                throw new System.ComponentModel.Win32Exception();
        }
        public void Add(Process p) {
            if (!AssignProcessToJobObject(handle, p.Handle)) { p.Kill(); throw new System.ComponentModel.Win32Exception(); }
        }
        public void Dispose() { if (handle != IntPtr.Zero) { CloseHandle(handle); handle = IntPtr.Zero; } }
    }
}
