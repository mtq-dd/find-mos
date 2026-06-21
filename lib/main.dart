import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import 'fft.dart';
import 'motion.dart';
import 'radar.dart';

void main() {
  runApp(const FindMosApp());
}

class FindMosApp extends StatelessWidget {
  const FindMosApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '声光猎蚊',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.greenAccent,
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF0B1A14),
        useMaterial3: true,
      ),
      home: const PermissionGate(),
    );
  }
}

class PermissionGate extends StatefulWidget {
  const PermissionGate({super.key});

  @override
  State<PermissionGate> createState() => _PermissionGateState();
}

class _PermissionGateState extends State<PermissionGate> {
  bool _ready = false;
  bool _hasCamera = false;
  bool _hasMic = false;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    final cam = await Permission.camera.status;
    final mic = await Permission.microphone.status;
    setState(() {
      _hasCamera = cam.isGranted;
      _hasMic = mic.isGranted;
      _ready = _hasCamera && _hasMic;
    });
  }

  Future<void> _request() async {
    final results = await [Permission.camera, Permission.microphone].request();
    setState(() {
      _hasCamera = results[Permission.camera]?.isGranted ?? false;
      _hasMic = results[Permission.microphone]?.isGranted ?? false;
      _ready = _hasCamera && _hasMic;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_ready) {
      return const FindMosHome();
    }
    return Scaffold(
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.radar, size: 80, color: Colors.greenAccent),
              const SizedBox(height: 24),
              const Text(
                '声光猎蚊',
                style: TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.bold,
                  color: Colors.greenAccent,
                  letterSpacing: 4,
                ),
              ),
              const SizedBox(height: 16),
              const Text(
                '需要「相机」与「麦克风」权限方可开始探测。',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white70, fontSize: 16),
              ),
              const SizedBox(height: 32),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _chip(Icons.videocam, '相机', _hasCamera),
                  const SizedBox(width: 12),
                  _chip(Icons.mic, '麦克风', _hasMic),
                ],
              ),
              const SizedBox(height: 32),
              ElevatedButton.icon(
                onPressed: _request,
                icon: const Icon(Icons.check_circle),
                label: const Text('请求权限'),
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                  textStyle: const TextStyle(fontSize: 16),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _chip(IconData icon, String label, bool ok) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      decoration: BoxDecoration(
        color: (ok ? Colors.greenAccent : Colors.redAccent).withOpacity(0.2),
        border: Border.all(color: ok ? Colors.greenAccent : Colors.redAccent),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 18, color: ok ? Colors.greenAccent : Colors.redAccent),
        const SizedBox(width: 6),
        Text(label, style: TextStyle(color: ok ? Colors.greenAccent : Colors.redAccent)),
      ]),
    );
  }
}

class FindMosHome extends StatefulWidget {
  const FindMosHome({super.key});

  @override
  State<FindMosHome> createState() => _FindMosHomeState();
}

class _FindMosHomeState extends State<FindMosHome> with WidgetsBindingObserver {
  static const _methodChannel = MethodChannel('com.example.mosqitoukiller/method');
  static const _radarChannel = EventChannel('com.example.mosqitoukiller/radar');
  static const _cameraChannel = EventChannel('com.example.mosqitoukiller/camera');

  StreamSubscription<dynamic>? _radarSub;
  StreamSubscription<dynamic>? _cameraSub;

  RadarTarget _target = const RadarTarget(distance: 0, azimuth: 0);
  String _direction = '等待信号';
  String _status = '雷达未启动';
  double _leftEnergy = 0;
  double _rightEnergy = 0;

  bool _radarOn = false;
  bool _cameraOn = false;
  bool _torchOn = false;

  MotionDetector? _motion;
  List<MotionRect> _rects = const <MotionRect>[];
  int _frameW = 160;
  int _frameH = 90;
  Uint8List? _lumaBytes;
  int _cameraFrameCount = 0;
  int _radarFrameCount = 0;

