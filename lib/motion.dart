library;

import 'dart:typed_data';

/// A rectangular motion region, in input pixel coordinates.
class MotionRect {
  final int left;
  final int top;
  final int right;
  final int bottom;
  final int area;

  MotionRect({
    required this.left,
    required this.top,
    required this.right,
    required this.bottom,
    required this.area,
  });
}

/// Frame-differencing motion detector.
///
/// For each new luminance frame, the detector:
/// 1. Marks pixels whose absolute difference with the previous frame exceeds
///    [threshold].
/// 2. Labels connected-components via a BFS flood-fill (4-connectivity).
/// 3. Retains regions whose pixel area is in `[minArea, maxArea]`.
class MotionDetector {
  final int width;
  final int height;
  final int threshold;
  final int minArea;
  final int maxArea;

  Uint8List? _prev;
  final Int32List _labels;
  int _frameCount = 0;

  MotionDetector({
    required this.width,
    required this.height,
    this.threshold = 25,
    this.minArea = 5,
    this.maxArea = 200,
  }) : _labels = Int32List(width * height);

  int get pixelCount => width * height;

  List<MotionRect> detect(List<int> luma) {
    final cur = Uint8List(pixelCount);
    final clampedLength = luma.length.clamp(0, pixelCount);
    for (var i = 0; i < clampedLength; i++) {
      cur[i] = luma[i] & 0xFF;
    }

    final mask = _labels;
    for (var i = 0; i < pixelCount; i++) {
      mask[i] = 0;
    }

    if (_prev != null) {
      final prev = _prev!;
      for (var i = 0; i < pixelCount; i++) {
        final d = (cur[i] - prev[i]).abs();
        if (d > threshold) mask[i] = -1;
      }
    }
    _prev = cur;
    if (_frameCount == 0) {
      _frameCount++;
      return const <MotionRect>[];
    }
    _frameCount++;

    final result = <MotionRect>[];
    var label = 1;
    final stack = Int32List(pixelCount * 2);
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        final start = y * width + x;
        if (mask[start] != -1) continue;
        // BFS
        var sp = 0;
        stack[sp++] = x;
        stack[sp++] = y;
        mask[start] = label;
        var area = 0;
        var minX = x, maxX = x, minY = y, maxY = y;
        while (sp > 0) {
          sp -= 2;
          final cx = stack[sp];
          final cy = stack[sp + 1];
          area++;
          if (cx < minX) minX = cx;
          if (cx > maxX) maxX = cx;
          if (cy < minY) minY = cy;
          if (cy > maxY) maxY = cy;
          for (var k = 0; k < 4; k++) {
            final nx = cx + const [-1, 1, 0, 0][k];
            final ny = cy + const [0, 0, -1, 1][k];
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
            final ni = ny * width + nx;
            if (mask[ni] == -1) {
              mask[ni] = label;
              stack[sp++] = nx;
              stack[sp++] = ny;
            }
          }
        }
        if (area >= minArea && area <= maxArea) {
          result.add(MotionRect(
            left: minX,
            top: minY,
            right: maxX,
            bottom: maxY,
            area: area,
          ));
        }
        label++;
      }
    }
    return result;
  }

  void reset() {
    _prev = null;
    _frameCount = 0;
  }
}
