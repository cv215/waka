import 'dart:async';
import 'dart:convert';
import '../../../core/config/local_api_configuration.dart';
import '../../../core/net/net.dart';
import 'driver_access_models.dart';
import 'driver_application_models.dart';
import 'driver_document_models.dart';
import 'login_models.dart';
import 'payment_models.dart';
import 'privacy_models.dart';
import 'profile_models.dart';
import 'referral_models.dart';
import 'ride_models.dart';
import 'vehicle_photo_models.dart';
import 'voyage_models.dart';

abstract interface class LoginGateway {
  Future<LoginChallenge> request(LoginAttempt attempt, String client);
  Future<LoginSession> verify(LoginAttempt attempt, String client, String code);
  Future<LoginAccount> me(LoginSession session, String client);
  Future<void> revoke(LoginSession session, String client, String key);
  void dispose();
}

abstract interface class ProfileGateway {
  Future<ProfileSnapshot> profile(LoginSession session, String client);
  Future<ProfileSnapshot> updateProfile(LoginSession session, String client, ProfileCommand command);
  void dispose();
}

/// Draft candidature routes (A26-A28 limited to the vehicle). No admission is ever read here.
abstract interface class DriverApplicationGateway {
  /// Null when the account has no open candidature (404).
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession session, String client);
  Future<DriverApplicationSnapshot> sendApplication(LoginSession session, String client, ApplicationCommand command);
  void dispose();
}

/// The six vehicle views of the owner's draft (K16). Upload is a raw JPEG or PNG body.
abstract interface class VehiclePhotoGateway {
  Future<List<PhotoViewStatus>> listPhotos(LoginSession session, String client, String applicationId);
  Future<PhotoUpload> uploadPhoto(LoginSession session, String client, String applicationId, String view,
      String key, List<int> bytes, String contentType);
  void dispose();
}

/// The driver's own effective access (K23), read only.
abstract interface class DriverAccessGateway {
  Future<DriverAccessView> driverAccess(LoginSession session, String client);
  void dispose();
}

/// Intercity voyages (K25): listings, seat reservations and cancellations. No payment here yet.
abstract interface class VoyageGateway {
  Future<List<VoyageView>> upcomingVoyages(LoginSession session, String client);
  Future<List<VoyageView>> myVoyages(LoginSession session, String client);
  Future<VoyageView> publishVoyage(LoginSession session, String client, String key, List<VoyageStop> stops, int seats,
      Map<String, int> prices);
  Future<VoyageView> cancelVoyage(LoginSession session, String client, String voyageId, String reasonCode);
  Future<ReservationView> reserveVoyage(LoginSession session, String client, String key, String voyageId, int from, int to,
      int seats);
  Future<List<ReservationView>> myVoyageReservations(LoginSession session, String client);
  Future<ReservationView> cancelVoyageReservation(LoginSession session, String client, String reservationId);
  Future<List<PassengerView>> voyagePassengers(LoginSession session, String client, String voyageId);
  Future<PassengerView> boardPassenger(LoginSession session, String client, String voyageId, String code);
  Future<PassengerView> markNoShow(LoginSession session, String client, String voyageId, String reservationId);
  Future<IncidentView> reportIncident(LoginSession session, String client, String key, String reservationId, String category, String text);
  Future<List<IncidentView>> myIncidents(LoginSession session, String client);
  Future<IncidentView> incidentMessage(LoginSession session, String client, String key, String incidentId, String kind, String text);
  void dispose();
}

/// Money (K33): pay a service or top up, the two pockets, withdrawals; a private driver's subscription (K32).
abstract interface class PaymentGateway {
  Future<PaymentView> pay(LoginSession session, String client, String key, String purpose, String? targetId, String method,
      String? phone, int? amountXaf);
  Future<PaymentView> payment(LoginSession session, String client, String paymentId);
  Future<WalletView> wallet(LoginSession session, String client);
  Future<WithdrawalView> withdraw(LoginSession session, String client, String key, String pocket, int amountXaf, String method,
      String phone);
  Future<WithdrawalView> withdrawal(LoginSession session, String client, String withdrawalId);
  Future<SubscriptionPeriodView> buySubscription(LoginSession session, String client, String key);
  Future<List<SubscriptionPeriodView>> subscriptions(LoginSession session, String client);
  void dispose();
}

/// City rides (K31) and the address search behind them (K35). Prices and distances are the server's.
abstract interface class RideGateway {
  Future<List<PlaceSuggestion>> placeSuggestions(LoginSession session, String client, String query, String sessionToken);
  Future<RidePoint> placeLocation(LoginSession session, String client, String placeId, String sessionToken);
  Future<RideQuoteView> quoteRide(LoginSession session, String client, String key, String mode, int passengerCount,
      RidePoint origin, RidePoint destination);
  Future<RideSearchView> startRideSearch(LoginSession session, String client, String key, String quoteId);
  Future<RideSearchView> rideSearch(LoginSession session, String client, String searchId);
  Future<RideSearchView> cancelRideSearch(LoginSession session, String client, String searchId);
  Future<CityRideView> cityRide(LoginSession session, String client, String rideId);
  Future<CityRideView> cancelCityRide(LoginSession session, String client, String rideId);
  /// The account's ride in progress (as client or driver), or null.
  Future<CityRideView?> activeRide(LoginSession session, String client);
  void dispose();
}

