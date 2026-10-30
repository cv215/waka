import 'vehicle_photo_models.dart';

/// R14 (K33): what the server says about money. The phone never decides an amount or a status.
const paymentPurposes = {'ride', 'voyage', 'subscription', 'topup'};
const paymentMethods = {'cm.mtn', 'cm.orange', 'credit', 'earnings'};
const paymentStates = {'pending', 'succeeded', 'failed', 'review'};
const withdrawalStates = {'pending', 'succeeded', 'failed'};
const pockets = {'credit', 'earnings'};
const walletKinds = {'deposit', 'service_payment', 'settlement', 'withdrawal', 'withdrawal_reversed', 'other'};
/// Founder's rule: withdrawals cost 3 % from the credit, 2 % from earnings, rounded up to the franc.
const withdrawalFeePercent = {'credit': 3, 'earnings': 2};
const topupMin = 500, topupMax = 200000, withdrawMin = 500, withdrawMax = 200000;
final _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

int withdrawalFee(String pocket, int amount) => (amount * withdrawalFeePercent[pocket]! + 99) ~/ 100;

Never _bad() => throw const PhotoProblem(PhotoFailure.invalidResponse);
Map<String, dynamic> _exact(Object? value, Set<String> fields) {
  if (value is! Map<String, dynamic> || value.length != fields.length || !value.keys.every(fields.contains)) _bad();
  return value;
}
int _amount(Object? v) => v is int && v >= 0 && v <= 100000000 ? v : _bad();
DateTime? _time(Object? v, {bool required = true}) {
  if (v == null && !required) return null;
  final parsed = v is String ? DateTime.tryParse(v) : null;
  return parsed ?? _bad();
}
String _id(Object? v) => v is String && _uuid.hasMatch(v) ? v : _bad();
String _in(Object? v, Set<String> allowed) => v is String && allowed.contains(v) ? v : _bad();

class PaymentView {
  const PaymentView(this.paymentId, this.purpose, this.targetId, this.method, this.chargedXaf, this.state, this.applied);
  final String paymentId, purpose, method, state;
  final String? targetId;
  final int chargedXaf;
  final bool applied;
  bool get pending => state == 'pending';

  static PaymentView parse(Object? value) {
    final v = _exact(value, const {'paymentId', 'purpose', 'targetId', 'method', 'chargedXaf', 'creditedXaf', 'feeXaf', 'state',
      'applied', 'createdAt', 'settledAt', 'idempotentReplay'});
    _amount(v['creditedXaf']);
    _amount(v['feeXaf']);
    _time(v['createdAt']);
    _time(v['settledAt'], required: false);
    if (v['applied'] is! bool || v['idempotentReplay'] is! bool) _bad();
    return PaymentView(_id(v['paymentId']), _in(v['purpose'], paymentPurposes), v['targetId'] == null ? null : _id(v['targetId']),
        _in(v['method'], paymentMethods), _amount(v['chargedXaf']), _in(v['state'], paymentStates), v['applied'] as bool);
  }
}

class WithdrawalView {
  const WithdrawalView(this.withdrawalId, this.pocket, this.amountXaf, this.feeXaf, this.state);
  final String withdrawalId, pocket, state;
  final int amountXaf, feeXaf;

  static WithdrawalView parse(Object? value) {
    final v = _exact(value, const {'withdrawalId', 'pocket', 'method', 'amountXaf', 'feeXaf', 'state', 'createdAt', 'settledAt',
      'idempotentReplay'});
    _in(v['method'], const {'cm.mtn', 'cm.orange'});
    _time(v['createdAt']);
    _time(v['settledAt'], required: false);
    if (v['idempotentReplay'] is! bool) _bad();
    return WithdrawalView(_id(v['withdrawalId']), _in(v['pocket'], pockets), _amount(v['amountXaf']), _amount(v['feeXaf']),
        _in(v['state'], withdrawalStates));
  }
}

class WalletLine {
  const WalletLine(this.at, this.pocket, this.amountXaf, this.kind);
  final DateTime at;
  final String pocket, kind;
  /// Positive when money came in, negative when it went out.
  final int amountXaf;
}

class WalletView {
  const WalletView(this.creditXaf, this.earningsXaf, this.lines);
  final int creditXaf, earningsXaf;
  final List<WalletLine> lines;

  static WalletView parse(Object? value) {
    final v = _exact(value, const {'creditXaf', 'earningsXaf', 'lines'});
    final lines = v['lines'];
    if (lines is! List || lines.length > 50) _bad();
    return WalletView(_amount(v['creditXaf']), _amount(v['earningsXaf']), List.unmodifiable([
      for (final line in lines)
        (() {
          final l = _exact(line, const {'at', 'pocket', 'amountXaf', 'kind'});
          final amount = l['amountXaf'];
          if (amount is! int || amount == 0 || amount.abs() > 100000000) _bad();
          return WalletLine(_time(l['at'])!, _in(l['pocket'], pockets), amount, _in(l['kind'], walletKinds));
        })(),
    ]));
  }
}

/// A private driver's subscription period (K32): awaiting payment until the provider confirms it.
class SubscriptionPeriodView {
  const SubscriptionPeriodView(this.periodId, this.state, this.priceXaf, this.startsAt, this.endsAt);
  final String periodId, state;
  final int priceXaf;
  final DateTime? startsAt, endsAt;

  static SubscriptionPeriodView parse(Object? value) {
    final v = _exact(value, const {'periodId', 'state', 'priceXaf', 'startsAt', 'endsAt', 'createdAt'});
    _time(v['createdAt']);
    return SubscriptionPeriodView(_id(v['periodId']), _in(v['state'], const {'awaiting_payment', 'paid', 'refunded'}),
        _amount(v['priceXaf']), _time(v['startsAt'], required: false), _time(v['endsAt'], required: false));
  }

  static List<SubscriptionPeriodView> parseList(Object? value) {
    if (value is! List || value.length > 50) _bad();
    return List.unmodifiable(value.map(parse));
  }
}
