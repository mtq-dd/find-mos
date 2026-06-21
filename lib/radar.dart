library;

import 'dart:math' as math;

import 'package:flutter/material.dart';

class RadarTarget {
  final double distance;
  final double azimuth;

  const RadarTarget({required this.distance, required this.azimuth});
}

class RadarView extends StatelessWidget {
  final RadarTarget target;
  final bool pulse;
  final String? directionText;

  const RadarView({
    super.key,
    required this.target,
    this.pulse = false,
    this.directionText,
  });

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      painter: _RadarPainter(target: target, pulse: pulse, directionText: directionText),
      child: const SizedBox.expand(),
    );
  }
}

class _RadarPainter extends CustomPainter {
  final RadarTarget target;
  final bool pulse;
  final String? directionText;

  _RadarPainter({required this.target, required this.pulse, this.directionText});

  @override
  void paint(Canvas canvas, Size size) {
    final cx = size.width / 2;
    final cy = size.height / 2;
    final radius = size.shortestSide / 2 - 6;

    final bg = Paint()
      ..color = const Color(0xFF0B1A14)
      ..style = PaintingStyle.fill;
    canvas.drawCircle(Offset(cx, cy), radius, bg);

    final ringPaint = Paint()
      ..color = Colors.greenAccent.withOpacity(0.35)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0;
    for (var i = 1; i <= 3; i++) {
      canvas.drawCircle(Offset(cx, cy), radius * i / 3, ringPaint);
    }

    final crossPaint = Paint()
      ..color = Colors.greenAccent.withOpacity(0.5)
      ..strokeWidth = 1.0;
    canvas.drawLine(Offset(cx - radius, cy), Offset(cx + radius, cy), crossPaint);
    canvas.drawLine(Offset(cx, cy - radius), Offset(cx, cy + radius), crossPaint);

    final sweepPaint = Paint()
      ..shader = SweepGradient(
        center: Alignment.center,
        colors: [
          Colors.transparent,
          Colors.greenAccent.withOpacity(0.25),
          Colors.transparent,
        ],
        stops: const [0.0, 0.1, 0.2],
      ).createShader(Rect.fromCircle(center: Offset(cx, cy), radius: radius));
    canvas.drawCircle(Offset(cx, cy), radius, sweepPaint);

    if (pulse) {
      final pulsePaint = Paint()
        ..color = Colors.redAccent.withOpacity(0.75)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.5;
      canvas.drawCircle(Offset(cx, cy), radius * 0.95, pulsePaint);
    }

    if (target.distance > 0.02) {
      final angle = -math.pi / 2 - target.azimuth;
      final r = radius * target.distance.clamp(0.0, 1.0);
      final tx = cx + r * math.cos(angle);
      final ty = cy + r * math.sin(angle);
      final dotRadius = 4.0 + target.distance * 12.0;
      final dotColor = target.distance > 0.7
          ? Colors.redAccent
          : target.distance > 0.4
              ? Colors.orangeAccent
              : Colors.greenAccent;
      canvas.drawCircle(Offset(tx, ty), dotRadius + 6,
          Paint()..color = dotColor.withOpacity(0.15));
      canvas.drawCircle(Offset(tx, ty), dotRadius, Paint()..color = dotColor);
    }

    if (directionText != null) {
      const textStyle = TextStyle(
        color: Colors.greenAccent,
        fontSize: 18,
        fontWeight: FontWeight.bold,
        letterSpacing: 2,
      );
      final textPainter = TextPainter(
        text: TextSpan(text: directionText, style: textStyle),
        textDirection: TextDirection.ltr,
      )..layout();
      textPainter.paint(
        canvas,
        Offset(cx - textPainter.width / 2, cy + radius - textPainter.height - 8),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _RadarPainter oldDelegate) {
    return oldDelegate.target.distance != target.distance ||
        oldDelegate.target.azimuth != target.azimuth ||
        oldDelegate.pulse != pulse ||
        oldDelegate.directionText != directionText;
  }
}
