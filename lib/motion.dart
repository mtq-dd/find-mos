/// Motion detection pipeline: frame differencing + flood-fill connected
/// components, filtered by area to produce motion region rectangles.
///
/// Designed to operate on small (e.g. 160x90) luminance frames so the pipeline
/// remains light-weight for continuous use.
library;

import 'dart:typed_data';

/// A rectangular motion region in normalized coordinates.
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

  /// Process a new luma frame (values 0..255). Returns the list of motion
  /// regions detected (in input pixel coordinates).
  List<MotionRect> detect(List<int> luma) {
    final prev = _prev ?? Uint8List(pixelCount);
    final cur = Uint8List(pixelCount);
    for (var i = 0; i < pixelCount && i < luma.length; i++) {
      cur[i] = luma[i] & 0xFF;
    }

    // Difference mask (1 = changed)
    final mask = _labels; // reuse labels for mask
    for (var i = 0; i < pixelCount; i++) {
      final d = (cur[i] - prev[i]).abs();
      mask[i] = d > threshold ? -1 : 0;
    }

    // Skip first frame (no previous image)
    if (_frameCount == 0) {
      _prev = cur;
      _frameCount++;
      return const <MotionRect>[];
    }
    _frameCount++;
    _prev = cur;

    // Connected components (1-pass flood-fill)
    final result = <MotionRect>[];
    var label = 1;
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        final idx = y * width + x;
        if (mask[idx] != -1) continue;
        // BFS
        var area = 0;
        var minX = x, maxX = x, minY = y, maxY = y;
        final stackX = [x];
        final stackY = [y];
        mask[idx] = label;
        while (stackX.isNotEmpty) {
          final cx = stackX.removeLast();
          final cy = stackY.removeLast();
          area++;
          if (cx < minX) minX = cx;
          if (cx > maxX) maxX = cx;
          if (cy < minY) minY = cy;
          if (cy > maxY) maxY = cy;
          // 4-neighbors
          for (var k = 0; k < 4; k++) {
            final nx = cx + const [-1, 1, 0, 0][k];
            final ny = cy + const [0, 0, -1, 1][k];
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
            final ni = ny * width + nx;
            if (mask[ni] == -1) {
              mask[ni] = label;
              stackX.add(nx);
              stackY.add(ny);
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
