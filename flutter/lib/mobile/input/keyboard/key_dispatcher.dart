import 'package:flutter_hbb/models/input_model.dart';

import 'keyboard_layouts.dart';

/// Sends key codes to the remote side and owns the sticky-modifier state.
///
/// Modifiers are latched locally: tapping Ctrl lights it up, the next key is
/// sent with `ctrl` set, and the latch clears afterwards. That matches how a
/// physical Shift behaves when you tap it once, without needing two hands on a
/// phone screen.
class KeyDispatcher {
  KeyDispatcher(this._input);

  final InputModel _input;
  final Set<String> _latched = <String>{};

  bool get hasLatched => _latched.isNotEmpty;

  bool isLatched(String keyName) => _latched.contains(keyName);

  bool get shiftLatched => _latched.contains('VK_SHIFT');

  /// Label to draw on a cap, taking the latched Shift into account.
  String labelFor(KeyCap cap) => shiftLatched ? cap.effective.label : cap.label;

  void toggle(String keyName) {
    if (!_latched.remove(keyName)) {
      _latched.add(keyName);
    }
    _sync();
  }

  void clearLatched() {
    if (_latched.isEmpty) return;
    _latched.clear();
    _sync();
  }

  void _sync() {
    _input.ctrl = _latched.contains('VK_CONTROL');
    _input.shift = _latched.contains('VK_SHIFT');
    _input.alt = _latched.contains('VK_MENU');
    _input.command = _latched.contains('VK_LWIN');
  }

  /// Tap a cap. Modifier caps toggle their latch instead of sending a key.
  void tap(KeyCap cap) {
    if (cap.kind == KeyCapKind.modifier) {
      toggle(cap.keyName);
      return;
    }
    tapKey(cap.keyName);
  }

  /// Tap a raw key name, applying and then clearing the latched modifiers.
  void tapKey(String keyName) {
    _sync();
    _input.inputKey(keyName);
    if (_latched.isNotEmpty) {
      clearLatched();
    }
  }

  /// Send a combination such as Ctrl+Shift+Esc in one go.
  ///
  /// Modifier names in [keyNames] are latched for the duration of the call and
  /// restored afterwards, so an in-progress latch is not disturbed.
  void combo(List<String> keyNames) {
    if (keyNames.isEmpty) return;
    final Set<String> previous = Set<String>.of(_latched);
    for (final String name in keyNames.take(keyNames.length - 1)) {
      _latched.add(name);
    }
    _sync();
    _input.inputKey(keyNames.last);
    _latched
      ..clear()
      ..addAll(previous);
    _sync();
  }

  /// Key down for keys that should repeat while held (backspace, arrows).
  void keyDown(String keyName) {
    _sync();
    _input.inputKey(keyName, down: true);
  }

  void keyUp(String keyName) {
    _input.inputKey(keyName, down: false);
  }
}
