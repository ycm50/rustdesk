import 'dart:async';

import 'package:flutter/material.dart';

import 'key_dispatcher.dart';
import 'keyboard_layouts.dart';

/// Full on-screen PC keyboard.
///
/// Every cap maps to a real key code, so combinations work on the remote side
/// exactly as they would from a hardware keyboard.
class PcKeyboardView extends StatefulWidget {
  const PcKeyboardView({
    super.key,
    required this.dispatcher,
    this.showFunctionRow = false,
    this.onToggleFunctionRow,
  });

  final KeyDispatcher dispatcher;
  final bool showFunctionRow;
  final VoidCallback? onToggleFunctionRow;

  @override
  State<PcKeyboardView> createState() => _PcKeyboardViewState();
}

class _PcKeyboardViewState extends State<PcKeyboardView> {
  Timer? _repeatTimer;
  String? _repeatingKey;

  static const Set<String> _repeatableKeys = <String>{
    'VK_BACK',
    'VK_DELETE',
    'VK_LEFT',
    'VK_RIGHT',
    'VK_UP',
    'VK_DOWN',
  };

  @override
  void dispose() {
    _stopRepeat();
    super.dispose();
  }

  void _stopRepeat() {
    _repeatTimer?.cancel();
    _repeatTimer = null;
    final String? key = _repeatingKey;
    if (key != null) {
      widget.dispatcher.keyUp(key);
      _repeatingKey = null;
    }
  }

  bool _canRepeat(KeyCap cap, bool compact) =>
      !compact && _repeatableKeys.contains(cap.keyName);

  void _startRepeat(String keyName) {    _stopRepeat();
    _repeatingKey = keyName;
    widget.dispatcher.keyDown(keyName);
    _repeatTimer = Timer(const Duration(milliseconds: 400), () {
      _repeatTimer = Timer.periodic(const Duration(milliseconds: 45), (_) {
        widget.dispatcher.keyDown(keyName);
      });
    });
  }

  void _onTap(KeyCap cap) {
    setState(() {
      widget.dispatcher.tap(cap);
    });
  }

  @override
  Widget build(BuildContext context) {
    final List<List<KeyCap>> rows = kPcKeyboardRows;
    return Container(
      color: const Color(0xFF1F1F23),
      padding: const EdgeInsets.fromLTRB(2, 4, 2, 4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Row(
            children: <Widget>[
              _toolButton(
                label: widget.showFunctionRow ? 'F  ▾' : 'F  ▸',
                onTap: widget.onToggleFunctionRow,
              ),
            ],
          ),
          if (widget.showFunctionRow)
            _buildRow(kPcKeyboardFunctionRow, compact: true),
          for (final List<KeyCap> row in rows) _buildRow(row),
        ],
      ),
    );
  }

  Widget _toolButton({required String label, VoidCallback? onTap}) {
    return GestureDetector(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        child: Text(
          label,
          style: const TextStyle(color: Colors.white70, fontSize: 11),
        ),
      ),
    );
  }

  Widget _buildRow(List<KeyCap> caps, {bool compact = false}) {
    return Padding(
      padding: EdgeInsets.symmetric(vertical: compact ? 1 : 2),
      child: Row(
        children: <Widget>[
          for (final KeyCap cap in caps)
            Expanded(
              flex: (cap.flex * 10).round(),
              child: _buildCap(cap, compact: compact),
            ),
        ],
      ),
    );
  }

  Widget _buildCap(KeyCap cap, {required bool compact}) {
    final bool latched = widget.dispatcher.isLatched(cap.keyName);
    final String label =
        cap.kind == KeyCapKind.modifier ? cap.label : widget.dispatcher.labelFor(cap);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 1),
      child: Material(
        color: latched ? const Color(0xFF3D5AFE) : const Color(0xFF2E2E33),
        borderRadius: BorderRadius.circular(5),
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => _onTap(cap),
          onLongPressStart: _canRepeat(cap, compact)
              ? (LongPressStartDetails _) => _startRepeat(cap.keyName)
              : null,
          onLongPressEnd: _canRepeat(cap, compact)
              ? (LongPressEndDetails _) => _stopRepeat()
              : null,
          child: SizedBox(
            height: compact ? 26 : 34,
            child: Center(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.clip,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: compact ? 11 : 13,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
