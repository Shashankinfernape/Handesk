import 'dart:async';
import 'dart:ffi';
import 'dart:io';
import 'package:flutter/material.dart';

import 'theme/style.dart';
import 'ui/shell.dart';
import 'ui/quick_connect.dart';
import 'ui/this_device.dart';
import 'ui/recent_connections.dart';
import 'ui/quality_settings.dart';

typedef BackendFunc = Void Function();
typedef BackendCall = void Function();
typedef SettingsFunc = Void Function(Uint32 fps, Uint32 bitrate);
typedef SettingsCall = void Function(int fps, int bitrate);

typedef GetSettingsFunc = Uint32 Function();
typedef GetSettingsCall = int Function();

late DynamicLibrary dylib;
late BackendCall startBackend;
late BackendCall stopBackend;
late SettingsCall setSettings;
late GetSettingsCall getCurrentFps;
late GetSettingsCall getCurrentBitrate;

void main() {
  try {
    dylib = DynamicLibrary.open('directlink_host.dll');
    startBackend = dylib.lookupFunction<BackendFunc, BackendCall>('start_directlink_backend');
    stopBackend = dylib.lookupFunction<BackendFunc, BackendCall>('stop_directlink_backend');
    setSettings = dylib.lookupFunction<SettingsFunc, SettingsCall>('set_quality_settings');
    getCurrentFps = dylib.lookupFunction<GetSettingsFunc, GetSettingsCall>('get_current_fps');
    getCurrentBitrate = dylib.lookupFunction<GetSettingsFunc, GetSettingsCall>('get_current_bitrate');
    
    // Auto-start backend on launch
    startBackend();
  } catch (e) {
    print("Could not load backend DLL: $e");
  }

  runApp(const HandeskApp());
}

class HandeskApp extends StatelessWidget {
  const HandeskApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Handesk',
      themeMode: ThemeMode.dark,
      theme: AppTheme.darkTheme,
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  bool isServiceRunning = true;
  String tailscaleIp = "Loading...";
  String lanIp = "Loading...";
  Timer? _ipTimer;

  @override
  void initState() {
    super.initState();
    _fetchIps();
    _ipTimer = Timer.periodic(const Duration(seconds: 4), (_) => _fetchIps());
  }

  @override
  void dispose() {
    _ipTimer?.cancel();
    super.dispose();
  }

  Future<void> _fetchIps() async {
    String newTsIp = "Not Connected";
    String newLanIp = "Not Connected";

    // 1. Fetch Tailscale IP (Checking CLI and PowerShell)
    try {
      final candidates = [
        'tailscale',
        r'C:\Program Files\Tailscale\tailscale.exe',
        r'C:\Program Files (x86)\Tailscale\tailscale.exe',
      ];
      for (final cmd in candidates) {
        try {
          final result = await Process.run(cmd, ['ip', '-4']);
          if (result.exitCode == 0) {
            final out = result.stdout.toString().trim();
            if (out.startsWith('100.')) {
              newTsIp = out;
              break;
            }
          }
        } catch (_) {}
      }
    } catch (_) {}

    if (newTsIp == "Not Connected") {
      try {
        final result = await Process.run('powershell', [
          '-NoProfile',
          '-Command',
          r"(Get-NetIPAddress -InterfaceAlias '*Tailscale*' -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress"
        ]);
        if (result.exitCode == 0) {
          final out = result.stdout.toString().trim();
          if (out.startsWith('100.')) {
            newTsIp = out.split(RegExp(r'\r?\n')).first.trim();
          }
        }
      } catch (_) {}
    }

    // 2. Fetch LAN IP: Query active Windows default route interface via PowerShell
    try {
      final result = await Process.run('powershell', [
        '-NoProfile',
        '-Command',
        r"Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Select-Object -First 1 | ForEach-Object { (Get-NetIPAddress -InterfaceIndex $_.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress }"
      ]);
      if (result.exitCode == 0) {
        final out = result.stdout.toString().trim();
        final lines = out.split(RegExp(r'\r?\n'));
        for (final line in lines) {
          final ip = line.trim();
          if (ip.isNotEmpty &&
              !ip.startsWith('100.') &&
              !ip.startsWith('127.') &&
              !ip.startsWith('192.168.56.') &&
              !ip.startsWith('192.168.137.') &&
              !ip.startsWith('169.254.')) {
            newLanIp = ip;
            break;
          }
        }
      }
    } catch (_) {}

    // 3. Fallback: NetworkInterface.list with physical adapter prioritization
    try {
      final interfaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
      for (var iface in interfaces) {
        final name = iface.name.toLowerCase();
        for (var addr in iface.addresses) {
          final ip = addr.address;
          if (newTsIp == "Not Connected" && (name.contains('tailscale') || ip.startsWith('100.'))) {
            newTsIp = ip;
          }
          if (newLanIp == "Not Connected") {
            if (name.contains('wi-fi') ||
                name.contains('wifi') ||
                name.contains('wireless') ||
                name.contains('wlan') ||
                (name.contains('ethernet') && !name.contains('7'))) {
              if (!ip.startsWith('100.') &&
                  !ip.startsWith('127.') &&
                  !ip.startsWith('192.168.56.') &&
                  !ip.startsWith('192.168.137.') &&
                  !ip.startsWith('169.254.')) {
                newLanIp = ip;
              }
            }
          }
        }
      }
    } catch (_) {}

    if (mounted && (tailscaleIp != newTsIp || lanIp != newLanIp)) {
      setState(() {
        tailscaleIp = newTsIp;
        lanIp = newLanIp;
      });
    }
  }

  void toggleService() {
    setState(() {
      isServiceRunning = !isServiceRunning;
    });
    
    if (isServiceRunning) {
      startBackend();
    } else {
      stopBackend();
    }
  }

  @override
  Widget build(BuildContext context) {
    return AppShell(
      currentVersion: "v1.0.0",
      isServiceRunning: isServiceRunning,
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 860),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 40.0, vertical: 40.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.start,
              children: [
                // Quick Connect is top and center!
                QuickConnectWidget(
                  onConnect: (id) {
                    // Logic to connect goes here
                  }
                ),
                const SizedBox(height: 48),
                
                // Properties panels below
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      flex: 5,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          ThisDeviceWidget(
                            tailscaleIp: tailscaleIp,
                            lanIp: lanIp,
                            isServiceRunning: isServiceRunning,
                            onToggle: toggleService,
                          ),
                          const SizedBox(height: 24),
                          const QualitySettingsWidget(),
                        ],
                      ),
                    ),
                    const SizedBox(width: 48),
                    const Expanded(
                      flex: 5,
                      child: RecentConnectionsWidget(),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
