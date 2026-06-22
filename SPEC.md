# 声光猎蚊 App — UI 改版规格说明

## 1. Concept & Vision

**全屏沉浸式 HUD 体验**：把手机变成"蚊子探测器"专用仪器。相机全屏取景作为"探测器镜头"，雷达和运动检测以浮动 HUD 部件叠加其上，控制面板通过点击隐藏/呼出。整体风格参考军事级光电探测仪——暗色调、荧光绿扫描线、高信息密度但层次分明。

## 2. Design Language

### Aesthetic Direction
**军事级光电探测仪（Military-grade Optronic Detector）**
- 参考：军用夜视仪 HUD、雷达显示屏、热成像仪界面
- 深黑底色 + 荧光绿/青色扫描线 + 琥珀色警告
- 信息以"浮动仪器面板"形式叠加在相机画面上

### Color Palette
```
--bg-primary:      #050A08   // 极深军绿黑
--bg-panel:       #0A1510   // 深绿黑（面板背景）
--accent-radar:   #00FF88   // 雷达荧光绿
--accent-motion:  #FF6B35   // 运动检测橙红
--accent-warn:    #FFB800   // 警告琥珀
--text-primary:   #E0F7EF   // 冷白文字
--text-secondary: #5A8A7A   // 次要灰绿
--border:         #1A3028   // 边框暗绿
--overlay:        rgba(5,10,8,0.72)  // 遮罩层
```

### Typography
- **Display / Labels**: `Share Tech Mono`（军用等宽感）
- **Values / Numbers**: `Orbitron`（数字仪表感）
- **Body**: `Inter`（清晰可读）
- Fallback: `monospace`

### Motion Philosophy
- 雷达扫描线：连续旋转动画（1.5s/圈）
- 运动检测框：脉冲闪烁（0.5s fade in/out）
- 控制面板：向上滑入（300ms ease-out）
- 所有 UI 元素：透明度渐变过渡（200ms）

## 3. Layout & Structure

### 主屏幕（全屏 HUD 叠加）

```
┌─────────────────────────────────────────────┐
│  ┌──────────────┐           ┌───────────┐  │
│  │   RADAR HUD  │           │ CAMERA    │  │
│  │  (circular)  │           │ FULL      │  │
│  │   center:    │           │ SCREEN    │  │
│  │   distance   │           │           │  │
│  │   arc: dir   │           │ ┌───────┐ │  │
│  └──────────────┘           │ │motion │ │  │
│                              │ │ rect  │ │  │
│  ┌──────────────────────────┐│ └───────┘ │  │
│  │ [status bar: radar/cam]  │└───────────┘  │
│  └──────────────────────────┘               │
│                                    [TAP ANYWHERE] │
└─────────────────────────────────────────────┘
        ↓ TAP ↓
┌─────────────────────────────────────────────┐
│  OVERLAY BACKDROP (tap to hide)             │
│  ┌───────────────────────────────────────┐  │
│  │         CONTROL PANEL                   │  │
│  │  [麦克风] [视觉] [补光] [惊扰音]        │  │
│  │  ────────────────────────────────────  │  │
│  │  雷达状态: 追踪中    方位: 左前方        │  │
│  │  帧率: 24fps        能量: L ████ R   │  │
│  │  ────────────────────────────────────  │  │
│  │  [DEBUG LOG]                          │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

### 层级说明
1. **Layer 0 — 背景**：相机全屏画面（Texture 组件）
2. **Layer 1 — 扫描线**：半透明网格叠加（CustomPaint）
3. **Layer 2 — 检测框**：运动检测矩形（Positioned overlays）
4. **Layer 3 — 雷达 HUD**：左上角圆形雷达组件（独立 StatelessWidget）
5. **Layer 4 — 状态栏**：底部半透明状态条
6. **Layer 5 — 控制面板**：点击任意处滑出/滑入的全屏半透明面板

### 响应式策略
- 全屏横屏优先（声音猎蚊场景固定横屏使用）
- 各 HUD 元素用 `Positioned` + `FractionalOffset` 定位
- 控制面板高度不超过屏幕 45%

## 4. Features & Interactions

### 相机全屏显示区
- Texture 组件填满整个屏幕（横屏下自动旋转校正）
- 半透明扫描线网格叠加（`CustomPaint`，8px 间隔）
- 运动检测矩形：橙色边框 + 半透明填充，脉冲动画
- 点击屏幕任意位置：切换控制面板显隐

### 雷达 HUD（左上角圆形）
- 圆形，深绿黑背景，荧光绿边框
- 中心：同心圆 + 十字准线
- 旋转扫描线（顺时针，1.5s/圈）
- 目标光点：红色脉冲点，位置由 `azimuth`（左右）和 `distance`（远近）决定
- 底部文字：`方位` + `距离`

### 控制面板（呼出层）
- 从底部向上滑入，`DraggableScrollableSheet` 实现
- 顶部有拖拽指示条（细横条）
- 四颗控制按钮横排：[麦克风] [视觉] [补光] [惊扰音]
- 下方状态信息：雷达帧率、方位、麦克风能量条
- 右下角 [日志] 按钮

### 状态栏（底部固定条）
- 左侧：雷达状态（绿点 + 文字）
- 右侧：视觉状态（绿点 + 文字）+ 帧计数
- 背景：`--overlay`，始终可见

## 5. Component Inventory

### `RadarHud` — 圆形雷达部件
- **States**: radarOff（灰色虚线圆）、radarOn（绿色扫描线+光点）
- **Props**: `distance`（0.0-1.0）、`azimuth`（-1.0到1.0）、`running: bool`
- **Size**: 140×140（固定）

### `MotionRectOverlay` — 运动检测框
- **States**: visible（橙色边框+填充）、fading（0.5s 渐隐）
- **Props**: `rect: MotionRect`

### `ScanlineGrid` — 扫描线网格
- 全屏覆盖，8px 间隔横线，颜色 `rgba(0,255,136,0.06)`

### `ControlPanel` — 控制面板
- **States**: hidden、visible、dragging
- 内含 `ControlChip`（带图标开关按钮）

### `StatusBar` — 底部状态栏
- 高度 36px，始终固定在底部

### `StatusChip` — 状态指示芯片
- 圆角胶囊，左侧状态点（绿/灰/红），右侧文字

## 6. Technical Approach

### Flutter 架构
- `main.dart`：单页 `FindMosHome`，所有状态集中管理
- `_sensorOrientation`：相机传感器方向
- 控制面板显隐：`_controlPanelVisible: bool`，`setState` 切换
- 雷达/相机数据通过既有 `EventChannel` 接收

### 关键实现点
- 相机 Texture 全屏 + `Transform.rotate` 旋转校正
- 雷达 HUD 用 `CustomPaint` + `AnimationController` 驱动扫描线
- 控制面板用 `AnimatedPositioned` + `Visibility` 实现滑入滑出
- 扫描线网格用 `CustomPaint` 单次绘制（`shouldRepaint: false`）
