import 'package:flutter/material.dart';

import 'src/admin_api.dart';
import 'src/agent_session.dart';
import 'src/pages.dart';

/// The API address is fixed at build time: http only for a local development host, https otherwise.
const apiBase = String.fromEnvironment('WAKA_API_BASE_URL');

void main() {
  final uri = Uri.tryParse(apiBase);
  AdminApi? api;
  try {
    api = uri == null || apiBase.isEmpty ? null : AdminApi(uri);
  } on ArgumentError {
    api = null;
  }
  runApp(WakaAdminApp(session: api == null ? null : AgentSession(api)));
}

class WakaAdminApp extends StatelessWidget {
  const WakaAdminApp({super.key, required this.session});
  final AgentSession? session;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'WAKA — administration',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(colorSchemeSeed: const Color(0xFF0B6E4F), useMaterial3: true),
    home: session == null
        ? const Scaffold(body: Center(child: Text('Adresse du serveur non configurée ou refusée (http seulement en local, sinon https).')))
        : AdminHome(session: session!),
  );
}
