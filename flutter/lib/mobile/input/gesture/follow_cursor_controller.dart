import 'dart:math' as math;

import 'follow_cursor_config.dart';
import 'touch_sample.dart';

/// Semantic actions produced by [FollowCursorController].
///
/// The UI layer translates these into `InputModel` calls, so this file stays
/// free of Flutter imports and can be replayed in a unit test.
enum CursorActionType {
  move,
  leftDown,
  leftUp,
  rightDown,
  rightUp,
  scroll,
  zoom,
}

class CursorAction {
  final CursorActionType type;

  /// Cursor position in view coordinates for button/move actions.
  final double x;
  final double y;

  /// Delta for [CursorActionType.scroll].
  final double dx;
  final double dy;

  /// Scale factor for [CursorActionType.zoom].
  final double scale;

  const CursorAction(
    this.type, {
    this.x = 0,
    this.y = 0,
    this.dx = 0,
    this.dy = 0,
    this.scale = 1,
  });

  @override
  String toString() =>
      'CursorAction($type, x: $x, y: $y, dx: $dx, dy: $dy, scale: $scale)';
}

/// Touch model used by the "finger follows hand" input mode.
///
/// Behaviour:
/// * one finger = move the cursor (no implicit button press);
/// * short still touch = left click, long still touch = right click;
/// * double tap then drag = left drag;
/// * pressing and holding in the left edge zone latches a right-button
///   modifier: anything the other hand does is a right-button interaction
///   until the left finger is lifted;
/// * two free fingers = scroll (and pinch for the view zoom).
class FollowCursorController {
  FollowCursorController({
    required this.screenWidth,
    required this.screenHeight,
    this.config = const FollowCursorConfig(),
  })  : cursorX = screenWidth / 2,
        cursorY = screenHeight / 2,
        targetX = screenWidth / 2,
        targetY = screenHeight / 2,
        _lastButtonX = screenWidth / 2,
        _lastButtonY = screenHeight / 2;

  double screenWidth;
  double screenHeight;
  FollowCursorConfig config;

  double cursorX;
  double cursorY;
  double targetX;
  double targetY;

  double _lastButtonX;
  double _lastButtonY;

  final List<CursorAction> _out = <CursorAction>[];
  final Map<int, _Pointer> _pointers = <int, _Pointer>{};

  int? _modifierPointerId;
  int? _leftPointerId;
  int? _rightPointerId;

  /// Pointers that act as the second finger of a two-finger gesture.
  bool _twoFinger = false;
  double _lastFocalX = 0;
  double _lastFocalY = 0;
  double _lastPinchDistance = 0;
  bool _pinchStarted = false;

  int? _pendingTapId;
  int _lastTapMs = -1000000;
  double _lastTapX = 0;
  double _lastTapY = 0;
  int? _dragPointerId;
  bool _dragStarted = false;

  bool get rightModifierActive => _modifierPointerId != null;
  bool get leftButtonDown => _leftPointerId != null;
  bool get rightButtonDown => _rightPointerId != null;
  bool get twoFingerActive => _twoFinger;

  /// Drain the actions produced since the last call.
  List<CursorAction> drainActions() {
    if (_out.isEmpty) return const <CursorAction>[];
    final List<CursorAction> result = List<CursorAction>.of(_out);
    _out.clear();
    return result;
  }

  void reset() {
    _pointers.clear();
    _out.clear();
    _modifierPointerId = null;
    _leftPointerId = null;
    _rightPointerId = null;
    _twoFinger = false;
    _pinchStarted = false;
    _pendingTapId = null;
    _dragPointerId = null;
    _dragStarted = false;
  }

  void _emit(CursorActionType type, {double x = 0, double y = 0, double dx = 0, double dy = 0, double scale = 1}) {
    _out.add(CursorAction(type, x: x, y: y, dx: dx, dy: dy, scale: scale));
  }

  bool _inLeftZone(double x) =>
      config.leftZoneRatio > 0 && x < screenWidth * config.leftZoneRatio;

