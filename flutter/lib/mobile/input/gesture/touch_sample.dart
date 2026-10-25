/// 与 Flutter 解耦的触点采样。
///
/// 之所以不复用 `dart:ui` 的 `PointerEvent`：
/// 1. 手势状态机需要能被纯 Dart 单测直接驱动（几十毫秒跑完一整段手势回放）；
/// 2. 单元测试里构造多指事件序列比构造 Flutter 事件管道简单得多。
///
/// UI 侧只需要把 `PointerEvent` 映射成 `TouchSample` 即可。
class TouchSample {
  /// 系统分配的指针 id，用来跟踪同一根手指的 down / move / up。
  final int id;

  /// 逻辑坐标（dp），屏幕左上角为原点。
  final double x;
  final double y;

  /// 毫秒时间戳，单调递增。由调用方注入，保证测试可复现。
  final int timeMs;

  const TouchSample({
    required this.id,
    required this.x,
    required this.y,
    required this.timeMs,
  });

  @override
  String toString() => 'TouchSample(id:$id, $x,$y @$timeMs)';
}
