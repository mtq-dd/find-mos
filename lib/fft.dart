import 'dart:math' as math;
import 'dart:typed_data';

/// Radix-2 Cooley-Tukey FFT / inverse FFT.
///
/// Input length must be a power of 2. The same precomputed bit-reversal
/// table and twiddle factors are reused across calls for efficiency.
class Radix2FFT {
  final int size;
  final Int32List _bitRev;
  final Float64List _twRe;
  final Float64List _twIm;

  factory Radix2FFT(int size) {
    if (size <= 0 || (size & (size - 1)) != 0) {
      throw ArgumentError.value(size, 'size', 'must be a positive power of 2');
    }
    var bits = 0;
    var n = size;
    while (n > 1) {
      n >>= 1;
      bits++;
    }
    final bitRev = Int32List(size);
    for (var i = 0; i < size; i++) {
      var x = i;
      var r = 0;
      for (var b = 0; b < bits; b++) {
        r = (r << 1) | (x & 1);
        x >>= 1;
      }
      bitRev[i] = r;
    }

    final twRe = Float64List(size ~/ 2);
    final twIm = Float64List(size ~/ 2);
    for (var k = 0; k < size ~/ 2; k++) {
      final ang = -2.0 * math.pi * k / size;
      twRe[k] = math.cos(ang);
      twIm[k] = math.sin(ang);
    }
    return Radix2FFT._(size, bitRev, twRe, twIm);
  }

  Radix2FFT._(this.size, this._bitRev, this._twRe, this._twIm);

  void fft(Float64List re, Float64List im) => _transform(re, im, inverse: false);

  void ifft(Float64List re, Float64List im) {
    _transform(re, im, inverse: true);
    final inv = 1.0 / size;
    for (var i = 0; i < size; i++) {
      re[i] *= inv;
      im[i] *= inv;
    }
  }

  void _transform(Float64List re, Float64List im, {required bool inverse}) {
    final n = size;
    for (var i = 0; i < n; i++) {
      final j = _bitRev[i];
      if (j > i) {
        final tr = re[i]; re[i] = re[j]; re[j] = tr;
        final ti = im[i]; im[i] = im[j]; im[j] = ti;
      }
    }
    for (var s = 2; s <= n; s <<= 1) {
      final half = s >> 1;
      final step = n ~/ s;
      for (var m = 0; m < n; m += s) {
        var k = 0;
        for (var j = m; j < m + half; j++) {
          final wr = _twRe[k];
          final wi = inverse ? -_twIm[k] : _twIm[k];
          final rr = re[j + half];
          final ii = im[j + half];
          final tr = wr * rr - wi * ii;
          final ti = wr * ii + wi * rr;
          re[j + half] = re[j] - tr;
          im[j + half] = im[j] - ti;
          re[j] = re[j] + tr;
          im[j] = im[j] + ti;
          k += step;
        }
      }
    }
  }

  /// Returns a Hann window of length [size].
  Float64List hannWindow() {
    final out = Float64List(size);
    for (var i = 0; i < size; i++) {
      out[i] = 0.5 * (1.0 - math.cos(2.0 * math.pi * i / (size - 1)));
    }
    return out;
  }
}