  void onDown(TouchSample s) {
    if (_pointers.containsKey(s.id)) return;
    // The same contact can be delivered twice with different pointer ids; a new
    // id landing on an existing start point in the same instant is that finger,
    // not a second one, and counting it as one would arm a two-finger gesture.
    for (final _Pointer q in _pointers.values) {
      if ((s.timeMs - q.startMs).abs() <= 30 &&
          (s.x - q.startX).abs() < 8 &&
          (s.y - q.startY).abs() < 8) {
        return;
      }
    }
    // A third finger is ignored: the model only needs two.
    if (_pointers.length >= 2) return;

    final _Pointer p = _Pointer(id: s.id, startX: s.x, startY: s.y, startMs: s.timeMs);
    _pointers[s.id] = p;

    if (_modifierPointerId != null) {
      _beginRightPress(s);
      return;
    }

    if (_pointers.length == 2) {
      _beginTwoFinger();
      return;
    }

    // Single finger. A left-zone touch may become the right-button modifier,
    // so it must not drag the cursor until it proves to be a normal move.
    p.modifierCandidate = _inLeftZone(s.x);
    _pendingTapId = s.id;
    if (!p.modifierCandidate) {
      _moveTarget(s.x, s.y);
    }

    // Second touch close in time and space arms a drag, so a quick
    // double tap followed by a move becomes "press and drag".
    final bool isDoubleTap = s.timeMs - _lastTapMs <= config.doubleTapMs &&
        math.sqrt(math.pow(s.x - _lastTapX, 2) + math.pow(s.y - _lastTapY, 2)) <=
            config.doubleTapSlop;
    if (isDoubleTap) {
      _dragPointerId = s.id;
      _dragStarted = false;
      _pendingTapId = null;
      _lastTapMs = -1000000;
    }
  }

  void onMove(TouchSample s) {
    final _Pointer? p = _pointers[s.id];
    if (p == null) return;

    p.lastX = s.x;
    p.lastY = s.y;
    p.lastMs = s.timeMs;

    final double travelled =
        math.sqrt(math.pow(s.x - p.startX, 2) + math.pow(s.y - p.startY, 2));
    if (p.modifierCandidate && travelled > config.moveThreshold) {
      // Moving before the hold completes means this finger is just steering
      // the cursor, not latching the right-button modifier.
      p.modifierCandidate = false;
      p.moved = true;
      _moveTarget(s.x, s.y);
    }

    if (s.id == _modifierPointerId) {
      // The modifier finger is expected to stay put; ignore its movement.
      return;
    }

    if (s.id == _rightPointerId) {
      _moveTarget(s.x, s.y);
      return;
    }

    if (_twoFinger) {
      _updateTwoFinger();
      return;
    }

    if (p.longPressed) {
      if (!_dragStarted) {
        _dragStarted = true;
        _dragPointerId = s.id;
        _pressLeftAt(s.x, s.y);
      }
      _moveTarget(s.x, s.y);
      return;
    }

    if (s.id == _dragPointerId) {
      if (!_dragStarted && travelled > config.moveThreshold) {
        _dragStarted = true;
        _pressLeftAt(s.x, s.y);
      }
      if (_dragStarted) {
        _moveTarget(s.x, s.y);
      }
      return;
    }

    p.moved = p.moved || travelled > config.moveThreshold;
    _moveTarget(s.x, s.y);
  }