  bool _pulse = false;
  Timer? _pulseTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startRadar();
    _startCamera();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stopRadar();
    _stopCamera();
    _pulseTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.resumed:
        _startRadar();
        _startCamera();
      case AppLifecycleState.paused:
      case AppLifecycleState.detached:
      case AppLifecycleState.inactive:
      case AppLifecycleState.hidden:
        _stopRadar();
        _stopCamera();
        _methodChannel.invokeMethod<bool>('setTorch', <String, dynamic>{'on': false});
    }
  }

  Future<void> _startRadar() async {
    if (_radarOn) return;
    try {
      final ok = await _methodChannel.invokeMethod<bool>('startRadar') ?? false;
      if (!ok) {
        setState(() => _status = '雷达启动失败');
        return;
      }
      _radarOn = true;
      _radarSub = _radarChannel.receiveBroadcastStream().listen(_onRadarEvent);
      setState(() {});
    } on PlatformException catch (e) {
      debugPrint('startRadar error: $e');
    }
  }

  Future<void> _stopRadar() async {
    if (!_radarOn) return;
    _radarOn = false;
    await _radarSub?.cancel();
    _radarSub = null;
    try {
      await _methodChannel.invokeMethod<bool>('stopRadar');
    } on PlatformException catch (e) {
      debugPrint('stopRadar error: $e');
    }
    setState(() {});
  }

  Future<void> _startCamera() async {
    if (_cameraOn) return;
    try {
      final ok = await _methodChannel.invokeMethod<bool>('startCameraStream') ?? false;
      if (!ok) {
        setState(() => _status = '相机启动失败');
        return;
      }
      _cameraOn = true;
      _cameraSub = _cameraChannel.receiveBroadcastStream().listen(_onCameraEvent);
      setState(() {});
    } on PlatformException catch (e) {
      debugPrint('startCamera error: $e');
    }
  }

  Future<void> _stopCamera() async {
    if (!_cameraOn) return;
    _cameraOn = false;
    await _cameraSub?.cancel();
    _cameraSub = null;
    try {
      await _methodChannel.invokeMethod<bool>('stopCameraStream');
    } on PlatformException catch (e) {
      debugPrint('stopCameraStream error: $e');
    }
    setState(() {});
  }

  void _onRadarEvent(dynamic event) {
    final map = event as Map<dynamic, dynamic>;
    final azimuth = (map['azimuth'] as num?)?.toDouble() ?? 0.0;
    final distance = (map['distance'] as num?)?.toDouble() ?? 0.0;
    final direction = (map['direction'] as String?) ?? '正前方';
    final status = (map['status'] as String?) ?? '监听中';
    final lE = (map['leftEnergy'] as num?)?.toDouble() ?? 0.0;
    final rE = (map['rightEnergy'] as num?)?.toDouble() ?? 0.0;
    setState(() {
      _target = RadarTarget(distance: distance, azimuth: azimuth);
      _direction = direction;
      _status = _rects.isNotEmpty ? '检测到运动' : status;
      _leftEnergy = lE;
      _rightEnergy = rE;
      _radarFrameCount++;
      if (distance > 0.6) _triggerPulse();
    });
  }

  void _onCameraEvent(dynamic event) {
    final map = event as Map<dynamic, dynamic>;
    final w = (map['width'] as int?) ?? 160;
    final h = (map['height'] as int?) ?? 90;
    final luma = (map['luma'] as List<dynamic>?)?.cast<int>() ?? const <int>[];
    if (_motion == null || _motion!.width != w || _motion!.height != h) {
      _motion = MotionDetector(width: w, height: h, threshold: 25, minArea: 5, maxArea: 500);
      _frameW = w;
      _frameH = h;
    }
    // 保存亮度数据用于渲染
    final bytes = Uint8List(w * h);
    final len = luma.length.clamp(0, w * h);
    for (var i = 0; i < len; i++) {
      bytes[i] = luma[i] & 0xFF;
    }
    final rects = _motion!.detect(luma);
    setState(() {
      _lumaBytes = bytes;
      _cameraFrameCount++;
      _rects = rects;
      if (_rects.isNotEmpty && _status != '目标接近') _status = '检测到运动';
    });
  }

  void _triggerPulse() {
    if (_pulse) return;
    setState(() => _pulse = true);
    _pulseTimer = Timer(const Duration(milliseconds: 400), () {
      if (mounted) setState(() => _pulse = false);
    });
  }

  Future<void> _toggleTorch() async {
    final next = !_torchOn;
    try {
      await _methodChannel.invokeMethod<bool>('setTorch', <String, dynamic>{'on': next});
      setState(() => _torchOn = next);
    } on PlatformException catch (e) {
      debugPrint('setTorch error: $e');
    }
  }

  Future<void> _playStartle() async {
    try {
      await _methodChannel.invokeMethod<bool>(
        'playStartleTone',
        <String, dynamic>{'frequency': 350.0, 'durationMs': 1000},
      );
    } on PlatformException catch (e) {
      debugPrint('playStartleTone error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            _buildStatusBar(),
            Expanded(
              child: Row(
                children: [
                  Expanded(flex: 5, child: _buildRadarPanel()),
                  Expanded(flex: 5, child: _buildCameraPanel()),
                ],
              ),
            ),
            _buildControls(),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBar() {
    final accent = _status == '目标接近'
        ? Colors.redAccent
        : _status == '检测到运动'
            ? Colors.orangeAccent
            : _status == '追踪中'
                ? Colors.yellowAccent
                : Colors.greenAccent;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: accent.withOpacity(0.3))),
      ),
      child: Row(children: [
        Icon(Icons.radar, color: accent),
        const SizedBox(width: 8),
        Text(
          '声光猎蚊 · $_status',
          style: TextStyle(color: accent, fontWeight: FontWeight.bold, letterSpacing: 2),
        ),
        const Spacer(),
        _chipSmall(Icons.mic, '麦克风', _radarOn),
        const SizedBox(width: 8),
        _chipSmall(Icons.videocam, '视觉', _cameraOn),
        const SizedBox(width: 8),
        _chipSmall(Icons.flash_on, '补光', _torchOn),
      ]),
    );
  }

  Widget _chipSmall(IconData icon, String label, bool on) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: (on ? Colors.greenAccent : Colors.grey).withOpacity(0.15),
        border: Border.all(color: on ? Colors.greenAccent : Colors.grey),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 14, color: on ? Colors.greenAccent : Colors.grey),
        const SizedBox(width: 4),
        Text(label, style: TextStyle(color: on ? Colors.greenAccent : Colors.grey, fontSize: 12)),
      ]),
    );
  }

  Widget _buildRadarPanel() {
    return Padding(
      padding: const EdgeInsets.all(12.0),
      child: Column(children: [
        Expanded(
          child: RadarView(
            target: _target,
            pulse: _pulse || _target.distance > 0.6,
            directionText: _direction,
          ),
        ),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: _energyBar('L', _leftEnergy, Colors.cyanAccent)),
          const SizedBox(width: 8),
          Expanded(child: _energyBar('R', _rightEnergy, Colors.pinkAccent)),
        ]),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            Text(
              '距离: ${(_target.distance * 100).toStringAsFixed(0)}%',
              style: const TextStyle(color: Colors.white70, fontSize: 12),
            ),
            Text(
              '方位: ${(_target.azimuth * 180 / 3.14159).toStringAsFixed(1)}°',
              style: const TextStyle(color: Colors.white70, fontSize: 12),
            ),
            Text(
              '雷达帧: $_radarFrameCount',
              style: const TextStyle(color: Colors.white70, fontSize: 12),
            ),
          ],
        ),
      ]),
    );
  }

  Widget _energyBar(String label, double value, Color color) {
    final v = value.clamp(0.0, 1.0);
    return Row(children: [
      SizedBox(width: 24, child: Text(label, style: const TextStyle(color: Colors.white54))),
      const SizedBox(width: 4),
      Expanded(
        child: Container(
          height: 10,
          decoration: BoxDecoration(
            color: Colors.white10,
            borderRadius: BorderRadius.circular(4),
          ),
          child: FractionallySizedBox(
            alignment: Alignment.centerLeft,
            widthFactor: v,
            child: Container(
              decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(4)),
            ),
          ),
        ),
      ),
    ]);
  }

  Widget _buildCameraPanel() {
    return Padding(
      padding: const EdgeInsets.all(12.0),
      child: Column(
        children: [
          const Text(
            '视觉运动侦测',
            style: TextStyle(color: Colors.greenAccent, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Container(
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.greenAccent.withOpacity(0.4), width: 2),
                  borderRadius: BorderRadius.circular(12),
                  color: Colors.black,
                ),
                child: _buildMotionOverlay(),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            '运动: ${_rects.length}   帧: ${_frameW}×${_frameH}   图像帧数: $_cameraFrameCount',
            style: const TextStyle(color: Colors.white70, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Widget _buildMotionOverlay() {
    return LayoutBuilder(
      builder: (context, constraints) {
        final w = constraints.maxWidth;
        final h = constraints.maxHeight;
        final scaleX = w / _frameW;
        final scaleY = h / _frameH;
        return Stack(
          children: [
            Positioned.fill(
              child: CustomPaint(
                painter: _GrayscalePainter(
                  luma: _lumaBytes,
                  width: _frameW,
                  height: _frameH,
                ),
              ),
            ),
            Positioned.fill(child: CustomPaint(painter: _ScanlinesPainter())),
            ..._rects.map((r) {
              return Positioned(
                left: r.left * scaleX,
                top: r.top * scaleY,
                width: (r.right - r.left + 1) * scaleX,
                height: (r.bottom - r.top + 1) * scaleY,
                child: Container(
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.redAccent, width: 2),
                    color: Colors.redAccent.withOpacity(0.2),
                  ),
                ),
              );
            }),
            if (_rects.isEmpty && _lumaBytes != null)
              const Positioned.fill(
                child: Center(
                  child: Text('未检测到运动', style: TextStyle(color: Colors.white30)),
                ),
              ),
            if (_lumaBytes == null)
              const Positioned.fill(
                child: Center(
                  child: Text('等待图像数据…', style: TextStyle(color: Colors.white54)),
                ),
              ),
          ],
        );
      },
    );
  }

  Widget _buildControls() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
        children: [
          _ctrlButton(
            icon: Icons.flashlight_on,
            label: '手电筒',
            on: _torchOn,
            onPressed: _toggleTorch,
            color: Colors.yellowAccent,
          ),
          _ctrlButton(
            icon: Icons.surround_sound,
            label: '惊扰音',
            on: false,
            onPressed: _playStartle,
            color: Colors.purpleAccent,
          ),
          _ctrlButton(
            icon: Icons.radar,
            label: _radarOn ? '雷达开' : '雷达关',
            on: _radarOn,
            onPressed: () async {
              if (_radarOn) {
                await _stopRadar();
              } else {
                await _startRadar();
              }
            },
            color: Colors.greenAccent,
          ),
          _ctrlButton(
            icon: Icons.videocam,
            label: _cameraOn ? '视觉开' : '视觉关',
            on: _cameraOn,
            onPressed: () async {
              if (_cameraOn) {
                await _stopCamera();
              } else {
                await _startCamera();
              }
            },
            color: Colors.cyanAccent,
          ),
        ],
      ),
    );
  }

  Widget _ctrlButton({
    required IconData icon,
    required String label,
    required bool on,
    required VoidCallback onPressed,
    required Color color,
  }) {
    return GestureDetector(
      onTap: onPressed,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: on ? color.withOpacity(0.25) : Colors.white10,
          border: Border.all(color: on ? color : Colors.white24),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, color: on ? color : Colors.white70, size: 22),
          const SizedBox(height: 4),
          Text(label, style: TextStyle(color: on ? color : Colors.white70, fontSize: 12)),
        ]),
      ),
    );
  }
}

