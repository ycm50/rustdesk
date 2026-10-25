/// Key descriptions for the on-screen PC keyboard.
///
/// Keys are described by their RustDesk key name (`VK_*`), the same identifier
/// the host side understands for `sessionInputKey`. Character keys are sent as
/// key codes rather than text so that combinations such as Ctrl+C keep working.
enum KeyCapKind { character, modifier, function, action }

class KeyCap {
  const KeyCap({
    required this.label,
    required this.keyName,
    this.flex = 1.0,
    this.kind = KeyCapKind.character,
    this.shiftedLabel,
    this.shiftedKeyName,
    this.icon,
  });

  /// Text drawn on the cap, already localized to the US layout.
  final String label;

  /// RustDesk key name, e.g. `VK_A`.
  final String keyName;

  /// Layout weight, used to size wide keys such as Shift and Space.
  final double flex;

  final KeyCapKind kind;

  /// Label and key name emitted while Shift is latched.
  final String? shiftedLabel;
  final String? shiftedKeyName;

  /// Optional icon name handled by the widget layer.
  final String? icon;

  KeyCap get effective =>
      shiftedLabel == null || shiftedKeyName == null || shiftedLabel!.isEmpty
          ? this
          : KeyCap(
              label: shiftedLabel!,
              keyName: shiftedKeyName!,
              flex: flex,
              kind: kind,
              icon: icon,
            );
}

const KeyCap kBackspace =
    KeyCap(label: '⌫', keyName: 'VK_BACK', flex: 1.6, kind: KeyCapKind.action);
const KeyCap kTab =
    KeyCap(label: 'Tab', keyName: 'VK_TAB', flex: 1.4, kind: KeyCapKind.action);
const KeyCap kEnter =
    KeyCap(label: 'Enter', keyName: 'VK_RETURN', flex: 1.8, kind: KeyCapKind.action);
const KeyCap kShiftL =
    KeyCap(label: 'Shift', keyName: 'VK_SHIFT', flex: 1.8, kind: KeyCapKind.modifier);
const KeyCap kShiftR =
    KeyCap(label: 'Shift', keyName: 'VK_SHIFT', flex: 1.8, kind: KeyCapKind.modifier);
const KeyCap kCtrl =
    KeyCap(label: 'Ctrl', keyName: 'VK_CONTROL', flex: 1.2, kind: KeyCapKind.modifier);
const KeyCap kAlt =
    KeyCap(label: 'Alt', keyName: 'VK_MENU', flex: 1.2, kind: KeyCapKind.modifier);
const KeyCap kWin =
    KeyCap(label: '⊞', keyName: 'VK_LWIN', flex: 1.2, kind: KeyCapKind.modifier);
const KeyCap kSpace =
    KeyCap(label: 'Space', keyName: 'VK_SPACE', flex: 6.0, kind: KeyCapKind.action);
const KeyCap kEsc =
    KeyCap(label: 'Esc', keyName: 'VK_ESCAPE', flex: 1.0, kind: KeyCapKind.action);

KeyCap _ch(String label, String keyName, {String? shiftedLabel, String? shiftedKeyName}) =>
    KeyCap(
      label: label,
      keyName: keyName,
      shiftedLabel: shiftedLabel,
      shiftedKeyName: shiftedKeyName,
    );

/// 60% keyboard: five rows, no function row and no numpad.
List<List<KeyCap>> get kPcKeyboardRows => <List<KeyCap>>[
      <KeyCap>[
        kEsc,
        _ch('1', 'VK_1', shiftedLabel: '!'),
        _ch('2', 'VK_2', shiftedLabel: '@'),
        _ch('3', 'VK_3', shiftedLabel: '#'),
        _ch('4', 'VK_4', shiftedLabel: '\$'),
        _ch('5', 'VK_5', shiftedLabel: '%'),
        _ch('6', 'VK_6', shiftedLabel: '^'),
        _ch('7', 'VK_7', shiftedLabel: '&'),
        _ch('8', 'VK_8', shiftedLabel: '*'),
        _ch('9', 'VK_9', shiftedLabel: '('),
        _ch('0', 'VK_0', shiftedLabel: ')'),
        _ch('-', 'VK_MINUS', shiftedLabel: '_'),
        _ch('=', 'VK_EQUAL', shiftedLabel: '+'),
        kBackspace,
      ],
      <KeyCap>[
        kTab,
        _ch('Q', 'VK_Q'),
        _ch('W', 'VK_W'),
        _ch('E', 'VK_E'),
        _ch('R', 'VK_R'),
        _ch('T', 'VK_T'),
        _ch('Y', 'VK_Y'),
        _ch('U', 'VK_U'),
        _ch('I', 'VK_I'),
        _ch('O', 'VK_O'),
        _ch('P', 'VK_P'),
        _ch('[', 'VK_LBRACKET', shiftedLabel: '{'),
        _ch(']', 'VK_RBRACKET', shiftedLabel: '}'),
        _ch('\\', 'VK_BACKSLASH', shiftedLabel: '|'),
      ],
      <KeyCap>[
        KeyCap(label: 'Caps', keyName: 'VK_CAPITAL', flex: 1.8, kind: KeyCapKind.modifier),
        _ch('A', 'VK_A'),
        _ch('S', 'VK_S'),
        _ch('D', 'VK_D'),
        _ch('F', 'VK_F'),
        _ch('G', 'VK_G'),
        _ch('H', 'VK_H'),
        _ch('J', 'VK_J'),
        _ch('K', 'VK_K'),
        _ch('L', 'VK_L'),
        _ch(';', 'VK_SEMICOLON', shiftedLabel: ':'),
        _ch("'", 'VK_APOSTROPHE', shiftedLabel: '"'),
        kEnter,
      ],
      <KeyCap>[
        kShiftL,
        _ch('Z', 'VK_Z'),
        _ch('X', 'VK_X'),
        _ch('C', 'VK_C'),
        _ch('V', 'VK_V'),
        _ch('B', 'VK_B'),
        _ch('N', 'VK_N'),
        _ch('M', 'VK_M'),
        _ch(',', 'VK_COMMA', shiftedLabel: '<'),
        _ch('.', 'VK_PERIOD', shiftedLabel: '>'),
        _ch('/', 'VK_SLASH', shiftedLabel: '?'),
        kShiftR,
      ],
      <KeyCap>[
        kCtrl,
        kWin,
        kAlt,
        kSpace,
        kAlt,
        kCtrl,
        KeyCap(label: '←', keyName: 'VK_LEFT', kind: KeyCapKind.action),
        KeyCap(label: '↑', keyName: 'VK_UP', kind: KeyCapKind.action),
        KeyCap(label: '↓', keyName: 'VK_DOWN', kind: KeyCapKind.action),
        KeyCap(label: '→', keyName: 'VK_RIGHT', kind: KeyCapKind.action),
      ],
    ];

/// Function row, shown above the main block when the user expands it.
List<KeyCap> get kPcKeyboardFunctionRow => <KeyCap>[
      for (int i = 1; i <= 12; i++)
        KeyCap(
          label: 'F$i',
          keyName: 'VK_F$i',
          flex: 1.0,
          kind: KeyCapKind.function,
        ),
      KeyCap(label: 'Del', keyName: 'VK_DELETE', kind: KeyCapKind.action),
      KeyCap(label: 'Home', keyName: 'VK_HOME', kind: KeyCapKind.action),
      KeyCap(label: 'End', keyName: 'VK_END', kind: KeyCapKind.action),
    ];
