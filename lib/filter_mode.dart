import 'package:flutter/material.dart';

/// 相机预览滤镜模式
enum FilterMode {
  original('原色', Icons.videocam),
  thermal('热成像', Icons.thermostat_outlined),
  edge('边缘增强', Icons.border_clear),
  invert('反色', Icons.invert_colors);

  final String label;
  final IconData icon;
  const FilterMode(this.label, this.icon);
}