/// Driver side of city rides (K31): presence with position, proposals, and the ride's steps.
abstract interface class DriverRideGateway {
  Future<DriverPresenceView> setPresence(LoginSession session, String client, bool online, RidePoint? position);
  Future<List<DriverProposalView>> driverProposals(LoginSession session, String client);
  /// The ride on acceptance; null on refusal.
  Future<CityRideView?> answerProposal(LoginSession session, String client, String proposalId, bool accept);
  Future<CityRideView> driverRideAction(LoginSession session, String client, String rideId, String action, String? boardingCode);
  Future<CityRideView> cityRide(LoginSession session, String client, String rideId);
  Future<CityRideView?> activeRide(LoginSession session, String client);
  void dispose();
}

/// Own referral code and the single referrer attachment (K22).
abstract interface class ReferralGateway {
  Future<ReferralView> referral(LoginSession session, String client);
  Future<LinkageView> attachReferrer(LoginSession session, String client, String code);
  void dispose();
}

/// Requests about one's own data and the account's own activity (K21).
abstract interface class PrivacyGateway {
  Future<PrivacyRequestView> requestPrivacy(LoginSession session, String client, String key, String kind, String? details);
  Future<ActivityPage> activity(LoginSession session, String client, String? cursor);
  void dispose();
}

/// Catalogue documents of the owner's file, and the decisions about it (K17, K18).
abstract interface class DriverDocumentGateway {
  Future<List<RequirementView>> requirements(LoginSession session, String client, String applicationId);
  Future<List<DecisionView>> decisions(LoginSession session, String client, String applicationId);
  Future<DocumentUpload> uploadDocument(LoginSession session, String client, String applicationId, String code,
      String key, List<int> bytes, String contentType, DateTime? validUntil);
  void dispose();
}

