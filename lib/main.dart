import 'package:flutter/material.dart';

void main() {
  runApp(const FindMosApp());
}

class FindMosApp extends StatelessWidget {
  const FindMosApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Find MOS',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Find MOS')),
      body: const Center(
        child: Text('Welcome to Find MOS'),
      ),
    );
  }
}
