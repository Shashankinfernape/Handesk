import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../theme/style.dart';
import 'shared.dart';

class ThisDeviceWidget extends StatelessWidget {
  final String tailscaleIp;
  final String lanIp;
  final bool isServiceRunning;
  final VoidCallback onToggle;

  const ThisDeviceWidget({
    Key? key,
    required this.tailscaleIp,
    required this.lanIp,
    required this.isServiceRunning,
    required this.onToggle,
  }) : super(key: key);

  void _copyToClipboard(BuildContext context, String text, String label) {
    Clipboard.setData(ClipboardData(text: text));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('$label copied to clipboard'),
        duration: const Duration(seconds: 2),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            'This Device',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: Colors.white,
            ),
          ),
          const SizedBox(height: 20),
          _buildInfoRow(context, 'Tailscale IP', tailscaleIp),
          const SizedBox(height: 16),
          _buildInfoRow(context, 'LAN IP', lanIp),
          const SizedBox(height: 16),
          _buildStatusRow(),
        ],
      ),
    );
  }

  Widget _buildInfoRow(BuildContext context, String label, String value) {
    return Row(
      children: [
        SizedBox(
          width: 100,
          child: Text(
            label,
            style: const TextStyle(color: Colors.white70, fontSize: 13),
          ),
        ),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(
              color: Colors.cyanAccent,
              fontFamily: 'monospace',
              fontSize: 14,
            ),
          ),
        ),
        IconButton(
          icon: const Icon(Icons.copy, size: 16, color: Colors.white54),
          onPressed: () => _copyToClipboard(context, value, label),
          tooltip: 'Copy $label',
          splashRadius: 16,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
          hoverColor: Colors.white10,
        ),
      ],
    );
  }

  Widget _buildStatusRow() {
    return Row(
      children: [
        const SizedBox(
          width: 100,
          child: Text(
            'Service Status',
            style: TextStyle(color: Colors.white70, fontSize: 13),
          ),
        ),
        Expanded(
          child: Row(
            children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: isServiceRunning ? Colors.greenAccent : Colors.redAccent,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                isServiceRunning ? 'Running' : 'Stopped',
                style: TextStyle(
                  color: isServiceRunning ? Colors.greenAccent : Colors.redAccent,
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
        TextButton(
          onPressed: onToggle,
          style: TextButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            minimumSize: Size.zero,
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            backgroundColor: isServiceRunning 
                ? Colors.redAccent.withOpacity(0.1) 
                : Colors.greenAccent.withOpacity(0.1),
            foregroundColor: isServiceRunning ? Colors.redAccent : Colors.greenAccent,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(4),
            ),
          ),
          child: Text(
            isServiceRunning ? 'Stop' : 'Start',
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
          ),
        ),
      ],
    );
  }
}
