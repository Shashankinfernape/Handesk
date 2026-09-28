import 'package:flutter/material.dart';
import '../theme/style.dart';
import 'shared.dart';

class QuickConnectWidget extends StatefulWidget {
  final Function(String) onConnect;

  const QuickConnectWidget({Key? key, required this.onConnect}) : super(key: key);

  @override
  _QuickConnectWidgetState createState() => _QuickConnectWidgetState();
}

class _QuickConnectWidgetState extends State<QuickConnectWidget> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  bool _isConnecting = false;
  String? _errorText;

  @override
  void initState() {
    super.initState();
    // Focused by default
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _focusNode.requestFocus();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _handleConnect() async {
    final id = _controller.text.trim();
    if (id.isEmpty) {
      setState(() {
        _errorText = 'Please enter a valid ID';
      });
      return;
    }

    setState(() {
      _isConnecting = true;
      _errorText = null;
    });

    try {
      // Assuming onConnect can be an async function, we await it if it is.
      await widget.onConnect(id);
    } catch (e) {
      if (mounted) {
        setState(() {
          _errorText = 'Failed to connect: $e';
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _isConnecting = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        const Text(
          'Connect to remote',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 16),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextField(
                    controller: _controller,
                    focusNode: _focusNode,
                    enabled: !_isConnecting,
                    decoration: InputDecoration(
                      hintText: 'Enter remote ID',
                      errorText: _errorText,
                      border: const OutlineInputBorder(),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 14,
                      ),
                    ),
                    onSubmitted: (_) => _handleConnect(),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Press Enter to connect',
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.grey.shade600,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 16),
            SizedBox(
              height: 48,
              child: ElevatedButton(
                onPressed: _isConnecting ? null : _handleConnect,
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                ),
                child: _isConnecting
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Connect'),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