class _ScanlinesPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.greenAccent.withOpacity(0.08)
      ..strokeWidth = 1;
    for (var y = 0; y < size.height; y += 4) {
      canvas.drawLine(Offset(0, y.toDouble()), Offset(size.width, y.toDouble()), paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _GrayscalePainter extends CustomPainter {
  final Uint8List? luma;
  final int width;
  final int height;

  _GrayscalePainter({required this.luma, required this.width, required this.height});

  @override
  void paint(Canvas canvas, Size size) {
    final data = luma;
    if (data == null || data.isEmpty) return;
    final pxW = size.width / width;
    final pxH = size.height / height;
    // 降采样绘制：每几像素画一个矩形，避免14400个drawRect
    final stepX = 1;
    final stepY = 1;
    final paint = Paint()..style = PaintingStyle.fill;
    for (var y = 0; y < height; y += stepY) {
      for (var x = 0; x < width; x += stepX) {
        final idx = y * width + x;
        if (idx >= data.length) continue;
        final v = data[idx];
        // 调整对比度，让画面更有层次感
        final adjusted = ((v - 80) * 1.4).clamp(0.0, 255.0).toInt();
        paint.color = Color.fromARGB(255, adjusted, adjusted, adjusted);
        canvas.drawRect(
          Rect.fromLTWH(x * pxW, y * pxH, pxW + 0.5, pxH + 0.5),
          paint,
        );
      }
    }
  }

  @override
  bool shouldRepaint(covariant _GrayscalePainter oldDelegate) {
    return oldDelegate.luma != luma;
  }
}
