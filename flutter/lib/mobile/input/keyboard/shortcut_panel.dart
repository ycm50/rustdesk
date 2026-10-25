import 'package:flutter/material.dart';

import 'key_dispatcher.dart';

/// One shortcut entry: a label plus the key sequence it sends.
class ShortcutDef {
  const ShortcutDef(this.label, this.keys, {this.group = 'general'});

  final String label;
  final List<String> keys;
  final String group;
}

const List<ShortcutDef> kDefaultShortcuts = <ShortcutDef>[
  ShortcutDef('Copy', <String>['VK_CONTROL', 'VK_C']),
  ShortcutDef('Paste', <String>['VK_CONTROL', 'VK_V']),
  ShortcutDef('Cut', <String>['VK_CONTROL', 'VK_X']),
  ShortcutDef('Undo', <String>['VK_CONTROL', 'VK_Z']),
  ShortcutDef('Redo', <String>['VK_CONTROL', 'VK_Y']),
  ShortcutDef('Select all', <String>['VK_CONTROL', 'VK_A']),
  ShortcutDef('Save', <String>['VK_CONTROL', 'VK_S']),
  ShortcutDef('Find', <String>['VK_CONTROL', 'VK_F']),
  ShortcutDef('Alt+Tab', <String>['VK_MENU', 'VK_TAB']),
  ShortcutDef('Alt+F4', <String>['VK_MENU', 'VK_F4']),
  ShortcutDef('Win', <String>['VK_LWIN']),
  ShortcutDef('Win+D', <String>['VK_LWIN', 'VK_D']),
  ShortcutDef('Win+E', <String>['VK_LWIN', 'VK_E']),
  ShortcutDef('Task manager', <String>['VK_CONTROL', 'VK_SHIFT', 'VK_ESCAPE']),
  ShortcutDef('Esc', <String>['VK_ESCAPE']),
  ShortcutDef('Enter', <String>['VK_RETURN']),
  ShortcutDef('Backspace', <String>['VK_BACK']),
  ShortcutDef('Delete', <String>['VK_DELETE']),
  ShortcutDef('Print screen', <String>['VK_SNAPSHOT']),
  ShortcutDef('Page up', <String>['VK_PRIOR']),
  ShortcutDef('Page down', <String>['VK_NEXT']),
  ShortcutDef('Home', <String>['VK_HOME']),
  ShortcutDef('End', <String>['VK_END']),
  ShortcutDef('F5', <String>['VK_F5']),
  ShortcutDef('F11', <String>['VK_F11']),
  ShortcutDef('Ctrl+Wheel up', <String>['VK_CONTROL', 'VK_UP']),
  ShortcutDef('Ctrl+Wheel down', <String>['VK_CONTROL', 'VK_DOWN']),
];

/// Grid of tappable shortcut buttons.
class ShortcutPanel extends StatelessWidget {
  const ShortcutPanel({
    super.key,
    required this.dispatcher,
    this.shortcuts = kDefaultShortcuts,
    this.onLongPress,
  });

  final KeyDispatcher dispatcher;
  final List<ShortcutDef> shortcuts;
  final void Function(ShortcutDef def)? onLongPress;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: const Color(0xFF1F1F23),
      padding: const EdgeInsets.all(6),
      child: GridView.builder(
        padding: EdgeInsets.zero,
        gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: 108,
          mainAxisExtent: 40,
          crossAxisSpacing: 6,
          mainAxisSpacing: 6,
        ),
        itemCount: shortcuts.length,
        itemBuilder: (BuildContext context, int index) {
          final ShortcutDef def = shortcuts[index];
          return Material(
            color: const Color(0xFF2E2E33),
            borderRadius: BorderRadius.circular(6),
            child: InkWell(
              borderRadius: BorderRadius.circular(6),
              onTap: () => dispatcher.combo(def.keys),
              onLongPress:
                  onLongPress == null ? null : () => onLongPress!(def),
              child: Center(
                child: Text(
                  def.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: Colors.white, fontSize: 12),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
