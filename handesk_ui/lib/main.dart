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

  @override
  void initState() {
    super.initState();
    _fetchIps();
  }

  Future<void> _fetchIps() async {
    // 1. Fetch Tailscale IP
    try {
      final result = await Process.run('tailscale', ['ip', '-4']);
      if (result.exitCode == 0) {
        tailscaleIp = result.stdout.toString().trim();
      } else {
        tailscaleIp = "Not Connected";
      }
    } catch (e) {
      tailscaleIp = "Tailscale Missing";
    }

    // 2. Fetch LAN IP
    try {
      lanIp = "Not Connected";
      final interfaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
      for (var interface in interfaces) {
        if (interface.name.toLowerCase().contains('tailscale')) continue;
        for (var addr in interface.addresses) {
          if (addr.address.startsWith('100.')) continue;
          if (addr.address.startsWith('127.')) continue;
          
          lanIp = addr.address;
          break;
        }
        if (lanIp != "Not Connected") break;
      }
    } catch (e) {
      lanIp = "Error finding LAN";
    }

    if (mounted) setState(() {});
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
