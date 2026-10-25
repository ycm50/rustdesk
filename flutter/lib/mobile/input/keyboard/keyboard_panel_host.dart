import 'package:flutter/material.dart';

import 'keyboard_layouts.dart';
import 'keyboard_mode.dart';
import 'key_dispatcher.dart';
import 'pc_keyboard_view.dart';
import 'shortcut_panel.dart';

/// Bottom sheet that hosts the three keyboard surfaces.
///
/// The three modes are mutually exclusive by design: an IME surface and a
/// synthetic PC keyboard would fight over the same screen space and the same
/// touch targets.
class KeyboardPanelHost extends StatefulWidget {
  const KeyboardPanelHost({
    super.key,
    required this.dispatcher,
    required this.imeController,
    required this.onImeChanged,
    this.initialMode = RcKeyboardMode.pc,
    this.onModeChanged,
    this.onImeRequested,
  });

  final KeyDispatcher dispatcher;
  final TextEditingController imeController;
  final ValueChanged<String> onImeChanged;
  final RcKeyboardMode initialMode;
  final ValueChanged<RcKeyboardMode>? onModeChanged;

  /// Called when the IME tab becomes active. Dismissing the system keyboard
  /// makes the Android window non-IME-eligible, so the host has to re-enable it
  /// before the field is focused again.
  final VoidCallback? onImeRequested;

  @override
  State<KeyboardPanelHost> createState() => _KeyboardPanelHostState();
}

class _KeyboardPanelHostState extends State<KeyboardPanelHost> {
  late RcKeyboardMode _mode = widget.initialMode;
  bool _showFunctionRow = false;
  final FocusNode _imeFocusNode = FocusNode();

  @override
  void dispose() {
    _imeFocusNode.dispose();
    super.dispose();
  }

  void _select(RcKeyboardMode mode) {
    if (_mode == mode) return;
    setState(() => _mode = mode);
    widget.onModeChanged?.call(mode);
    if (mode == RcKeyboardMode.ime) {
      widget.onImeRequested?.call();
      _imeFocusNode.requestFocus();
    } else {
      _imeFocusNode.unfocus();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        _buildTabs(),
        Flexible(child: _buildBody()),
      ],
    );
  }

  Widget _buildTabs() {
    return Container(
      color: const Color(0xFF18181C),
      height: 36,
      child: Row(
        children: <Widget>[
          _tab(RcKeyboardMode.ime, '输入法'),
          _tab(RcKeyboardMode.shortcuts, '快捷键'),
          _tab(RcKeyboardMode.pc, '电脑键盘'),
          const Spacer(),
        ],
      ),
    );
  }

  Widget _tab(RcKeyboardMode mode, String label) {
    final bool selected = _mode == mode;
    return GestureDetector(
      onTap: () => _select(mode),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          border: Border(
            bottom: BorderSide(
              color: selected ? const Color(0xFF3D5AFE) : Colors.transparent,
              width: 2,
            ),
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.white : Colors.white54,
            fontSize: 13,
          ),
        ),
      ),
    );
  }

  Widget _buildBody() {
    switch (_mode) {
      case RcKeyboardMode.ime:
        return _buildIme();
      case RcKeyboardMode.shortcuts:
        return ShortcutPanel(dispatcher: widget.dispatcher);
      case RcKeyboardMode.pc:
        return PcKeyboardView(
          dispatcher: widget.dispatcher,
          showFunctionRow: _showFunctionRow,
          onToggleFunctionRow: () =>
              setState(() => _showFunctionRow = !_showFunctionRow),
        );
    }
  }

  Widget _buildIme() {
    return Container(
      color: const Color(0xFF1F1F23),
      padding: const EdgeInsets.all(8),
      child: TextField(
        controller: widget.imeController,
        focusNode: _imeFocusNode,
        autofocus: true,
        maxLines: 3,
        minLines: 1,
        style: const TextStyle(color: Colors.white, fontSize: 15),
        decoration: InputDecoration(
          isDense: true,
          hintText: '在此输入，直接发送到远端',
          hintStyle: const TextStyle(color: Colors.white38, fontSize: 13),
          filled: true,
          fillColor: const Color(0xFF2E2E33),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: BorderSide.none,
          ),
          suffixIcon: IconButton(
            icon: const Icon(Icons.clear, size: 18, color: Colors.white54),
            onPressed: () {
              widget.imeController.clear();
              widget.onImeChanged('');
            },
          ),
        ),
        onChanged: widget.onImeChanged,
      ),
    );
  }
}

/// Small helper used by the host when the caller wants a plain key cap row.
List<KeyCap> get kShortcutFallbackRow => kPcKeyboardFunctionRow;
