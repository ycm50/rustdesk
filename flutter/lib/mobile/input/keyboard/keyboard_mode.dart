/// Which of the three keyboard surfaces is shown at the bottom of a session.
///
/// The three modes are exclusive: an IME surface and a synthetic PC keyboard
/// cannot share the screen without stealing each other's touch targets.
enum RcKeyboardMode {
  /// Only the system input method, for typing text into the remote side.
  ime,

  /// A grid of shortcut buttons (Ctrl+C, Alt+Tab, ...).
  shortcuts,

  /// A full on-screen PC keyboard that emits real key codes.
  pc,
}

RcKeyboardMode parseRcKeyboardMode(String? raw) {
  switch (raw) {
    case 'ime':
      return RcKeyboardMode.ime;
    case 'shortcuts':
      return RcKeyboardMode.shortcuts;
    default:
      return RcKeyboardMode.pc;
  }
}

String rcKeyboardModeName(RcKeyboardMode mode) {
  switch (mode) {
    case RcKeyboardMode.ime:
      return 'ime';
    case RcKeyboardMode.shortcuts:
      return 'shortcuts';
    case RcKeyboardMode.pc:
      return 'pc';
  }
}
