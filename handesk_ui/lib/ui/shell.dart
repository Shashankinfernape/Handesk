import 'package:flutter/material.dart';
import '../theme/style.dart';
import 'shared.dart';

class AppShell extends StatelessWidget {
  final Widget child;
  final String currentVersion;
  final bool isServiceRunning;

  const AppShell({
    super.key,
    required this.child,
    required this.currentVersion,
    required this.isServiceRunning,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Column(
        children: [
          _buildTopBar(context),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _buildSidebar(context),
                Expanded(
                  child: child,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTopBar(BuildContext context) {
    return SizedBox(
      height: 40,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16.0),
        child: Row(
          children: [
            const Icon(Icons.desktop_windows, size: 20),
            const SizedBox(width: 12),
            const Text(
              'Handesk',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const Spacer(),
            Text(
              'v$currentVersion',
              style: TextStyle(fontSize: 12, color: Theme.of(context).disabledColor),
            ),
            const SizedBox(width: 16),
            _StatusLed(isRunning: isServiceRunning),
            const SizedBox(width: 8),
            Text(
              isServiceRunning ? 'Service Running' : 'Service Stopped',
              style: const TextStyle(fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSidebar(BuildContext context) {
    return SizedBox(
      width: 200,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 16),
          const _SidebarItem(icon: Icons.home, label: 'Home', isSelected: true),
          const _SidebarItem(icon: Icons.devices, label: 'Devices'),
          const _SidebarItem(icon: Icons.history, label: 'Recent'),
          const Spacer(),
          const _SidebarItem(icon: Icons.settings, label: 'Settings'),
          const _SidebarItem(icon: Icons.info_outline, label: 'About'),
          const SizedBox(height: 16),
        ],
      ),
    );
  }
}

class _StatusLed extends StatelessWidget {
  final bool isRunning;

  const _StatusLed({required this.isRunning});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: isRunning ? Colors.green : Colors.red,
      ),
    );
  }
}

class _SidebarItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool isSelected;

  const _SidebarItem({
    required this.icon,
    required this.label,
    this.isSelected = false,
  });

  @override
  Widget build(BuildContext context) {
    final color = isSelected ? Theme.of(context).colorScheme.primary : Theme.of(context).unselectedWidgetColor;
    final bgColor = isSelected ? Theme.of(context).colorScheme.primary.withOpacity(0.1) : Colors.transparent;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 2.0),
      child: Container(
        decoration: BoxDecoration(
          color: bgColor,
          borderRadius: BorderRadius.circular(8),
        ),
        child: InkWell(
          onTap: () {},
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 10.0),
            child: Row(
              children: [
                Icon(icon, size: 20, color: color),
                const SizedBox(width: 16),
                Text(
                  label,
                  style: TextStyle(
                    color: color,
                    fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