  void onUp(TouchSample s) {
    final _Pointer? p = _pointers.remove(s.id);
    if (p == null) return;

    if (s.id == _modifierPointerId) {
      _modifierPointerId = null;
      _releaseRightIfNeeded();
      _twoFinger = false;
      return;
    }

    if (s.id == _rightPointerId) {
      _releaseRightIfNeeded();
      return;
    }

    if (s.id == _dragPointerId) {
      if (_dragStarted) {
        _releaseLeftIfNeeded();
      } else {
        // Second tap of a double tap without movement: send the second click.
        _emit(CursorActionType.leftDown, x: s.x, y: s.y);
        _emit(CursorActionType.leftUp, x: s.x, y: s.y);
      }
      _dragPointerId = null;
      _dragStarted = false;
      return;
    }

    // A two-finger gesture only owns the lift while a second finger is still
    // down. Otherwise a stale flag would swallow every later single-finger tap.
    if (_twoFinger && _pointers.length >= 2) {
      return;
    }
    _twoFinger = false;
    _pinchStarted = false;

    // A long press that never dragged is a right click at the touched point.
    if (p.longPressed) {
      _pendingTapId = null;
      _lastButtonX = p.lastX;
      _lastButtonY = p.lastY;
      _emit(CursorActionType.rightDown, x: p.lastX, y: p.lastY);
      _emit(CursorActionType.rightUp, x: p.lastX, y: p.lastY);
      return;
    }

    if (_pendingTapId == s.id) {
      _pendingTapId = null;
      final bool still = !p.moved;
      final bool quick = s.timeMs - p.startMs <= config.tapMaxMs;
      if (still && quick) {
        _handleTap(s);
      }
    }
  }

  void onCancel(int id) {
    _pointers.remove(id);
    if (_pointers.length < 2) {
      // A cancel can leave only one finger behind; without this the next
      // single-finger move would keep hitting the two-finger branch and the
      // cursor would stop following until a fresh two-finger gesture.
      _twoFinger = false;
      _pinchStarted = false;
      _lastPinchDistance = 0;
    }
    if (id == _modifierPointerId) {
      _modifierPointerId = null;
      _releaseRightIfNeeded();
    }
    if (id == _rightPointerId) {
      _releaseRightIfNeeded();
    }
    if (id == _dragPointerId) {
      _releaseLeftIfNeeded();
      _dragPointerId = null;
      _dragStarted = false;
    }
  }

  /// Advance the smoothing and the long-press timers.
  ///
  /// [nowMs] must come from the same clock as [TouchSample.timeMs].
  void tick(int nowMs, double dtMs) {
    // Modifier hold must win the race against the long-press timer: a dropped
    // frame would otherwise satisfy both and fire a right click as well.
    _checkModifierHold(nowMs);
    _checkLongPress(nowMs);

    if (dtMs <= 0) return;
    final double tau = math.max(config.smoothingTauMs, 1);
    final double alpha = 1 - math.exp(-dtMs / tau);
    final double nx = cursorX + (targetX - cursorX) * alpha;
    final double ny = cursorY + (targetY - cursorY) * alpha;
    if ((nx - cursorX).abs() > 0.05 || (ny - cursorY).abs() > 0.05) {
      cursorX = nx;
      cursorY = ny;
      _emit(CursorActionType.move, x: cursorX, y: cursorY);
    }
  }

  void _checkModifierHold(int nowMs) {
    if (_modifierPointerId != null) return;
    for (final _Pointer p in _pointers.values) {
      if (!p.modifierCandidate || p.moved) continue;
      if (nowMs - p.startMs >= config.modifierHoldMs) {
        p.modifierCandidate = false;
        _modifierPointerId = p.id;
        _pendingTapId = null;
        _twoFinger = false;
        return;
      }
    }
  }

  void _checkLongPress(int nowMs) {
    if (_modifierPointerId != null) return;
    if (_pendingTapId == null) return;
    final _Pointer? p = _pointers[_pendingTapId];
    if (p == null || p.moved || p.longPressed) return;
    if (nowMs - p.startMs < config.longPressMs) return;
    // Resolved on release: holding still is a right click, while dragging past
    // the threshold holds the left button so text can be selected as with a
    // real mouse.
    p.longPressed = true;
  }

  void _handleTap(TouchSample s) {
    _lastTapMs = s.timeMs;
    _lastTapX = s.x;
    _lastTapY = s.y;
    _lastButtonX = s.x;
    _lastButtonY = s.y;
    _emit(CursorActionType.leftDown, x: s.x, y: s.y);
    _emit(CursorActionType.leftUp, x: s.x, y: s.y);
  }

  void _beginRightPress(TouchSample s) {
    _lastButtonX = s.x;
    _lastButtonY = s.y;
    _rightPointerId = s.id;
    _moveTarget(s.x, s.y);
    _emit(CursorActionType.rightDown, x: s.x, y: s.y);
  }

