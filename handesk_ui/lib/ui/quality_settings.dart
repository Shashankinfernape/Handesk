import 'package:flutter/material.dart';
import '../main.dart'; // To access setSettings

class QualitySettingsWidget extends StatefulWidget {
  const QualitySettingsWidget({Key? key}) : super(key: key);

  @override
  _QualitySettingsWidgetState createState() => _QualitySettingsWidgetState();
}

class _QualitySettingsWidgetState extends State<QualitySettingsWidget> {
  int _selectedFps = 60;
  int _selectedBitrate = 2000000; // matches backend default

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
          _buildRow('Quality', DropdownButton<int>(
            value: _selectedBitrate,
            dropdownColor: const Color(0xFF2C2C2C),
            style: const TextStyle(color: Colors.cyanAccent, fontFamily: 'monospace', fontSize: 14),
            underline: Container(height: 1, color: Colors.white24),
            items: const [
              DropdownMenuItem(value: 50000000, child: Text('Source (50 Mbps)')),
              DropdownMenuItem(value: 25000000, child: Text('1440p (25 Mbps)')),
              DropdownMenuItem(value: 15000000, child: Text('1080p (15 Mbps)')),
              DropdownMenuItem(value: 10000000, child: Text('720p (10 Mbps)')),
              DropdownMenuItem(value: 5000000, child: Text('480p (5 Mbps)')),
              DropdownMenuItem(value: 3000000, child: Text('360p (3 Mbps)')),
              DropdownMenuItem(value: 2000000, child: Text('Default (2 Mbps)')),
              DropdownMenuItem(value: 1000000, child: Text('240p (1 Mbps)')),
              DropdownMenuItem(value: 500000, child: Text('144p (0.5 Mbps)')),
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
