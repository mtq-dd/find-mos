import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'motion.dart';
import 'radar.dart';

// 飞行轨迹点：时间戳 + 归一化坐标(0-100)
class _FlightPoint {
  final int timeMs;
  final double x; // 0-100
  final double y; // 0-100
  _FlightPoint(this.timeMs, this.x, this.y);
}

// 带存活时间的运动检测槽位（环形缓冲区用）
class _MotionSlot {
  MotionRect rect;
  int createdAtMs;
  _MotionSlot(this.rect, this.createdAtMs);
}

// 环形缓冲区：零堆分配，O(1) 淘汰
class _MotionRing {
  static const int _capacity = 64;
  final List<_MotionSlot?> _slots = List<_MotionSlot?>.filled(_capacity, null);
  int _head = 0;
  int _count = 0;

  void add(MotionRect rect, int nowMs) {
    final idx = (_head + _count) % _capacity;
    if (_slots[idx] == null) {
      _slots[idx] = _MotionSlot(rect, nowMs);
    } else {
      _slots[idx]!.rect = rect;
      _slots[idx]!.createdAtMs = nowMs;
    }
    if (_count < _capacity) {
      _count++;
    } else {
      _head = (_head + 1) % _capacity;
    }
  }

  // 收集所有未过期的槽位，写入 out 列表（复用 out 避免分配）
  void collectActive(int cutoffMs, List<_MotionSlot> out) {
    out.clear();
    for (int i = 0; i < _count; i++) {
      final idx = (_head + i) % _capacity;
      final slot = _slots[idx];
      if (slot != null && slot.createdAtMs >= cutoffMs) {
        out.add(slot);
      }
    }
  }