  void _releaseRightIfNeeded() {
    if (_rightPointerId == null) return;
    _rightPointerId = null;
    _emit(CursorActionType.rightUp, x: _lastButtonX, y: _lastButtonY);
  }

  void _pressLeftAt(double x, double y) {
    _lastButtonX = x;
    _lastButtonY = y;
    _leftPointerId = 1;
    _emit(CursorActionType.leftDown, x: x, y: y);
  }

  void _releaseLeftIfNeeded() {
    if (_leftPointerId == null) return;
    _leftPointerId = null;
    _emit(CursorActionType.leftUp, x: _lastButtonX, y: _lastButtonY);
  }

  void _moveTarget(double x, double y) {
    targetX = x.clamp(0.0, screenWidth);
    targetY = y.clamp(0.0, screenHeight);
    _lastButtonX = targetX;
    _lastButtonY = targetY;
  }

  void _beginTwoFinger() {
    _pendingTapId = null;
    final List<_Pointer> ps = _pointers.values.toList();
    if (ps.length < 2) return;
    // A second finger during a drag must not leave the left button latched.
    _releaseLeftIfNeeded();
    _dragPointerId = null;
    _dragStarted = false;
    for (final _Pointer p in ps) {
      p.modifierCandidate = false;
    }
    _twoFinger = true;
    _lastFocalX = (ps[0].lastX + ps[1].lastX) / 2;
    _lastFocalY = (ps[0].lastY + ps[1].lastY) / 2;
    _lastPinchDistance = math.sqrt(math.pow(ps[0].lastX - ps[1].lastX, 2) +
        math.pow(ps[0].lastY - ps[1].lastY, 2));
    _pinchStarted = false;
  }

  void _updateTwoFinger() {
    if (_pointers.length < 2) return;
    final List<_Pointer> ps = _pointers.values.toList();
    final double focalX = (ps[0].lastX + ps[1].lastX) / 2;
    final double focalY = (ps[0].lastY + ps[1].lastY) / 2;
    final double distance = math.sqrt(math.pow(ps[0].lastX - ps[1].lastX, 2) +
        math.pow(ps[0].lastY - ps[1].lastY, 2));

    // Spacing that changes more than the focal point travels is a pinch; the
    // other way round the fingers are travelling together, which is a scroll.
    // Comparing the two keeps this independent of how far the view is zoomed.
    final double dDist = (distance - _lastPinchDistance).abs();
    final double dFocal = math.sqrt(math.pow(focalX - _lastFocalX, 2) +
        math.pow(focalY - _lastFocalY, 2));
    if (_lastPinchDistance > 0 && dDist > math.max(8, dFocal)) {
      // Pinch takes priority over scrolling once the distance changes.
      _pinchStarted = true;
      _emit(CursorActionType.zoom,
          x: focalX, y: focalY, scale: distance / _lastPinchDistance);
      _lastPinchDistance = distance;
      _lastFocalX = focalX;
      _lastFocalY = focalY;
      return;
    }

    if (_pinchStarted) {
      _lastPinchDistance = distance;
      _lastFocalX = focalX;
      _lastFocalY = focalY;
      return;
    }

    final double dx = (focalX - _lastFocalX) * config.scrollScale;
    final double dy = (focalY - _lastFocalY) * config.scrollScale;
    if (dx.abs() > 0.5 || dy.abs() > 0.5) {
      _emit(CursorActionType.scroll, dx: dx, dy: dy);
    }
    _lastFocalX = focalX;
    _lastFocalY = focalY;
    _lastPinchDistance = distance;
  }
}

class _Pointer {
  _Pointer({
    required this.id,
    required this.startX,
    required this.startY,
    required this.startMs,
  })  : lastX = startX,
        lastY = startY,
        lastMs = startMs;

  final int id;
  final double startX;
  final double startY;
  final int startMs;

  double lastX;
  double lastY;
  int lastMs;

  bool moved = false;
  bool modifierCandidate = false;
  bool longPressed = false;
}
