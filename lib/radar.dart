import 'dart:math' as math;

import 'package:flutter/material.dart';

class RadarTarget {
  final double distance;
  final double azimuth;

  const RadarTarget({required this.distance, required this.azimuth});
}

/// 圆形雷达 HUD 部件，带旋转扫描线动画
/// 30% 透明度背景，不遮挡相机画面
class RadarView extends StatefulWidget {
  final RadarTarget target;
  final bool pulse;
  final String? directionText;
  final bool running;

  const RadarView({
    super.key,
    required this.target,
    this.pulse = false,
    this.directionText,
    this.running = true,
  });

  @override
  State<RadarView> createState() => _RadarViewState();
}

class _RadarViewState extends State<RadarView> with SingleTickerProviderStateMixin {
  late AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );
    if (widget.running) _controller.repeat();
  }

  @override
  void didUpdateWidget(RadarView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.running && !_controller.isAnimating) {
      _controller.repeat();
    } else if (!widget.running && _controller.isAnimating) {
      _controller.stop();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, child) {
        return CustomPaint(
          painter: _RadarPainter(
            target: widget.target,
            pulse: widget.pulse,
            directionText: widget.directionText,
            sweepAngle: _controller.value * 2 * math.pi,
            running: widget.running,
          ),
          size: const Size(140, 140),
        );
      },
    );
  }
}

class _RadarPainter extends CustomPainter {
  final RadarTarget target;
  final bool pulse;
  final String? directionText;
  final double sweepAngle;
  final bool running;

  static const _panelBg = Color(0x4D050A08); // 30% 透明度深绿黑
  static const _accent = Color(0xFF00FF88); // 荧光绿
  static const _accentDim = Color(0x4000FF88); // 25% 透明度

  _RadarPainter({
    required this.target,
    required this.pulse,
    this.directionText,
    required this.sweepAngle,
    required this.running,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final cx = size.width / 2;
    final cy = size.height / 2;
    final radius = size.shortestSide / 2 - 4;

    // 30% 透明背景（仅边框线，主体透明）
    final bgBorder = Paint()
      ..color = _accentDim
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    canvas.drawCircle(Offset(cx, cy), radius, bgBorder);

    // 同心圆刻度（极淡）
    final ringPaint = Paint()
      ..color = _accent.withOpacity(0.18)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.8;
    for (var i = 1; i <= 3; i++) {
      canvas.drawCircle(Offset(cx, cy), radius * i / 3, ringPaint);
    }

    // 十字准线
    final crossPaint = Paint()
      ..color = _accent.withOpacity(0.25)
      ..strokeWidth = 0.8;
    canvas.drawLine(Offset(cx - radius, cy), Offset(cx + radius, cy), crossPaint);
    canvas.drawLine(Offset(cx, cy - radius), Offset(cx, cy + radius), crossPaint);

    // 旋转扫描扇形（仅运行时）
    if (running) {
      canvas.save();
      canvas.translate(cx, cy);
      canvas.rotate(sweepAngle);
      final sweepPaint = Paint()
        ..shader = SweepGradient(
          center: Alignment.center,
          colors: [
            Colors.transparent,
            _accent.withOpacity(0.5),
            _accent.withOpacity(0.15),
            Colors.transparent,
          ],
          stops: const [0.0, 0.08, 0.2, 0.35],
        ).createShader(Rect.fromCircle(center: Offset.zero, radius: radius));
      canvas.drawArc(
        Rect.fromCircle(center: Offset.zero, radius: radius),
        -math.pi / 2,
        math.pi / 2,
        true,
        sweepPaint,
      );
      canvas.restore();
    }

    // 目标光点
    if (target.distance > 0.02 && running) {
      final angle = -math.pi / 2 - target.azimuth;
      final r = radius * target.distance.clamp(0.0, 1.0);
      final tx = cx + r * math.cos(angle);
      final ty = cy + r * math.sin(angle);
      final dotColor = target.distance > 0.7
          ? const Color(0xFFFF4444)
          : target.distance > 0.4
              ? const Color(0xFFFF8800)
              : _accent;
      final dotRadius = 3.0 + target.distance * 10.0;
      canvas.drawCircle(Offset(tx, ty), dotRadius + 6, Paint()..color = dotColor.withOpacity(0.15));
      canvas.drawCircle(Offset(tx, ty), dotRadius, Paint()..color = dotColor);
    }

    // 底部方位文字
    if (directionText != null && running) {
      final textStyle = TextStyle(
        color: _accent.withOpacity(0.8),
        fontSize: 11,
        fontWeight: FontWeight.bold,
        letterSpacing: 1.5,
      );
      final textPainter = TextPainter(
        text: TextSpan(text: directionText, style: textStyle),
        textDirection: TextDirection.ltr,
      )..layout();
      textPainter.paint(
        canvas,
        Offset(cx - textPainter.width / 2, cy + radius - textPainter.height - 2),
      );
    }

    // 雷达关闭状态
    if (!running) {
      final textStyle = TextStyle(
        color: Colors.white30,
        fontSize: 10,
        letterSpacing: 1,
      );
      final textPainter = TextPainter(
        text: TextSpan(text: 'RADAR OFF', style: textStyle),
        textDirection: TextDirection.ltr,
      )..layout();
      textPainter.paint(canvas, Offset(cx - textPainter.width / 2, cy - textPainter.height / 2));
    }
  }

  @override
  bool shouldRepaint(covariant _RadarPainter oldDelegate) {
    return oldDelegate.target.distance != target.distance ||
        oldDelegate.target.azimuth != target.azimuth ||
        oldDelegate.pulse != pulse ||
        oldDelegate.directionText != directionText ||
        oldDelegate.sweepAngle != sweepAngle ||
        oldDelegate.running != running;
  }
}