  void clear() {
    _head = 0;
    _count = 0;
  }
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight]);
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

  // 运动检测环形缓冲区
  final _MotionRing _motionRing = _MotionRing();
  // 渲染用临时列表（复用，避免每帧分配）
  final List<_MotionSlot> _activeSlots = [];
  static const int _motionKeepMs = 600; // 检测到后保留 600ms
  // 飞行轨迹队列：保留最近60帧位置（timeMs, centerX, centerY）
  final List<_FlightPoint> _flightPath = [];
  static const int _maxFlightPathLength = 60;
  int? _cameraTextureId; // Flutter Texture ID
  int _cameraFrameCount = 0;
  int _sensorOrientation = 0; // 摄像头传感器方向（度），0/90/180/270
  int _radarFrameCount = 0;
  int _rotationAngle = 0; // 用户手动调整的画面旋转角度（0/90/180/270），持久化存储

  bool _pulse = false;
  Timer? _pulseTimer;
  bool _controlPanelVisible = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadRotationAngle();
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

  Future<void> _loadRotationAngle() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _rotationAngle = prefs.getInt('rotationAngle') ?? 0;
      });
    } catch (e) {
      debugPrint('loadRotationAngle error: $e');
    }
  }

  Future<void> _saveRotationAngle(int angle) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('rotationAngle', angle);
    } catch (e) {
      debugPrint('saveRotationAngle error: $e');
    }
  }

  void _rotateClockwise() {
    setState(() {
      _rotationAngle = (_rotationAngle + 90) % 360;
    });
    _saveRotationAngle(_rotationAngle);
  }

  void _rotateCounterClockwise() {
    setState(() {
      _rotationAngle = (_rotationAngle - 90 + 360) % 360;
    });
    _saveRotationAngle(_rotationAngle);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.resumed:
        _startRadar();
        _startCamera();
        break;
      case AppLifecycleState.paused:
      case AppLifecycleState.detached:
      case AppLifecycleState.inactive:
      case AppLifecycleState.hidden:
        _stopRadar();
        _stopCameraNoAwait(); // 不等完成，防止卡住
        _methodChannel.invokeMethod<bool>('setTorch', <String, dynamic>{'on': false});
        break;
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
      // 先建立 EventChannel 监听，确保 native 侧 cameraSink 就绪后再启动相机
      _cameraSub = _cameraChannel.receiveBroadcastStream().listen(_onCameraEvent);
      final textureId = await _methodChannel.invokeMethod<int>('startCameraStream') ?? -1;
      if (textureId < 0) {
        await _cameraSub?.cancel();
        _cameraSub = null;
        setState(() => _status = '相机启动失败');
        return;
      }
      _cameraOn = true;
      _cameraTextureId = textureId;
      setState(() {});
    } on PlatformException catch (e) {
      debugPrint('startCamera error: $e');
    }
  }

  // 异步版本：用于控制面板手动开关，等待 native 侧完整停止
  Future<void> _stopCamera() async {
    if (!_cameraOn) return;
    _cameraOn = false;
    _cameraTextureId = null;
    await _cameraSub?.cancel();
    _cameraSub = null;
    try {
      await _methodChannel.invokeMethod<bool>('stopCameraStream');
    } on PlatformException catch (e) {
      debugPrint('stopCameraStream error: $e');
    }
    if (mounted) {
      setState(() {
        _motionRing.clear();
      });
    }
  }

  // 同步版本：用于生命周期切换，不等 native 侧完成
  void _stopCameraNoAwait() {
    if (!_cameraOn) return;
    _cameraOn = false;
    _cameraTextureId = null;
    _cameraSub?.cancel();
    _cameraSub = null;
    _methodChannel.invokeMethod<bool>('stopCameraStream'); // fire & forget
    _flightPath.clear();
    setState(() {
      _motionRing.clear();
    });
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
      _status = _activeSlots.isNotEmpty ? '检测到运动' : status;
      _leftEnergy = lE;
      _rightEnergy = rE;
      _radarFrameCount++;
      if (distance > 0.6) _triggerPulse();
    });
  }

  void _onCameraEvent(dynamic event) {
    final map = event as Map<dynamic, dynamic>;
    final frameCount = (map['frameCount'] as int?) ?? 0;
    final sensorOri = (map['sensorOrientation'] as int?) ?? 0;
    final rawRects = (map['rects'] as List<dynamic>?) ?? const <dynamic>[];

    MotionRect? parsed;
    if (rawRects.isNotEmpty) {
      final m = rawRects[0] as Map<dynamic, dynamic>;
      final left = ((m['left'] as num?)?.toDouble() ?? 0) * 100;
      final top = ((m['top'] as num?)?.toDouble() ?? 0) * 100;
      final right = ((m['right'] as num?)?.toDouble() ?? 0) * 100;
      final bottom = ((m['bottom'] as num?)?.toDouble() ?? 0) * 100;
      final w = (right - left).round();
      final h = (bottom - top).round();
      parsed = MotionRect(
        left: left.round(),
        top: top.round(),
        right: right.round(),
        bottom: bottom.round(),
        area: (w * h).clamp(1, 999999),
      );
    }

    final nowMs = DateTime.now().millisecondsSinceEpoch;

    if (mounted) {
      setState(() {
        _cameraFrameCount = frameCount;
        if (sensorOri != 0) _sensorOrientation = sensorOri;
        // 新检测追加到环形缓冲区
        if (parsed != null) {
          _motionRing.add(parsed, nowMs);
          // 飞行轨迹：记录中心点
          final cx = (parsed.left + parsed.right) / 2.0;
          final cy = (parsed.top + parsed.bottom) / 2.0;
          _flightPath.add(_FlightPoint(nowMs, cx, cy));
          while (_flightPath.length > _maxFlightPathLength) {
            _flightPath.removeAt(0);
          }
        }
        // 从环形缓冲区收集存活 rect（复用 _activeSlots 避免分配）
        final cutoff = nowMs - _motionKeepMs;
        _motionRing.collectActive(cutoff, _activeSlots);
        if (_activeSlots.isNotEmpty && _status != '目标接近') _status = '检测到运动';
      });
    }
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
        <String, dynamic>{'frequency': 800.0, 'durationMs': 1000},
      );
    } on PlatformException catch (e) {
      debugPrint('playStartleTone error: $e');
    }
  }

  Future<void> _showLogs() async {
    String? runtime;
    String? crash;
    List<dynamic> list = const <dynamic>[];
    try {
      runtime = await _methodChannel.invokeMethod<String>('getLatestRuntimeLog');
    } on PlatformException catch (e) { debugPrint('getLatestRuntimeLog error: $e'); }
    try {
      crash = await _methodChannel.invokeMethod<String>('getLatestCrashLog');
    } on PlatformException catch (e) { debugPrint('getLatestCrashLog error: $e'); }
    try {
      final r = await _methodChannel.invokeMethod<List<dynamic>>('listRuntimeLogs');
      if (r != null) list = r;
    } on PlatformException catch (e) { debugPrint('listRuntimeLogs error: $e'); }
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF0E1B16),
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (ctx) {
        return DraggableScrollableSheet(
          expand: false, initialChildSize: 0.7, minChildSize: 0.3, maxChildSize: 0.95,
          builder: (ctx, scroll) {
            return ListView(controller: scroll, padding: const EdgeInsets.all(16), children: [
              Row(children: [
                const Icon(Icons.bug_report, color: Colors.greenAccent),
                const SizedBox(width: 8),
                const Text('日志', style: TextStyle(color: Colors.white, fontSize: 16)),
              ]),
              const SizedBox(height: 12),
              _logSection('崩溃日志', crash, Colors.redAccent),
              const SizedBox(height: 12),
              _logSection('运行时日志', runtime, Colors.greenAccent),
              const SizedBox(height: 12),
              const Text('日志文件：', style: TextStyle(color: Colors.white70)),
              if (list.isEmpty)
                const Padding(padding: EdgeInsets.symmetric(vertical: 8), child: Text('（暂无）', style: TextStyle(color: Colors.white38)))
              else
                ...list.map((e) {
                  final m = (e as Map).cast<String, dynamic>();
                  return Padding(padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Text('• ${m['name']}  (${(m['size'] / 1024).toStringAsFixed(1)} KB)',
                        style: const TextStyle(color: Colors.white54, fontSize: 12)));
                }),
            ]);
          },
        );
      },
    );
  }

  Widget _logSection(String title, String? content, Color color) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.06),
        border: Border.all(color: color.withOpacity(0.4)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: TextStyle(color: color, fontWeight: FontWeight.bold)),
        const SizedBox(height: 6),
        if (content == null || content.isEmpty)
          const Text('（无）', style: TextStyle(color: Colors.white38, fontSize: 12))
        else
          SelectableText(
            content.length > 6000 ? content.substring(content.length - 6000) : content,
            style: const TextStyle(color: Colors.white70, fontSize: 11, fontFamily: 'monospace')),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Stack(
        children: [
          // Layer 0: 全屏相机画面（点击背景切换控制面板）
          GestureDetector(
            onTap: () => setState(() => _controlPanelVisible = !_controlPanelVisible),
            child: Container(color: Colors.black, child: _buildCameraPanel()),
          ),

          // Layer 3: 雷达 HUD（左上角浮动，30% 透明）
          Positioned(
            left: 12,
            top: MediaQuery.of(context).padding.top + 8,
            child: RadarView(
              target: _target,
              pulse: _pulse || _target.distance > 0.6,
              directionText: _direction,
              running: _radarOn,
            ),
          ),

          // Layer 4: 底部状态栏（30% 透明）
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: _buildStatusBar(),
          ),

          // 控制面板（点击按钮区域不传递点击事件）
          if (_controlPanelVisible)
            Positioned.fill(
              child: GestureDetector(
                onTap: () {}, // 吸收面板上的点击，防止穿透
                child: Container(color: Colors.transparent),
              ),
            ),
          if (_controlPanelVisible)
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: _buildControlPanel(),
            ),
        ],
      ),
    );
  }

  // 30% 透明底部状态栏
  Widget _buildStatusBar() {
    final dotColor = _radarOn ? const Color(0xFF00FF88) : Colors.grey;
    return Container(
      height: 36,
      decoration: BoxDecoration(
        color: const Color(0x4D050A08), // 30% 透明
        border: const Border(top: BorderSide(color: Color(0x3300FF88), width: 0.5)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Row(children: [
        Container(
          width: 8, height: 8,
          decoration: BoxDecoration(shape: BoxShape.circle, color: dotColor),
        ),
        const SizedBox(width: 8),
        Text(
          _radarOn ? 'RADAR ACTIVE' : 'RADAR OFF',
          style: TextStyle(color: dotColor, fontSize: 11, letterSpacing: 1.5, fontWeight: FontWeight.bold),
        ),
        const SizedBox(width: 16),
        Text(
          'FPS $_cameraFrameCount',
          style: const TextStyle(color: Colors.white38, fontSize: 11),
        ),
        const Spacer(),
        if (_torchOn)
          const Icon(Icons.flash_on, color: Color(0xFF00FF88), size: 16),
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
    return ClipRect(child: _buildMotionOverlay());
  }

  Widget _buildMotionOverlay() {
    return LayoutBuilder(
      builder: (context, constraints) {
        final scaleX = constraints.maxWidth / 100;
        final scaleY = constraints.maxHeight / 100;
        // sensorOrientation=90 为原生横屏输出，Android相机已处理方向，无需 Dart 侧旋转
        return Stack(
          children: [
            Positioned.fill(
              child: _cameraTextureId != null
                  ? Transform.rotate(
                      angle: _rotationAngle * 3.141592653589793 / 180.0,
                      child: FittedBox(
                        fit: BoxFit.cover,
                        child: SizedBox(width: 1, height: 1, child: Texture(textureId: _cameraTextureId!)),
                      ),
                    )
                  : Container(
                      color: Colors.black,
                      child: const Center(
                        child: Text('等待图像数据…', style: TextStyle(color: Colors.white54)),
                      ),
                    ),
            ),
            Positioned.fill(child: CustomPaint(painter: _ScanlinesPainter())),
            // 飞行轨迹渲染层
            Positioned.fill(child: CustomPaint(painter: _FlightPathPainter(_flightPath))),
            // 运动检测框渲染层（从环形缓冲区，按存活时间计算透明度）
            ..._activeSlots.map((slot) {
              final ageMs = DateTime.now().millisecondsSinceEpoch - slot.createdAtMs;
              final opacity = (1.0 - (ageMs / _motionKeepMs)).clamp(0.1, 1.0);
              return Positioned(
                left: slot.rect.left * scaleX,
                top: slot.rect.top * scaleY,
                width: (slot.rect.right - slot.rect.left + 1) * scaleX,
                height: (slot.rect.bottom - slot.rect.top + 1) * scaleY,
                child: Container(
                  decoration: BoxDecoration(
                    border: Border.all(color: Colors.redAccent.withOpacity(opacity), width: 2),
                    color: Colors.redAccent.withOpacity(0.15 * opacity),
                  ),
                ),
              );
            }),
            if (_activeSlots.isEmpty && _cameraTextureId != null)
              const Positioned.fill(
                child: Center(
                  child: Text('未检测到运动', style: TextStyle(color: Colors.white30)),
                ),
              ),
          ],
        );
      },
    );
  }

  // 控制面板 — 30% 透明背景，点击相机背景或关闭按钮关闭
  Widget _buildControlPanel() {
    return Container(
      decoration: const BoxDecoration(
        color: Color(0x4D050A08), // 30% 透明
        border: Border(top: BorderSide(color: Color(0x3300FF88), width: 0.5)),
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 拖拽指示条 + 关闭按钮
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    width: 40, height: 4,
                    decoration: BoxDecoration(
                      color: Colors.white24,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const Spacer(),
                  GestureDetector(
                    onTap: () => setState(() => _controlPanelVisible = false),
                    child: Container(
                      padding: const EdgeInsets.all(4),
                      decoration: BoxDecoration(
                        color: Colors.white10,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: const Icon(Icons.close, color: Colors.white54, size: 18),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 14, 20, 8),
              child: Column(children: [
                // 四个主控制按钮横排
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    _ctrlButton(
                      icon: Icons.mic,
                      label: '麦克风',
                      on: _radarOn,
                      onPressed: () async {
                        if (_radarOn) { await _stopRadar(); } else { await _startRadar(); }
                      },
                      color: const Color(0xFF00FF88),
                    ),
                    _ctrlButton(
                      icon: Icons.videocam,
                      label: '视觉',
                      on: _cameraOn,
                      onPressed: () async {
                        if (_cameraOn) { await _stopCamera(); } else { await _startCamera(); }
                      },
                      color: const Color(0xFF00BFFF),
                    ),
                    _ctrlButton(
                      icon: Icons.flashlight_on,
                      label: '补光',
                      on: _torchOn,
                      onPressed: _toggleTorch,
                      color: const Color(0xFFFFB800),
                    ),
                    _ctrlButton(
                      icon: Icons.surround_sound,
                      label: '惊扰音',
                      on: false,
                      onPressed: _playStartle,
                      color: const Color(0xFF9B59B6),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                // 分隔线
                Container(height: 0.5, color: const Color(0x2200FF88)),
                const SizedBox(height: 12),
                // 雷达详细数据行
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    _statItem('方位', _direction ?? '--'),
                    _statItem('距离', _radarOn ? '${(_target.distance * 100).toStringAsFixed(0)}%' : '--'),
                    _statItem('帧', '$_radarFrameCount'),
                    _statItem('L', _radarOn ? '${(_leftEnergy * 100).toStringAsFixed(0)}' : '--',
                        barColor: Colors.cyanAccent, barValue: _leftEnergy),
                    _statItem('R', _radarOn ? '${(_rightEnergy * 100).toStringAsFixed(0)}' : '--',
                        barColor: Colors.pinkAccent, barValue: _rightEnergy),
                  ],
                ),
                const SizedBox(height: 12),
                // 画面旋转控制
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _ctrlButton(
                      icon: Icons.rotate_left,
                      label: '逆时针',
                      on: false,
                      onPressed: _rotateCounterClockwise,
                      color: Colors.white70,
                    ),
                    const SizedBox(width: 16),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                      decoration: BoxDecoration(
                        color: Colors.white10,
                        border: Border.all(color: Colors.white24),
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Text('${_rotationAngle}°', style: const TextStyle(color: Colors.white70, fontSize: 13)),
                    ),
                    const SizedBox(width: 16),
                    _ctrlButton(
                      icon: Icons.rotate_right,
                      label: '顺时针',
                      on: false,
                      onPressed: _rotateClockwise,
                      color: Colors.white70,
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                // 底部日志按钮
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: () { _showLogs(); },
                    icon: const Icon(Icons.bug_report, color: Color(0xFF00FF88), size: 16),
                    label: const Text('查看运行时日志', style: TextStyle(color: Color(0xFF00FF88))),
                    style: OutlinedButton.styleFrom(
                      side: const BorderSide(color: Color(0x3300FF88)),
                      padding: const EdgeInsets.symmetric(vertical: 10),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
              ]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statItem(String label, String value, {Color? barColor, double? barValue}) {
    return Column(children: [
      Text(label, style: const TextStyle(color: Colors.white38, fontSize: 10, letterSpacing: 1)),
      const SizedBox(height: 2),
      if (barColor != null && barValue != null)
        SizedBox(
          width: 40, height: 24,
          child: Stack(alignment: Alignment.bottomCenter, children: [
            Container(width: 40, height: 16, decoration: BoxDecoration(
              color: Colors.white10, borderRadius: BorderRadius.circular(3),
            )),
            FractionallySizedBox(
              heightFactor: barValue.clamp(0.0, 1.0),
              child: Container(width: 40,
                decoration: BoxDecoration(
                  color: barColor,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
            ),
            Positioned(
              bottom: 2,
              child: Text(value, style: const TextStyle(color: Colors.white70, fontSize: 9, fontWeight: FontWeight.bold)),
            ),
          ]),
        )
      else
        Text(value, style: const TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold)),
    ]);
  }

  // 旧版控件（不再使用，保留以防万一）
  Widget _buildControls() => const SizedBox.shrink();

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

// 飞行轨迹渐变渲染：最新=亮绿#00FF88，最老=暗绿#003300
class _FlightPathPainter extends CustomPainter {
  final List<_FlightPoint> path;
  _FlightPathPainter(this.path);

  @override
  void paint(Canvas canvas, Size size) {
    final len = path.length;
    if (len < 2) return;
    final scaleX = size.width / 100;
    final scaleY = size.height / 100;
    for (int i = 1; i < len; i++) {
      final prev = path[i - 1];
      final curr = path[i];
      // 透明度：最新段=1.0，最老段=0.15
      final t = i / len;
      final alpha = 0.15 + 0.85 * t;
      // 绿色分量：最老=0x33，最新=0xFF（纯绿无蓝色调）
      final g = (0x33 + (0xFF - 0x33) * t).round().clamp(0, 255);
      final paint = Paint()
        ..color = Color.fromARGB((alpha * 255).round(), 0, g, 0)
        ..strokeWidth = 2.0
        ..style = PaintingStyle.stroke;
      canvas.drawLine(
        Offset(prev.x * scaleX, prev.y * scaleY),
        Offset(curr.x * scaleX, curr.y * scaleY),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _FlightPathPainter oldDelegate) => true;
}
