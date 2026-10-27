using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace Handesk
{
    static class Program
    {
        private static Mutex _mutex;
        private static NotifyIcon _trayIcon;
        private static string _appDir;
        private static string _hostExe;
        private static string _uiExe;

        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        private const int SW_RESTORE = 9;

        [STAThread]
        static void Main()
        {
            try
            {
                RunApp();
            }
            catch (Exception ex)
            {
                try
                {
                    string log = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "handesk_tray_crash.log");
                    File.AppendAllText(log, DateTime.Now + ": " + ex.ToString() + "\r\n");
                }
                catch { }
            }
        }

        static void RunApp()
        {
            string log = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "handesk_tray.log");
            try { File.AppendAllText(log, DateTime.Now + ": RunApp started\r\n"); } catch { }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool createdNew;
            _mutex = new Mutex(true, "Local\\HandeskTrayMutex_v2", out createdNew);
            if (!createdNew)
            {
                try { File.AppendAllText(log, DateTime.Now + ": Mutex already exists, exiting\r\n"); } catch { }
                return;
            }
            try { File.AppendAllText(log, DateTime.Now + ": Mutex acquired, initializing tray\r\n"); } catch { }

            _appDir = AppDomain.CurrentDomain.BaseDirectory;
            _hostExe = Path.Combine(_appDir, "handesk-host.exe");
            _uiExe = Path.Combine(_appDir, "Handesk.exe");

            Icon appIcon = null;
            string icoPath = Path.Combine(_appDir, "tray-icon.ico");
            if (File.Exists(icoPath))
            {
                try { appIcon = new Icon(icoPath); } catch { }
            }
            if (appIcon == null)
            {
                try { appIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            }
            if (appIcon == null)
            {
                appIcon = SystemIcons.Application;
            }

            // Create context menu
            ContextMenu menu = new ContextMenu();
            MenuItem openItem = new MenuItem("Open Handesk", OnOpen);
            openItem.DefaultItem = true;
            menu.MenuItems.Add(openItem);

            menu.MenuItems.Add(new MenuItem("Copy Local Address", OnCopyAddress));
            menu.MenuItems.Add("-");
            menu.MenuItems.Add(new MenuItem("Exit Handesk", OnExit));

            _trayIcon = new NotifyIcon
            {
                Icon = appIcon,
                ContextMenu = menu,
                Text = "Handesk Remote Desktop (Ready)",
                Visible = true
            };

            _trayIcon.DoubleClick += OnOpen;
            _trayIcon.Click += (s, e) =>
            {
                MouseEventArgs me = e as MouseEventArgs;
                if (me != null && me.Button == MouseButtons.Left)
                {
                    OnOpen(s, e);
                }
            };

            // Ensure host or UI is running
            EnsureHostRunning();

            // Periodic watchdog to keep headless server alive when GUI is closed
            System.Windows.Forms.Timer watchdog = new System.Windows.Forms.Timer();
            watchdog.Interval = 3000;
            watchdog.Tick += (s, e) => EnsureHostRunning();
            watchdog.Start();

            Application.Run(new ApplicationContext());
        }

        private static void EnsureHostRunning()
        {
            // If the Flutter UI is running, it handles the backend host itself
            Process[] uiProcs = Process.GetProcessesByName("Handesk");
            if (uiProcs.Length > 0)
            {
                return;
            }

            if (!File.Exists(_hostExe)) return;

            Process[] hostProcs = Process.GetProcessesByName("handesk-host");
            if (hostProcs.Length == 0)
            {
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo(_hostExe, "--headless")
                    {
                        WorkingDirectory = _appDir,
                        CreateNoWindow = true,
                        UseShellExecute = false,
                        WindowStyle = ProcessWindowStyle.Hidden
                    };
                    Process.Start(psi);
                }
                catch { }
            }
        }

        private static void OnOpen(object sender, EventArgs e)
        {
            // If Flutter UI is already running, bring it to the foreground
            Process[] uiProcs = Process.GetProcessesByName("Handesk");
            foreach (var p in uiProcs)
            {
                if (p.MainWindowHandle != IntPtr.Zero)
                {
                    ShowWindow(p.MainWindowHandle, SW_RESTORE);
                    SetForegroundWindow(p.MainWindowHandle);
                    return;
                }
            }

            // If headless host is running, terminate it so Handesk.exe can bind UDP 21118
            foreach (var p in Process.GetProcessesByName("handesk-host"))
            {
                try { p.Kill(); p.WaitForExit(1000); } catch { }
            }

            // Launch the authentic Handesk Flutter UI
            if (File.Exists(_uiExe))
            {
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo(_uiExe)
                    {
                        WorkingDirectory = _appDir,
                        UseShellExecute = true
                    };
                    Process.Start(psi);
                }
                catch (Exception ex)
                {
                    MessageBox.Show("Failed to launch Handesk: " + ex.Message, "Handesk Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
            else
            {
                MessageBox.Show("Handesk.exe not found at: " + _uiExe, "Handesk Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private static void OnCopyAddress(object sender, EventArgs e)
        {
            string ip = GetLocalIPAddress();
            string fullAddr = ip + ":21118";
            Clipboard.SetText(fullAddr);

            _trayIcon.ShowBalloonTip(2000, "Handesk Address Copied", fullAddr + " copied to clipboard!", ToolTipIcon.Info);
        }

        private static string GetLocalIPAddress()
        {
            try
            {
                using (Socket socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0))
                {
                    socket.Connect("8.8.8.8", 65530);
                    IPEndPoint endPoint = socket.LocalEndPoint as IPEndPoint;
                    if (endPoint != null)
                    {
                        return endPoint.Address.ToString();
                    }
                }
            }
            catch { }

            try
            {
                var host = Dns.GetHostEntry(Dns.GetHostName());
                foreach (var ip in host.AddressList)
                {
                    if (ip.AddressFamily == AddressFamily.InterNetwork && !ip.ToString().StartsWith("127."))
                    {
                        return ip.ToString();
                    }
                }
            }
            catch { }

            return "127.0.0.1";
        }

        private static void OnExit(object sender, EventArgs e)
        {
            if (_trayIcon != null)
            {
                _trayIcon.Visible = false;
                _trayIcon.Dispose();
            }

            // Terminate any running Handesk processes
            foreach (var p in Process.GetProcessesByName("Handesk"))
            {
                try { p.Kill(); } catch { }
            }
            foreach (var p in Process.GetProcessesByName("handesk-host"))
            {
                try { p.Kill(); } catch { }
            }

            Application.Exit();
        }
    }
}