class LoginClient
    implements LoginGateway, ProfileGateway, DriverApplicationGateway, VehiclePhotoGateway, DriverDocumentGateway,
        PrivacyGateway, ReferralGateway, DriverAccessGateway, VoyageGateway, PaymentGateway, RideGateway, DriverRideGateway {
  LoginClient(this.configuration);

  @override
  Future<DriverPresenceView> setPresence(LoginSession session, String client, bool online, RidePoint? position) async =>
      DriverPresenceView.parse(await _photoSend(session, client, 'PUT', '/api/v1/driver/presence', 200,
          contentType: 'application/json', bytes: _json({'online': online, ...?position?.toJson()})));

  @override
  Future<List<DriverProposalView>> driverProposals(LoginSession session, String client) async =>
      DriverProposalView.parseList(await _photoSend(session, client, 'GET', '/api/v1/driver/proposals', 200, maxBytes: 16384));

  @override
  Future<CityRideView?> answerProposal(LoginSession session, String client, String proposalId, bool accept) async {
    if (!isLoginUuid(proposalId)) throw const PhotoProblem(PhotoFailure.invalid);
    final body = await _photoSend(session, client, 'POST', '/api/v1/proposals/$proposalId/responses', accept ? 201 : 204,
        contentType: 'application/json', bytes: _json({'action': accept ? 'accept' : 'refuse'}));
    return accept ? CityRideView.parse(body) : null;
  }

  @override
  Future<CityRideView> driverRideAction(LoginSession session, String client, String rideId, String action, String? boardingCode) async {
    if (!isLoginUuid(rideId) || !{'arrive', 'board', 'finish', 'client_absent', 'cancel'}.contains(action) ||
        (boardingCode != null && !RegExp(r'^[0-9]{6}$').hasMatch(boardingCode))) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return CityRideView.parse(await _photoSend(session, client, 'POST', '/api/v1/rides/$rideId/driver-actions', 200,
        contentType: 'application/json', bytes: _json({'action': action, 'boardingCode': ?boardingCode})));
  }

  List<int> _json(Object? body) => utf8.encode(jsonEncode(body));

  @override
  Future<List<PlaceSuggestion>> placeSuggestions(LoginSession session, String client, String query, String sessionToken) async =>
      PlaceSuggestion.parseList(await _photoSend(session, client, 'POST', '/api/v1/places/suggestions', 200,
          contentType: 'application/json', bytes: _json({'query': query, 'sessionToken': sessionToken}), maxBytes: 16384));

  @override
  Future<RidePoint> placeLocation(LoginSession session, String client, String placeId, String sessionToken) async =>
      parsePlaceLocation(await _photoSend(session, client, 'POST', '/api/v1/places/locations', 200,
          contentType: 'application/json', bytes: _json({'placeId': placeId, 'sessionToken': sessionToken})), placeId);

  @override
  Future<RideQuoteView> quoteRide(LoginSession session, String client, String key, String mode, int passengerCount,
      RidePoint origin, RidePoint destination) async {
    if (!isLoginUuid(key) || !{'shared', 'vip'}.contains(mode) || passengerCount < 1 || passengerCount > 5) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return RideQuoteView.parse(await _photoSend(session, client, 'POST', '/api/v1/quotes', 201, alsoExpected: 200, key: key,
        contentType: 'application/json', bytes: _json({'mode': mode, 'passengerCount': passengerCount,
          'origin': origin.toJson(), 'destination': destination.toJson()})));
  }

  @override
  Future<RideSearchView> startRideSearch(LoginSession session, String client, String key, String quoteId) async {
    if (!isLoginUuid(key) || !isLoginUuid(quoteId)) throw const PhotoProblem(PhotoFailure.invalid);
    return RideSearchView.parse(await _photoSend(session, client, 'POST', '/api/v1/searches', 201, alsoExpected: 200, key: key,
        contentType: 'application/json', bytes: _json({'quoteId': quoteId})));
  }

  @override
  Future<RideSearchView> rideSearch(LoginSession session, String client, String searchId) async {
    if (!isLoginUuid(searchId)) throw const PhotoProblem(PhotoFailure.invalid);
    return RideSearchView.parse(await _photoSend(session, client, 'GET', '/api/v1/searches/$searchId', 200));
  }

  @override
  Future<RideSearchView> cancelRideSearch(LoginSession session, String client, String searchId) async {
    if (!isLoginUuid(searchId)) throw const PhotoProblem(PhotoFailure.invalid);
    return RideSearchView.parse(await _photoSend(session, client, 'POST', '/api/v1/searches/$searchId/cancellations', 200));
  }

  @override
  Future<CityRideView> cityRide(LoginSession session, String client, String rideId) async {
    if (!isLoginUuid(rideId)) throw const PhotoProblem(PhotoFailure.invalid);
    return CityRideView.parse(await _photoSend(session, client, 'GET', '/api/v1/rides/$rideId', 200));
  }

  @override
  Future<CityRideView?> activeRide(LoginSession session, String client) async {
    try {
      return CityRideView.parse(await _photoSend(session, client, 'GET', '/api/v1/me/active-ride', 200));
    } on PhotoProblem catch (problem) {
      if (problem.failure == PhotoFailure.notFound) return null; // nothing in progress
      rethrow;
    }
  }

  @override
  Future<CityRideView> cancelCityRide(LoginSession session, String client, String rideId) async {
    if (!isLoginUuid(rideId)) throw const PhotoProblem(PhotoFailure.invalid);
    return CityRideView.parse(await _photoSend(session, client, 'POST', '/api/v1/rides/$rideId/cancellations', 200));
  }
  final LocalApiConfiguration configuration;
  final Set<HttpClient> _active = {};
  bool _closed = false;

  @override
  Future<List<PhotoViewStatus>> listPhotos(LoginSession session, String client, String applicationId) async =>
      PhotoViewStatus.parseList(await _photoSend(session, client, 'GET', '/api/v1/driver-applications/$applicationId/photos', 200));

  @override
  Future<PhotoUpload> uploadPhoto(LoginSession session, String client, String applicationId, String view,
      String key, List<int> bytes, String contentType) async {
    if (!vehiclePhotoViews.contains(view) || photoContentType(bytes) != contentType || bytes.length > maxPhotoBytes) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return PhotoUpload.parse(
      await _photoSend(session, client, 'POST', '/api/v1/driver-applications/$applicationId/photos/$view', 201,
          key: key, bytes: bytes, contentType: contentType),
      applicationId,
      view,
    );
  }

  @override
  Future<List<RequirementView>> requirements(LoginSession session, String client, String applicationId) async =>
      RequirementView.parseList(await _photoSend(session, client, 'GET',
          '/api/v1/driver-applications/$applicationId/requirements', 200, maxBytes: 32768));

  @override
  Future<List<DecisionView>> decisions(LoginSession session, String client, String applicationId) async =>
      DecisionView.parseList(await _photoSend(session, client, 'GET',
          '/api/v1/driver-applications/$applicationId/decisions', 200, maxBytes: 65536));

  @override
  Future<DocumentUpload> uploadDocument(LoginSession session, String client, String applicationId, String code,
      String key, List<int> bytes, String contentType, DateTime? validUntil) async {
    if (!RegExp(r'^[A-Z][A-Z0-9_]{2,47}$').hasMatch(code) || photoContentType(bytes) != contentType ||
        bytes.length > maxPhotoBytes) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return DocumentUpload.parse(
      await _photoSend(session, client, 'POST', '/api/v1/driver-applications/$applicationId/documents/$code', 201,
          key: key, bytes: bytes, contentType: contentType,
          extra: {if (validUntil != null) 'X-Waka-Valid-Until': dayText(validUntil)}),
      code,
    );
  }

  @override
  Future<PrivacyRequestView> requestPrivacy(LoginSession session, String client, String key, String kind,
      String? details) async {
    if (!privacyKinds.contains(kind) || !isLoginUuid(key)) throw const PhotoProblem(PhotoFailure.invalid);
    return PrivacyRequestView.parse(await _photoSend(session, client, 'POST', '/api/v1/me/privacy-requests', 201,
        alsoExpected: 200, key: key, contentType: 'application/json',
        bytes: utf8.encode(jsonEncode({'kind': kind, 'details': details}))));
  }

  @override
  Future<ActivityPage> activity(LoginSession session, String client, String? cursor) async {
    if (cursor != null && !RegExp(r'^[0-9a-f_-]{1,64}$').hasMatch(cursor)) throw const PhotoProblem(PhotoFailure.invalid);
    return ActivityPage.parse(await _photoSend(session, client, 'GET',
        '/api/v1/me/activity?limit=20${cursor == null ? '' : '&cursor=$cursor'}', 200, maxBytes: 32768));
  }

  @override
  Future<ReferralView> referral(LoginSession session, String client) async =>
      ReferralView.parse(await _photoSend(session, client, 'GET', '/api/v1/me/referral', 200));

  /// Naturally idempotent on the server: resending the same code finds the same attachment.
  @override
  Future<LinkageView> attachReferrer(LoginSession session, String client, String code) async {
    if (!referralCodePattern.hasMatch(code)) throw const PhotoProblem(PhotoFailure.invalid);
    return LinkageView.parse(await _photoSend(session, client, 'POST', '/api/v1/me/referral-linkages', 201,
        alsoExpected: 200, contentType: 'application/json', bytes: utf8.encode(jsonEncode({'referralCode': code}))));
  }

  @override
  Future<PaymentView> pay(LoginSession session, String client, String key, String purpose, String? targetId, String method,
      String? phone, int? amountXaf) async {
    if (!isLoginUuid(key) || !paymentPurposes.contains(purpose) || !paymentMethods.contains(method)
        || (targetId != null && !isLoginUuid(targetId))) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return PaymentView.parse(await _photoSend(session, client, 'POST', '/api/v1/payments', 201, alsoExpected: 200, key: key,
        contentType: 'application/json', bytes: utf8.encode(jsonEncode({
          'purpose': purpose, 'method': method,
          'targetId': ?targetId,
          'phone': ?phone,
          'amountXaf': ?amountXaf,
        }))));
  }

  @override
  Future<PaymentView> payment(LoginSession session, String client, String paymentId) async {
    if (!isLoginUuid(paymentId)) throw const PhotoProblem(PhotoFailure.invalid);
    return PaymentView.parse(await _photoSend(session, client, 'GET', '/api/v1/payments/$paymentId', 200));
  }

  @override
  Future<WalletView> wallet(LoginSession session, String client) async =>
      WalletView.parse(await _photoSend(session, client, 'GET', '/api/v1/wallet', 200, maxBytes: 65536));

  @override
  Future<WithdrawalView> withdraw(LoginSession session, String client, String key, String pocket, int amountXaf, String method,
      String phone) async {
    if (!isLoginUuid(key) || !pockets.contains(pocket) || !{'cm.mtn', 'cm.orange'}.contains(method)) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return WithdrawalView.parse(await _photoSend(session, client, 'POST', '/api/v1/withdrawals', 201, alsoExpected: 200, key: key,
        contentType: 'application/json',
        bytes: utf8.encode(jsonEncode({'pocket': pocket, 'amountXaf': amountXaf, 'method': method, 'phone': phone}))));
  }

  @override
  Future<WithdrawalView> withdrawal(LoginSession session, String client, String withdrawalId) async {
    if (!isLoginUuid(withdrawalId)) throw const PhotoProblem(PhotoFailure.invalid);
    return WithdrawalView.parse(await _photoSend(session, client, 'GET', '/api/v1/withdrawals/$withdrawalId', 200));
  }

  @override
  Future<SubscriptionPeriodView> buySubscription(LoginSession session, String client, String key) async {
    if (!isLoginUuid(key)) throw const PhotoProblem(PhotoFailure.invalid);
    return SubscriptionPeriodView.parse(await _photoSend(session, client, 'POST', '/api/v1/subscription-purchases', 201,
        alsoExpected: 200, key: key, bytes: const [], contentType: 'application/json'));
  }

  @override
  Future<List<SubscriptionPeriodView>> subscriptions(LoginSession session, String client) async =>
      SubscriptionPeriodView.parseList(await _photoSend(session, client, 'GET', '/api/v1/driver/subscriptions', 200, maxBytes: 32768));

  @override
  Future<DriverAccessView> driverAccess(LoginSession session, String client) async =>
      DriverAccessView.parse(await _photoSend(session, client, 'GET', '/api/v1/driver/access', 200));

  @override
  Future<List<VoyageView>> upcomingVoyages(LoginSession session, String client) async =>
      VoyageView.parseList(await _photoSend(session, client, 'GET', '/api/v1/voyages', 200, maxBytes: 262144));

  @override
  Future<List<VoyageView>> myVoyages(LoginSession session, String client) async =>
      VoyageView.parseList(await _photoSend(session, client, 'GET', '/api/v1/driver/voyages', 200, maxBytes: 262144));

  @override
  Future<VoyageView> publishVoyage(LoginSession session, String client, String key, List<VoyageStop> stops, int seats,
      Map<String, int> prices) async {
    if (!isLoginUuid(key)) throw const PhotoProblem(PhotoFailure.invalid);
    return VoyageView.parse(await _photoSend(session, client, 'POST', '/api/v1/voyages', 201, alsoExpected: 200, key: key,
        contentType: 'application/json', maxBytes: 16384,
        bytes: utf8.encode(jsonEncode({'stops': [for (final s in stops) s.toJson()], 'seats': seats, 'prices': prices}))));
  }

  @override
  Future<VoyageView> cancelVoyage(LoginSession session, String client, String voyageId, String reasonCode) async {
    if (!isLoginUuid(voyageId)) throw const PhotoProblem(PhotoFailure.invalid);
    return VoyageView.parse(await _photoSend(session, client, 'POST', '/api/v1/voyages/$voyageId/cancellations', 200,
        contentType: 'application/json', maxBytes: 16384, bytes: utf8.encode(jsonEncode({'reasonCode': reasonCode}))));
  }

  @override
  Future<ReservationView> reserveVoyage(LoginSession session, String client, String key, String voyageId, int from, int to,
      int seats) async {
    if (!isLoginUuid(key) || !isLoginUuid(voyageId)) throw const PhotoProblem(PhotoFailure.invalid);
    return ReservationView.parse(await _photoSend(session, client, 'POST', '/api/v1/voyages/$voyageId/reservations', 201,
        alsoExpected: 200, key: key, contentType: 'application/json',
        bytes: utf8.encode(jsonEncode({'fromIndex': from, 'toIndex': to, 'seats': seats}))));
  }

  @override
  Future<List<ReservationView>> myVoyageReservations(LoginSession session, String client) async =>
      ReservationView.parseList(await _photoSend(session, client, 'GET', '/api/v1/me/voyage-reservations', 200, maxBytes: 131072));

  @override
  Future<ReservationView> cancelVoyageReservation(LoginSession session, String client, String reservationId) async {
    if (!isLoginUuid(reservationId)) throw const PhotoProblem(PhotoFailure.invalid);
    return ReservationView.parse(await _photoSend(session, client, 'POST', '/api/v1/voyage-reservations/$reservationId/cancellations',
        200, bytes: const [], contentType: 'application/json'));
  }

  @override
  Future<List<PassengerView>> voyagePassengers(LoginSession session, String client, String voyageId) async {
    if (!isLoginUuid(voyageId)) throw const PhotoProblem(PhotoFailure.invalid);
    return PassengerView.parseList(await _photoSend(session, client, 'GET', '/api/v1/voyages/$voyageId/passengers', 200, maxBytes: 65536));
  }

  @override
  Future<PassengerView> boardPassenger(LoginSession session, String client, String voyageId, String code) async {
    if (!isLoginUuid(voyageId) || !RegExp(r'^[0-9]{6}$').hasMatch(code)) throw const PhotoProblem(PhotoFailure.invalid);
    return PassengerView.parse(await _photoSend(session, client, 'POST', '/api/v1/voyages/$voyageId/boardings', 200,
        contentType: 'application/json', bytes: utf8.encode(jsonEncode({'boardingCode': code}))));
  }

  @override
  Future<PassengerView> markNoShow(LoginSession session, String client, String voyageId, String reservationId) async {
    if (!isLoginUuid(voyageId) || !isLoginUuid(reservationId)) throw const PhotoProblem(PhotoFailure.invalid);
    return PassengerView.parse(await _photoSend(session, client, 'POST', '/api/v1/voyages/$voyageId/no-shows', 200,
        contentType: 'application/json', bytes: utf8.encode(jsonEncode({'reservationId': reservationId}))));
  }

  @override
  Future<IncidentView> reportIncident(LoginSession session, String client, String key, String reservationId, String category,
      String text) async {
    if (!isLoginUuid(key) || !isLoginUuid(reservationId) || !incidentCategories.containsKey(category)) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return IncidentView.parse(await _photoSend(session, client, 'POST', '/api/v1/incidents', 201, alsoExpected: 200, key: key,
        contentType: 'application/json', maxBytes: 131072,
        bytes: utf8.encode(jsonEncode({'reservationId': reservationId, 'category': category, 'description': text}))));
  }

  @override
  Future<List<IncidentView>> myIncidents(LoginSession session, String client) async =>
      IncidentView.parseList(await _photoSend(session, client, 'GET', '/api/v1/me/incidents', 200, maxBytes: 524288));

  @override
  Future<IncidentView> incidentMessage(LoginSession session, String client, String key, String incidentId, String kind,
      String text) async {
    if (!isLoginUuid(key) || !isLoginUuid(incidentId) || !{'reply', 'contest'}.contains(kind)) throw const PhotoProblem(PhotoFailure.invalid);
    return IncidentView.parse(await _photoSend(session, client, 'POST', '/api/v1/incidents/$incidentId/messages', 201, alsoExpected: 200,
        key: key, contentType: 'application/json', maxBytes: 131072, bytes: utf8.encode(jsonEncode({'kind': kind, 'text': text}))));
  }

  Future<Object?> _photoSend(LoginSession session, String client, String method, String path, int expected,
      {String? key, List<int>? bytes, String? contentType, Map<String, String> extra = const {},
      int maxBytes = 8192, int? alsoExpected}) async {
    if (_closed || !configuration.isConfigured) throw const PhotoProblem(PhotoFailure.unavailable);
    final http = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(http);
    try {
      // A photo upload may take longer on a slow mobile network than the small JSON calls.
      return await (() async {
        final request = await http.openUrl(method, configuration.baseUri!.resolve(path));
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json, application/problem+json');
        request.headers.set('Authorization', 'Bearer ${session.token}');
        request.headers.set('X-Waka-Client-Instance', client);
        extra.forEach(request.headers.set);
        if (bytes != null) {
          if (key != null) request.headers.set('Idempotency-Key', key);
          request.headers.set(HttpHeaders.contentTypeHeader, contentType!);
          request.contentLength = bytes.length;
          request.add(bytes);
        }
        final response = await request.close();
        final received = <int>[];
        await for (final chunk in response) {
          if (received.length + chunk.length > maxBytes) throw const PhotoProblem(PhotoFailure.invalidResponse);
          received.addAll(chunk);
        }
        if (response.headers.value(HttpHeaders.cacheControlHeader) != 'no-store') {
          throw const PhotoProblem(PhotoFailure.invalidResponse);
        }
        final status = response.statusCode;
        if (status == expected || status == alsoExpected) {
          if (status == 204) return null; // no content: the caller asked for it (a refused proposal)
          if (response.headers.contentType?.mimeType != 'application/json') throw const PhotoProblem(PhotoFailure.invalidResponse);
          return jsonDecode(utf8.decode(received));
        }
        if (response.headers.contentType?.mimeType != 'application/problem+json') {
          throw const PhotoProblem(PhotoFailure.invalidResponse);
        }
        final problem = jsonDecode(utf8.decode(received));
        final code = problem is Map && problem['code'] is String ? problem['code'] as String : null;
        throw PhotoProblem(switch ((status, code)) {
          (403, 'DRIVER_NOT_ALLOWED') => PhotoFailure.driverNotAllowed,
          (403, 'NOT_PRIVATE_DRIVER') => PhotoFailure.driverNotAllowed,
          (401 || 403, _) => PhotoFailure.denied,
          (404, _) => PhotoFailure.notFound,
          (400, 'FILE_REJECTED') => PhotoFailure.rejectedFile,
          (400, 'DOCUMENT_EXPIRED') => PhotoFailure.expired,
          (409, 'DOCUMENT_NOT_APPLICABLE') => PhotoFailure.notApplicable,
          (409, 'REGISTRATION_REQUIRED') => PhotoFailure.registrationRequired,
          (409, 'IDEMPOTENCY_KEY_REUSED') => PhotoFailure.keyReused,
          (409, 'NOT_EDITABLE') => PhotoFailure.notEditable,
          (422, 'SELF_REFERRAL') => PhotoFailure.selfReferral,
          (422, 'CODE_NOT_RECOGNIZED') => PhotoFailure.codeNotRecognized,
          (409, 'REFERRER_ALREADY_SET') => PhotoFailure.referrerAlreadySet,
          (409, 'NOT_ENOUGH_SEATS') => PhotoFailure.notEnoughSeats,
          (422, 'OWN_VOYAGE') => PhotoFailure.ownVoyage,
          (422, 'DEPARTED') => PhotoFailure.departed,
          (422, 'TOO_MANY_SEATS') => PhotoFailure.tooManySeats,
          (422, 'WRONG_CODE') => PhotoFailure.wrongCode,
          (409, 'NOT_PAID') => PhotoFailure.notPaid,
          (429, 'BOARDING_LOCKED') => PhotoFailure.boardingLocked,
          (422, 'MUTUAL_REFERRAL') => PhotoFailure.mutualReferral,
          (422, 'WINDOW_CLOSED') => PhotoFailure.referralWindowClosed,
          (429, 'TOO_MANY_ATTEMPTS') => PhotoFailure.tooManyAttempts,
          (422, 'TOO_EARLY') => PhotoFailure.tooEarly,
          (409, 'NOT_PAYABLE') => PhotoFailure.notPayable,
          (409, 'INSUFFICIENT_CREDIT') => PhotoFailure.insufficientCredit,
          (409, 'CREDIT_LIMIT') => PhotoFailure.creditLimit,
          (422, 'PHONE_NOT_ACCOUNT') => PhotoFailure.phoneNotAccount,
          (409, 'TRIAL_NOT_STARTED') => PhotoFailure.notPayable,
          (422, 'OUTSIDE_SERVICE_AREA') => PhotoFailure.outsideServiceArea,
          (422, 'NO_ROUTE' || 'DISTANCE_OUT_OF_RANGE') => PhotoFailure.noRoute,
          (409, 'QUOTE_EXPIRED' || 'PROPOSAL_EXPIRED') => PhotoFailure.expired,
          (409, 'CAR_BUSY' || 'NOT_BOARDED' || 'NOT_ARRIVED' || 'ALREADY_BOARDED') => PhotoFailure.notEditable,
          (413, _) => PhotoFailure.tooLarge,
          (415, _) => PhotoFailure.unsupported,
          (>= 500, _) => PhotoFailure.unavailable,
          _ => PhotoFailure.invalidResponse,
        });
      })().timeout(Duration(seconds: bytes == null ? 8 : 60));
    } on PhotoProblem {
      rethrow;
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on Exception {
      throw const PhotoProblem(PhotoFailure.uncertain);
    } finally {
      http.close(force: true);
      _active.remove(http);
    }
  }

  @override
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession session, String client) async {
    final (status, body) = await _applicationSend(session, client, 'GET', '/api/v1/me/driver-application');
    return status == 404 ? null : DriverApplicationSnapshot.parse(body, session);
  }

  @override
  Future<DriverApplicationSnapshot> sendApplication(
    LoginSession session,
    String client,
    ApplicationCommand command,
  ) async {
    final (status, body) = switch (command) {
      CreateApplicationCommand c => await _applicationSend(session, client, 'POST', '/api/v1/driver-applications',
          command: c, body: {'requestedCategory': c.requestedCategory}),
      VehicleApplicationCommand c => await _applicationSend(session, client, 'PATCH',
          '/api/v1/driver-applications/${c.applicationId}',
          command: c, body: {'expectedVersion': c.expectedVersion, 'vehicleFields': c.vehicle.toJson()}),
      SubmitApplicationCommand c => await _applicationSend(session, client, 'POST',
          '/api/v1/driver-applications/${c.applicationId}/submissions',
          command: c, body: {'expectedVersion': c.expectedVersion}),
    };
    if (status != (command is CreateApplicationCommand ? 201 : 200)) {
      throw const ApplicationProblem(ApplicationFailure.invalidResponse);
    }
    return DriverApplicationSnapshot.parse(body, session, command: command);
  }

  /// Returns (status, decoded JSON) for 200/201, or (404, null); every other answer is a problem.
  Future<(int, Object?)> _applicationSend(
    LoginSession session,
    String client,
    String method,
    String path, {
    ApplicationCommand? command,
    Map<String, Object?>? body,
  }) async {
    if (_closed || !configuration.isConfigured) throw const ApplicationProblem(ApplicationFailure.unavailable);
    final http = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(http);
    try {
      return await (() async {
        final request = await http.openUrl(method, configuration.baseUri!.resolve(path));
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json, application/problem+json');
        request.headers.set('Authorization', 'Bearer ${session.token}');
        request.headers.set('X-Waka-Client-Instance', client);
        if (command != null) {
          request.headers.set('Idempotency-Key', command.key);
          request.headers.contentType = ContentType.json;
          request.add(utf8.encode(jsonEncode(body)));
        }
        final response = await request.close();
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > 8192) throw const ApplicationProblem(ApplicationFailure.invalidResponse);
          bytes.addAll(chunk);
        }
        if (response.headers.value(HttpHeaders.cacheControlHeader) != 'no-store') {
          throw const ApplicationProblem(ApplicationFailure.invalidResponse);
        }
        final status = response.statusCode;
        if (status == 200 || status == 201) {
          if (response.headers.contentType?.mimeType != 'application/json') {
            throw const ApplicationProblem(ApplicationFailure.invalidResponse);
          }
          return (status, jsonDecode(utf8.decode(bytes)));
        }
        if (response.headers.contentType?.mimeType != 'application/problem+json') {
          throw const ApplicationProblem(ApplicationFailure.invalidResponse);
        }
        final problem = jsonDecode(utf8.decode(bytes));
        final code = problem is Map && problem['code'] is String ? problem['code'] as String : null;
        // For the read, 404 is the normal answer of an account without an open candidature.
        if (status == 404 && command == null) return (404, null);
        throw ApplicationProblem(switch ((status, code)) {
          (401 || 403, _) => ApplicationFailure.denied,
          (404, _) => ApplicationFailure.notFound,
          (400 || 413 || 415, _) => ApplicationFailure.rejected,
          (409, 'VERSION_CONFLICT') => ApplicationFailure.versionConflict,
          (409, 'IDEMPOTENCY_KEY_REUSED') => ApplicationFailure.keyReused,
          (409, 'APPLICATION_ALREADY_OPEN') => ApplicationFailure.alreadyOpen,
          (409, 'INCOMPLETE') => ApplicationFailure.incomplete,
          (409, 'NOT_EDITABLE') => ApplicationFailure.notEditable,
          (410, 'REPLAY_EXPIRED') => ApplicationFailure.replayExpired,
          (>= 500, _) => ApplicationFailure.unavailable,
          _ => ApplicationFailure.invalidResponse,
        });
      })().timeout(const Duration(seconds: 8));
    } on ApplicationProblem {
      rethrow;
    } on FormatException {
      throw const ApplicationProblem(ApplicationFailure.invalidResponse);
    } on Exception {
      throw const ApplicationProblem(ApplicationFailure.uncertain);
    } finally {
      http.close(force: true);
      _active.remove(http);
    }
  }

  @override
  Future<ProfileSnapshot> profile(LoginSession session, String client) async =>
      ProfileSnapshot.parse(await _profileSend(session, client), session);

  @override
  Future<ProfileSnapshot> updateProfile(LoginSession session, String client, ProfileCommand command) async =>
      ProfileSnapshot.parse(await _profileSend(session, client, command), session, expectedVersion: command.expectedVersion);

  Future<Object?> _profileSend(LoginSession session, String client, [ProfileCommand? command]) async {
    if (_closed || !configuration.isConfigured) throw const ProfileProblem(ProfileFailure.unavailable);
    final http = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(http);
    try {
      return await (() async {
        final request = await http.openUrl(command == null ? 'GET' : 'PATCH', configuration.baseUri!.resolve(command == null ? '/api/v1/me/profile' : '/api/v1/me'));
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json, application/problem+json');
        request.headers.set('Authorization', 'Bearer ${session.token}');
        request.headers.set('X-Waka-Client-Instance', client);
        if (command != null) {
          request.headers.set('Idempotency-Key', command.key);
          request.headers.contentType = ContentType.json;
          request.add(utf8.encode(jsonEncode({'expectedVersion': command.expectedVersion, 'displayName': command.displayName})));
        }
        final response = await request.close();
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > 8192) throw const ProfileProblem(ProfileFailure.invalidResponse);
          bytes.addAll(chunk);
        }
        if (response.headers.value(HttpHeaders.cacheControlHeader) != 'no-store') throw const ProfileProblem(ProfileFailure.invalidResponse);
        if (response.statusCode != 200) {
          String? code;
          if (response.headers.contentType?.mimeType != 'application/problem+json') throw const ProfileProblem(ProfileFailure.invalidResponse);
          try { final body = jsonDecode(utf8.decode(bytes)); if (body is Map && body['code'] is String) code = body['code'] as String; } on Exception { throw const ProfileProblem(ProfileFailure.invalidResponse); }
          if (response.statusCode == 401 || response.statusCode == 403) throw const ProfileProblem(ProfileFailure.denied);
          if (response.statusCode == 400 || response.statusCode == 413 || response.statusCode == 415) throw const ProfileProblem(ProfileFailure.rejected);
          if (response.statusCode == 409) throw ProfileProblem(code == 'VERSION_CONFLICT' ? ProfileFailure.versionConflict : code == 'IDEMPOTENCY_KEY_REUSED' ? ProfileFailure.keyReused : ProfileFailure.invalidResponse);
          if (response.statusCode == 410 && code == 'REPLAY_EXPIRED') throw const ProfileProblem(ProfileFailure.replayExpired);
          if (response.statusCode >= 500) throw const ProfileProblem(ProfileFailure.unavailable);
          throw const ProfileProblem(ProfileFailure.invalidResponse);
        }
        if (response.headers.contentType?.mimeType != 'application/json') throw const ProfileProblem(ProfileFailure.invalidResponse);
        return jsonDecode(utf8.decode(bytes));
      })().timeout(const Duration(seconds: 8));
    } on ProfileProblem { rethrow; }
    on FormatException { throw const ProfileProblem(ProfileFailure.invalidResponse); }
    on Exception { throw const ProfileProblem(ProfileFailure.uncertain); }
    finally { http.close(force: true); _active.remove(http); }
  }

  @override
  Future<LoginChallenge> request(LoginAttempt attempt, String client) async =>
      LoginChallenge.parse(
        await _send('/api/v1/auth/phone-challenges', attempt.requestKey, 202, {
          'phoneNumber': attempt.phone,
          'purpose': 'login',
          'clientInstanceRef': client,
        }),
      );

  @override
  Future<LoginSession> verify(
    LoginAttempt attempt,
    String client,
    String code,
  ) async => LoginSession.parse(
    await _send(
      '/api/v1/auth/phone-challenges/${attempt.challenge!.id}/verifications',
      attempt.verificationKey(code),
      200,
      {'phoneNumber': attempt.phone, 'code': code, 'clientInstanceRef': client},
    ),
  );

  @override
  Future<void> revoke(LoginSession session, String client, String key) async {
    await _send(
      '/api/v1/auth/sessions/${session.id}/revocations',
      key,
      204,
      null,
      {
        'Authorization': 'Bearer ${session.token}',
        'X-Waka-Client-Instance': client,
      },
    );
  }

  @override
  Future<LoginAccount> me(LoginSession session, String client) async =>
      LoginAccount.parse(
        await _send('/api/v1/me', null, 200, null, {
          'Authorization': 'Bearer ${session.token}',
          'X-Waka-Client-Instance': client,
        }, 'GET'),
        session,
      );

  Future<Object?> _send(
    String path,
    String? key,
    int expected,
    Map<String, Object>? body, [
    Map<String, String> extra = const {},
    String method = 'POST',
  ]) async {
    if (_closed || !configuration.isConfigured) {
      throw const LoginProblem(LoginFailure.unavailable);
    }
    final http = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(http);
    try {
      return await (() async {
        final request = await http.openUrl(
          method,
          configuration.baseUri!.resolve(path),
        );
        request.followRedirects = false;
        if (key != null) request.headers.set('Idempotency-Key', key);
        request.headers.set(
          HttpHeaders.acceptHeader,
          'application/json, application/problem+json',
        );
        extra.forEach(request.headers.set);
        if (body != null) {
          request.headers.contentType = ContentType.json;
          request.add(utf8.encode(jsonEncode(body)));
        }
        final response = await request.close();
        if (response.statusCode != expected) {
          throw LoginProblem(switch (response.statusCode) {
            400 || 413 || 415 => LoginFailure.invalid,
            401 || 403 => LoginFailure.denied,
            409 => LoginFailure.conflict,
            429 => LoginFailure.limited,
            >= 500 => LoginFailure.unavailable,
            _ => LoginFailure.invalidResponse,
          });
        }
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > 8192) {
            throw const LoginProblem(LoginFailure.invalidResponse);
          }
          bytes.addAll(chunk);
        }
        if (response.headers.value(HttpHeaders.cacheControlHeader) !=
            'no-store') {
          throw const LoginProblem(LoginFailure.invalidResponse);
        }
        if (expected == 204) {
          if (bytes.isNotEmpty) {
            throw const LoginProblem(LoginFailure.invalidResponse);
          }
          return null;
        }
        if (response.headers.contentType?.mimeType != 'application/json') {
          throw const LoginProblem(LoginFailure.invalidResponse);
        }
        return jsonDecode(utf8.decode(bytes));
      })().timeout(const Duration(seconds: 8));
    } on LoginProblem {
      rethrow;
    } on FormatException {
      throw const LoginProblem(LoginFailure.invalidResponse);
    } on Exception {
      throw const LoginProblem(LoginFailure.uncertain);
    } finally {
      http.close(force: true);
      _active.remove(http);
    }
  }

  @override
  void dispose() {
    _closed = true;
    for (final client in _active) {
      client.close(force: true);
    }
    _active.clear();
  }
}
