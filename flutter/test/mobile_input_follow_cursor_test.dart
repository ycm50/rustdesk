import 'package:flutter_hbb/mobile/input/gesture/follow_cursor_config.dart';
import 'package:flutter_hbb/mobile/input/gesture/follow_cursor_controller.dart';
import 'package:flutter_hbb/mobile/input/gesture/touch_sample.dart';
import 'package:flutter_test/flutter_test.dart';

FollowCursorController _controller() => FollowCursorController(
      screenWidth: 400,
      screenHeight: 800,
      config: const FollowCursorConfig(),
    );

List<CursorActionType> _buttons(List<CursorAction> actions) => actions
    .map((CursorAction e) => e.type)
    .where((CursorActionType t) => t != CursorActionType.move)
    .toList();

void main() {
  test('single tap sends a left click at the touched point', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.onUp(const TouchSample(id: 1, x: 100, y: 200, timeMs: 60));

    final List<CursorAction> actions = c.drainActions();
    expect(_buttons(actions),
        <CursorActionType>[CursorActionType.leftDown, CursorActionType.leftUp]);
    expect(actions.first.x, 100);
    expect(actions.first.y, 200);
  });

  test('moving one finger only moves the cursor', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.onMove(const TouchSample(id: 1, x: 160, y: 260, timeMs: 16));
    c.onUp(const TouchSample(id: 1, x: 160, y: 260, timeMs: 32));

    expect(_buttons(c.drainActions()), isEmpty);
    expect(c.targetX, 160);
    expect(c.targetY, 260);
  });

  test('holding one finger still is a right click', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.tick(520, 16);
    expect(_buttons(c.drainActions()), isEmpty);

    c.onUp(const TouchSample(id: 1, x: 100, y: 200, timeMs: 540));
    expect(_buttons(c.drainActions()),
        <CursorActionType>[CursorActionType.rightDown, CursorActionType.rightUp]);
  });

  test('a duplicate contact is not a second finger', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.onDown(const TouchSample(id: 2, x: 100, y: 200, timeMs: 5));
    c.onUp(const TouchSample(id: 2, x: 100, y: 200, timeMs: 20));
    c.onUp(const TouchSample(id: 1, x: 100, y: 200, timeMs: 40));

    expect(_buttons(c.drainActions()), <CursorActionType>[
      CursorActionType.leftDown,
      CursorActionType.leftUp,
    ]);
  });

  test('long press then drag holds the left button for text selection', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.tick(520, 16);
    c.onMove(const TouchSample(id: 1, x: 160, y: 200, timeMs: 560));
    c.onMove(const TouchSample(id: 1, x: 220, y: 200, timeMs: 600));
    c.onUp(const TouchSample(id: 1, x: 220, y: 200, timeMs: 620));

    expect(_buttons(c.drainActions()), <CursorActionType>[
      CursorActionType.leftDown,
      CursorActionType.leftUp,
    ]);
  });

  test('holding the left zone turns the other hand into the right button', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 20, y: 400, timeMs: 0));
    c.tick(200, 16);
    expect(c.rightModifierActive, isTrue);

    c.onDown(const TouchSample(id: 2, x: 200, y: 300, timeMs: 220));
    c.onMove(const TouchSample(id: 2, x: 260, y: 340, timeMs: 240));
    c.onUp(const TouchSample(id: 2, x: 260, y: 340, timeMs: 260));
    c.onUp(const TouchSample(id: 1, x: 20, y: 400, timeMs: 300));

    expect(_buttons(c.drainActions()),
        <CursorActionType>[CursorActionType.rightDown, CursorActionType.rightUp]);
    expect(c.rightModifierActive, isFalse);
  });

  test('moving early inside the left zone is a plain move, not the modifier', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 20, y: 400, timeMs: 0));
    c.onMove(const TouchSample(id: 1, x: 60, y: 400, timeMs: 30));
    c.tick(400, 16);
    c.onUp(const TouchSample(id: 1, x: 60, y: 400, timeMs: 420));

    expect(c.rightModifierActive, isFalse);
    expect(_buttons(c.drainActions()), isEmpty);
  });

  test('two free fingers scroll', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 400, timeMs: 0));
    c.onDown(const TouchSample(id: 2, x: 200, y: 400, timeMs: 10));
    c.onMove(const TouchSample(id: 1, x: 100, y: 430, timeMs: 20));
    c.onMove(const TouchSample(id: 2, x: 200, y: 430, timeMs: 30));

    final List<CursorAction> scrolls = c
        .drainActions()
        .where((CursorAction a) => a.type == CursorActionType.scroll)
        .toList();
    expect(scrolls, isNotEmpty);
    expect(scrolls.fold<double>(0, (double s, CursorAction a) => s + a.dy),
        greaterThan(0));
  });

  test('a single-finger tap still clicks after a two-finger gesture', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 400, timeMs: 0));
    c.onDown(const TouchSample(id: 2, x: 200, y: 400, timeMs: 10));
    c.onMove(const TouchSample(id: 1, x: 100, y: 430, timeMs: 20));
    c.onMove(const TouchSample(id: 2, x: 200, y: 430, timeMs: 30));
    c.onUp(const TouchSample(id: 1, x: 100, y: 430, timeMs: 40));
    c.onUp(const TouchSample(id: 2, x: 200, y: 430, timeMs: 50));
    c.drainActions();

    c.onDown(const TouchSample(id: 3, x: 120, y: 300, timeMs: 2000));
    c.onUp(const TouchSample(id: 3, x: 120, y: 300, timeMs: 2050));

    expect(_buttons(c.drainActions()), <CursorActionType>[
      CursorActionType.leftDown,
      CursorActionType.leftUp,
    ]);
  });

  test('double tap followed by a move drags with the left button', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.onUp(const TouchSample(id: 1, x: 100, y: 200, timeMs: 50));
    c.onDown(const TouchSample(id: 1, x: 102, y: 201, timeMs: 100));
    c.onMove(const TouchSample(id: 1, x: 150, y: 201, timeMs: 140));
    c.onUp(const TouchSample(id: 1, x: 150, y: 201, timeMs: 180));

    expect(_buttons(c.drainActions()), <CursorActionType>[
      CursorActionType.leftDown,
      CursorActionType.leftUp,
      CursorActionType.leftDown,
      CursorActionType.leftUp,
    ]);
  });

  test('a cancelled second finger does not leave the gesture in two-finger mode',
      () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.onDown(const TouchSample(id: 2, x: 200, y: 300, timeMs: 10));
    c.onCancel(2);
    c.onMove(const TouchSample(id: 1, x: 150, y: 260, timeMs: 40));
    c.onUp(const TouchSample(id: 1, x: 150, y: 260, timeMs: 60));

    expect(c.targetX, 150);
    expect(c.targetY, 260);
    expect(_buttons(c.drainActions()), isEmpty);
  });

  test('a second finger during a drag releases the left button', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 200, timeMs: 0));
    c.onUp(const TouchSample(id: 1, x: 100, y: 200, timeMs: 50));
    c.onDown(const TouchSample(id: 1, x: 102, y: 201, timeMs: 100));
    c.onMove(const TouchSample(id: 1, x: 150, y: 201, timeMs: 140));
    c.drainActions();

    c.onDown(const TouchSample(id: 2, x: 220, y: 420, timeMs: 160));
    c.drainActions();

    expect(c.leftButtonDown, isFalse);
  });

  test('small pinch jitter still allows the gesture to scroll', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 400, timeMs: 0));
    c.onDown(const TouchSample(id: 2, x: 200, y: 400, timeMs: 10));
    // 7px of spacing jitter, as a real two-finger landing produces.
    c.onMove(const TouchSample(id: 1, x: 100, y: 407, timeMs: 20));
    c.drainActions();
    c.onMove(const TouchSample(id: 1, x: 100, y: 447, timeMs: 40));
    c.onMove(const TouchSample(id: 2, x: 200, y: 447, timeMs: 50));

    final List<CursorAction> scrolls = c
        .drainActions()
        .where((CursorAction a) => a.type == CursorActionType.scroll)
        .toList();
    expect(scrolls, isNotEmpty);
  });

  test('pinching reports the zoom ratio at the pinch focal point', () {
    final FollowCursorController c = _controller();
    c.onDown(const TouchSample(id: 1, x: 100, y: 400, timeMs: 0));
    c.onDown(const TouchSample(id: 2, x: 300, y: 400, timeMs: 10));
    c.onMove(const TouchSample(id: 1, x: 60, y: 400, timeMs: 20));
    c.onMove(const TouchSample(id: 2, x: 340, y: 400, timeMs: 30));

    final List<CursorAction> zooms = c
        .drainActions()
        .where((CursorAction a) => a.type == CursorActionType.zoom)
        .toList();
    expect(zooms, isNotEmpty);
    expect(zooms.first.scale, greaterThan(1));
    // Focal point of the update that reports the zoom: finger 1 moved first.
    expect(zooms.first.x, 180);
    expect(zooms.first.y, 400);
  });
}
