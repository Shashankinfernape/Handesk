import 'package:flutter/material.dart';
import '../main.dart'; // To access setSettings

class QualitySettingsWidget extends StatefulWidget {
  const QualitySettingsWidget({Key? key}) : super(key: key);

  @override
  _QualitySettingsWidgetState createState() => _QualitySettingsWidgetState();
}

class _QualitySettingsWidgetState extends State<QualitySettingsWidget> {
  int _selectedFps = 60;
  int _selectedBitrate = 4000000; // 4 Mbps default

  void _updateSettings() {
    try {
      setSettings(_selectedFps, _selectedBitrate);
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Host settings updated to $_selectedFps FPS and ${(_selectedBitrate / 1000000).toStringAsFixed(1)} Mbps'),
          duration: const Duration(seconds: 2),
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (e) {
      print('Failed to set quality: $e');
    }
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
            'Host Quality Settings',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: Colors.white,
            ),
          ),
          const SizedBox(height: 20),
          _buildRow('Target FPS', DropdownButton<int>(
            value: _selectedFps,
            dropdownColor: const Color(0xFF2C2C2C),
            style: const TextStyle(color: Colors.cyanAccent, fontFamily: 'monospace', fontSize: 14),
            underline: Container(height: 1, color: Colors.white24),
            items: const [
              DropdownMenuItem(value: 30, child: Text('30 FPS')),
              DropdownMenuItem(value: 60, child: Text('60 FPS')),
              DropdownMenuItem(value: 90, child: Text('90 FPS')),
              DropdownMenuItem(value: 120, child: Text('120 FPS')),
              DropdownMenuItem(value: 144, child: Text('144 FPS')),
            ],
            onChanged: (val) {
              if (val != null) {
                setState(() { _selectedFps = val; });
                _updateSettings();
              }
            },
          )),
          const SizedBox(height: 16),
          _buildRow('Max Bitrate', DropdownButton<int>(
            value: _selectedBitrate,
            dropdownColor: const Color(0xFF2C2C2C),
            style: const TextStyle(color: Colors.cyanAccent, fontFamily: 'monospace', fontSize: 14),
            underline: Container(height: 1, color: Colors.white24),
            items: const [
              DropdownMenuItem(value: 1000000, child: Text('Low (1 Mbps)')),
              DropdownMenuItem(value: 2000000, child: Text('Medium (2 Mbps)')),
              DropdownMenuItem(value: 4000000, child: Text('High - 1080p (4 Mbps)')),
              DropdownMenuItem(value: 8000000, child: Text('Ultra - 1440p (8 Mbps)')),
              DropdownMenuItem(value: 15000000, child: Text('Max (15 Mbps)')),
            ],
            onChanged: (val) {
              if (val != null) {
                setState(() { _selectedBitrate = val; });
                _updateSettings();
              }
            },
          )),
        ],
      ),
    );
  }

  Widget _buildRow(String label, Widget control) {
    return Row(
      children: [
        SizedBox(
          width: 100,
          child: Text(
            label,
            style: const TextStyle(color: Colors.white70, fontSize: 13),
          ),
        ),
        Expanded(child: control),
      ],
    );
  }
}
