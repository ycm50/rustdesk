import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import 'package:flutter_hbb/models/input_model.dart';
import 'package:flutter_hbb/models/model.dart';

import 'follow_cursor_config.dart';
import 'follow_cursor_controller.dart';
import 'touch_sample.dart';

/// Routes raw pointer events into [FollowCursorController] and paints the
/// local virtual cursor.
///
/// Raw [Listener] events are used on purpose: `GestureDetector` runs its
/// recognizers through the gesture arena, which delays the first movement of a
/// pan by a couple of frames. For a cursor that must feel attached to the
/// finger, that delay is the difference between "follows my hand" and "lags".
class FollowCursorLayer extends StatefulWidget {
  const FollowCursorLayer({
    super.key,
    required this.child,
    required this.ffi,
    required this.enabled,
    this.config = const FollowCursorConfig(),
    this.bottomInset = 0,
  });

  final Widget child;
  final FFI ffi;
  final bool enabled;
  final FollowCursorConfig config;

  /// Height reserved at the bottom by another input surface (keyboard panel).
  /// Touches there belong to that surface and must not drive the cursor.
  final double bottomInset;

  @override
  State<FollowCursorLayer> createState() => _FollowCursorLayerState();
}

class _FollowCursorLayerState extends State<FollowCursorLayer>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final GlobalKey _sizeKey = GlobalKey();
  FollowCursorController? _controller;
  Size _size = Size.zero;
  Duration _lastElapsed = Duration.zero;
  double _cursorX = 0;
  double _cursorY = 0;
  bool _cursorVisible = false;
  bool _hiddenByTwoFinger = false;

  InputModel get _inputModel => widget.ffi.inputModel;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_onTick)..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  FollowCursorController? _ensureController() {
    final Size size = _size;
    if (size.width <= 1 || size.height <= 1) return null;
    final FollowCursorController? existing = _controller;
    if (existing != null) {
      existing.screenWidth = size.width;
      existing.screenHeight = size.height;
      existing.config = widget.config;
      return existing;
    }
    final FollowCursorController created = FollowCursorController(
      screenWidth: size.width,
      screenHeight: size.height,
      config: widget.config,
    );
    _controller = created;
    _cursorX = created.cursorX;
    _cursorY = created.cursorY;
    return created;
  }

  int _nowMs() => DateTime.now().millisecondsSinceEpoch;

  void _onTick(Duration elapsed) {
    final FollowCursorController? c = _ensureController();
    if (c == null) return;

    final double dtMs =
        (elapsed - _lastElapsed).inMicroseconds / 1000.0;
    _lastElapsed = elapsed;
    if (dtMs <= 0) return;
    c.tick(_nowMs(), dtMs.clamp(1.0, 64.0));
    _flush();
  }

  void _flush() {
    final FollowCursorController? c = _controller;
    if (c == null) return;
    final List<CursorAction> actions = c.drainActions();
    if (actions.isEmpty) return;
    for (final CursorAction a in actions) {
      _apply(a);
    }
    if (mounted) {
      setState(() {
        _cursorX = c.cursorX;
        _cursorY = c.cursorY;
      });
    }
  }

  void _apply(CursorAction a) {

    switch (a.type) {
      case CursorActionType.move:
        unawaited(widget.ffi.cursorModel.move(a.x, a.y));
      case CursorActionType.leftDown:
        unawaited(widget.ffi.cursorModel.move(a.x, a.y));
        unawaited(_inputModel.sendMouse('down', MouseButtons.left));
      case CursorActionType.leftUp:
        unawaited(_inputModel.sendMouse('up', MouseButtons.left));
      case CursorActionType.rightDown:
        unawaited(widget.ffi.cursorModel.move(a.x, a.y));
        unawaited(_inputModel.sendMouse('down', MouseButtons.right));
      case CursorActionType.rightUp:
        unawaited(_inputModel.sendMouse('up', MouseButtons.right));
      case CursorActionType.scroll:
        // inputModel.scroll(+1) scrolls up, matching the wheel-up button.
        unawaited(_inputModel.scroll(a.dy.round()));
      case CursorActionType.zoom:
        // The canvas model expects the focal point the way the stock scale
        // gesture reports it, i.e. in global coordinates.
        final RenderBox? zbox =
            _sizeKey.currentContext?.findRenderObject() as RenderBox?;
        final Offset focal = zbox == null
            ? Offset(a.x, a.y)
            : zbox.localToGlobal(Offset(a.x, a.y));
        unawaited(widget.ffi.canvasModel.updateScale(a.scale, focal));
      }
  }

  void _handle(PointerEvent e) {
    final bool isRelease = e is PointerUpEvent || e is PointerCancelEvent;
    if (!widget.enabled && !isRelease) return;
    final FollowCursorController? c = _ensureController();
    if (c == null) return;
    final RenderBox? box =
        _sizeKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null) return;
    final Offset local = box.globalToLocal(e.position);
    // The inset only guards gesture starts. A finger that begins on the canvas
    // and lifts over the panel must still deliver up/cancel, otherwise the
    // remote left button stays latched and the pointer id leaks.
    if (!isRelease &&
        widget.bottomInset > 0 &&
        local.dy > box.size.height - widget.bottomInset) {
      return;
    }
    final TouchSample sample = TouchSample(
      id: e.pointer,
      x: local.dx,
      y: local.dy,
      timeMs: _nowMs(),
    );
    // Flutter delivers transformed pointer events whose runtime type is not the
    // base class, so match with `is` instead of comparing runtimeType.
    if (e is PointerDownEvent) {
      _cursorVisible = true;
      c.onDown(sample);
    } else if (e is PointerMoveEvent) {
      c.onMove(sample);
    } else if (e is PointerUpEvent) {
      c.onUp(sample);
    } else if (e is PointerCancelEvent) {
      c.onCancel(sample.id);
    }
    // Two-finger gestures drive the view, not the cursor: hide the drawn
    // pointer while they run and bring it back once they end.
    if (c.twoFingerActive) {
      if (_cursorVisible) {
        _cursorVisible = false;
        _hiddenByTwoFinger = true;
      }
    } else if (_hiddenByTwoFinger) {
      _hiddenByTwoFinger = false;
      _cursorVisible = true;
      if (mounted) setState(() {});
    }
    _flush();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        _size = Size(constraints.maxWidth, constraints.maxHeight);
        _ensureController();
        return Listener(
          behavior: HitTestBehavior.translucent,
          onPointerDown: _handle,
          onPointerMove: _handle,
          onPointerUp: _handle,
          onPointerCancel: _handle,
          child: Stack(
            fit: StackFit.expand,
            children: <Widget>[
              KeyedSubtree(key: _sizeKey, child: widget.child),
              if (widget.enabled && widget.config.showCursor)
                IgnorePointer(
                  child: CustomPaint(
                    painter: _CursorPainter(
                      x: _cursorX,
                      y: _cursorY,
                      visible: _cursorVisible,
                      rightMode: _controller?.rightModifierActive ?? false,
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

class _CursorPainter extends CustomPainter {
  _CursorPainter({
    required this.x,
    required this.y,
    required this.visible,
    required this.rightMode,
  });

  final double x;
  final double y;
  final bool visible;
  final bool rightMode;

  @override
  void paint(Canvas canvas, Size size) {
    if (!visible) return;
    final Offset p = Offset(
      x.clamp(0.0, size.width),
      y.clamp(0.0, size.height),
    );
    final Paint ring = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..color = rightMode ? const Color(0xFFE53935) : Colors.white;
    final Paint fill = Paint()
      ..color = rightMode ? const Color(0x66E53935) : const Color(0x66FFFFFF);
    canvas.drawCircle(p, rightMode ? 16 : 12, ring);
    canvas.drawCircle(p, rightMode ? 3 : 2, fill);
  }

  @override
  bool shouldRepaint(_CursorPainter old) =>
      old.x != x || old.y != y || old.visible != visible || old.rightMode != rightMode;
}
