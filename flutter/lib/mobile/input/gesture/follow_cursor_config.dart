/// 「跟随手」手势层的可调参数。
///
/// 这些值会同时用于运行期和单元测试；测试里显式传一个固定配置，
/// 避免依赖设备尺寸导致断言飘移。
class FollowCursorConfig {
  /// 左侧右键修饰区宽度占屏幕宽度的比例（0 = 关闭该功能）。
  final double leftZoneRatio;

  /// 判定「手的位移」的阈值（dp）。小于它视为点击，大于它视为移动。
  final double moveThreshold;

  /// 左区手指需要静止多久才被认定为「按住右键修饰」（毫秒）。
  final int modifierHoldMs;

  /// 单指从按下到抬起不超过该时长且没移动，才算点击（毫秒）。
  final int tapMaxMs;

  /// 长按多久触发右键（毫秒）。修饰区内的长按不算长按，走修饰逻辑。
  final int longPressMs;

  /// 双击判定窗口（毫秒）。
  final int doubleTapMs;

  /// 双击后允许的位移容差（dp）。
  final double doubleTapSlop;

  /// 光标平滑时间常数（毫秒）。越小越跟手，越大越平滑。
  final double smoothingTauMs;

  /// 双指滚动的每像素缩放系数。
  final double scrollScale;

  /// 是否在光标移动时保持光标可见（点击涟漪由 UI 层负责）。
  final bool showCursor;

  const FollowCursorConfig({
    this.leftZoneRatio = 0.2,
    this.moveThreshold = 8.0,
    this.modifierHoldMs = 150,
    this.tapMaxMs = 250,
    this.longPressMs = 500,
    this.doubleTapMs = 300,
    this.doubleTapSlop = 40.0,
    this.smoothingTauMs = 40.0,
    this.scrollScale = 1.0,
    this.showCursor = true,
  });

  FollowCursorConfig copyWith({
    double? leftZoneRatio,
    double? moveThreshold,
    int? modifierHoldMs,
    int? tapMaxMs,
    int? longPressMs,
    int? doubleTapMs,
    double? doubleTapSlop,
    double? smoothingTauMs,
    double? scrollScale,
    bool? showCursor,
  }) {
    return FollowCursorConfig(
      leftZoneRatio: leftZoneRatio ?? this.leftZoneRatio,
      moveThreshold: moveThreshold ?? this.moveThreshold,
      modifierHoldMs: modifierHoldMs ?? this.modifierHoldMs,
      tapMaxMs: tapMaxMs ?? this.tapMaxMs,
      longPressMs: longPressMs ?? this.longPressMs,
      doubleTapMs: doubleTapMs ?? this.doubleTapMs,
      doubleTapSlop: doubleTapSlop ?? this.doubleTapSlop,
      smoothingTauMs: smoothingTauMs ?? this.smoothingTauMs,
      scrollScale: scrollScale ?? this.scrollScale,
      showCursor: showCursor ?? this.showCursor,
    );
  }
}
