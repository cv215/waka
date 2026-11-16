import 'dart:async';

import 'package:flutter/material.dart';

/// Le défilement conserve les actions sur petit écran ou avec du texte agrandi.
class WakaPage extends StatelessWidget {
  const WakaPage({
    required this.title,
    required this.child,
    this.actions,
    this.busy = false,
    super.key,
  });

  final String title;
  final Widget child;
  final List<Widget>? actions;
  /// An action waits for the server: a thin bar under the title, and a sentence if it lasts (see [BusyNotice]).
  final bool busy;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title), actions: actions,
          bottom: busy ? const PreferredSize(preferredSize: Size.fromHeight(3), child: LinearProgressIndicator(minHeight: 3)) : null),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          child: Align(
            alignment: Alignment.topCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: SizedBox(width: double.infinity, child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                BusyNotice(busy: busy, bar: false),
                child,
              ])),
            ),
          ),
        ),
      ),
    );
  }
}

/// While an action waits for the server: a thin bar at once (unless the page draws its own), then after [after] a
/// sentence saying it is still going, so a slow network never looks like a frozen button. Requests give up by
/// themselves after 8 seconds and the screen then shows what went wrong.
class BusyNotice extends StatefulWidget {
  const BusyNotice({required this.busy, this.bar = true, this.after = const Duration(seconds: 3), super.key});

  final bool busy;
  final bool bar;
  final Duration after;

  @override
  State<BusyNotice> createState() => _BusyNoticeState();
}

class _BusyNoticeState extends State<BusyNotice> {
  Timer? _timer;
  bool _slow = false;

  @override
  void initState() {
    super.initState();
    _arm();
  }

  @override
  void didUpdateWidget(BusyNotice old) {
    super.didUpdateWidget(old);
    if (old.busy != widget.busy) _arm();
  }

  void _arm() {
    _timer?.cancel();
    _slow = false;
    if (widget.busy) _timer = Timer(widget.after, () { if (mounted) setState(() => _slow = true); });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.busy) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
      if (widget.bar) const LinearProgressIndicator(minHeight: 3),
      if (_slow) Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Semantics(liveRegion: true, child: Text(
          'Ça prend un peu de temps : WAKA attend la réponse du serveur. Garde l’app ouverte, rien ne sera fait en double.',
          style: TextStyle(color: scheme.onSurfaceVariant))),
      ),
    ]);
  }
}

/// Step 7 of the redesign: a small turning wheel while a screen reads the server, instead of the word « Chargement ».
class WakaLoading extends StatelessWidget {
  const WakaLoading({super.key});

  @override
  Widget build(BuildContext context) => const Center(child: Padding(
    padding: EdgeInsets.all(24),
    child: SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 3, semanticsLabel: 'Chargement')),
  ));
}

class InformationPanel extends StatelessWidget {
  const InformationPanel({
    required this.title,
    required this.description,
    required this.icon,
    super.key,
  });

  final String title;
  final String description;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: theme.colorScheme.surface,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ExcludeSemantics(
              child: Icon(icon, color: theme.colorScheme.primary, size: 28),
            ),
            const SizedBox(height: 12),
            Semantics(
              header: true,
              child: Text(
                title,
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(description, style: theme.textTheme.bodyLarge),
          ],
        ),
      ),
    );
  }
}
