package com.waka.operations.infrastructure;

import com.waka.drivers.DriverAccessService;
import com.waka.drivers.SubscriptionService;
import com.waka.finance.FapshiProvider;
import com.waka.geo.GooglePlacesProvider;
import com.waka.geo.GoogleRoutesProvider;
import com.waka.finance.FinanceAdminService;
import com.waka.finance.PaymentProvider;
import com.waka.finance.PaymentService;
import com.waka.geo.CircleServiceArea;
import com.waka.geo.GeoPoint;
import com.waka.geo.PickupTimeProvider;
import com.waka.geo.RouteDistanceProvider;
import com.waka.identity.admin.AdminCrypto;
import com.waka.identity.admin.AdminPolicy;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.admission.AdmissionCrypto;
import com.waka.identity.admission.AdmissionReviewService;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.application.LoginService;
import com.waka.identity.files.DriverDocumentService;
import com.waka.identity.files.PrivateFileStore;
import com.waka.identity.files.VehiclePhotoService;
import com.waka.identity.application.MeService;
import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.OtpPolicy;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.SmsGateway;
import com.waka.identity.privacy.PersistentPrivacyService;
import com.waka.identity.privacy.PrivacyAdminService;
import com.waka.identity.profile.PersistentProfileService;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionCrypto;
import com.waka.identity.session.SessionPolicy;
import com.waka.identity.session.SessionRecoveryCrypto;
import com.waka.identity.session.SessionRecoveryPolicy;
import com.waka.mobility.incident.PersistentIncidentService;
import com.waka.mobility.quote.PersistentQuoteService;
import com.waka.mobility.ride.PersistentRideService;
import com.waka.mobility.voyage.PersistentVoyageService;
import com.waka.mobility.voyage.VoyageAssistanceService;
import com.waka.pricing.infrastructure.JdbcFareScheduleRepository;
import com.waka.referrals.PersistentReferralService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The whole application, assembled for trying it on this computer only (profile dev, 127.0.0.1, opt-in with
 * WAKA_LOCAL_APP=on). Three stand-ins replace paid services still waiting for the founder, and are refused
 * anywhere else: login codes are written to a private local file instead of an SMS; distances are estimated
 * from straight lines (x 1.3) instead of a map; payments are simulated unless Fapshi sandbox keys are given.
 * Every key is derived from one private master key held outside Git, so each purpose has its own key.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnProperty(name = "waka.local-app", havingValue = "on")
public class LocalAppAssembly {
    private final byte[] master;
    private final Path dir;
    private final SecureRandom random = new SecureRandom();

    public LocalAppAssembly() {
        String encoded = System.getenv("WAKA_LOCAL_MASTER_KEY"), folder = System.getenv("WAKA_LOCAL_DIR");
        byte[] key;
        try { key = encoded == null ? null : Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { key = null; }
        if (key == null || key.length < 32 || folder == null || !Path.of(folder).isAbsolute() || !Files.isDirectory(Path.of(folder))) {
            throw new IllegalStateException("LOCAL_APP_CONFIGURATION_REQUIRED");
        }
        master = key;
        dir = Path.of(folder);
    }

    /** A distinct 32-byte key per purpose, always the same for the same master key. */
    private byte[] key(String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(("waka-local-app-v1:" + purpose).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("LOCAL_APP_KEY");
        }
    }
    private ProfileCrypto profileCrypto(String purpose) { return new ProfileCrypto(key(purpose + ":enc"), key(purpose + ":mac"), "local-1", random); }

    // ---- Foundations ----

    @Bean Clock wakaClock() { return Clock.systemUTC(); }
    @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }

    @Bean OtpCrypto otpCrypto() { return new OtpCrypto(key("otp:mac"), key("otp:enc"), random); }
    @Bean PersistentOtpService otpService(JdbcTemplate jdbc, DataSourceTransactionManager tx, OtpCrypto crypto, Clock clock) {
        return new PersistentOtpService(jdbc, tx, crypto, new OtpPolicy(100), new LocalFileCodes(dir.resolve("codes-connexion-local.txt")), clock);
    }
    @Bean PersistentSessionService sessions(JdbcTemplate jdbc, DataSourceTransactionManager tx, OtpCrypto otp, Clock clock) {
        return new PersistentSessionService(jdbc, tx, new PersistedOtpProofVerifier(jdbc, otp, clock), new SessionCrypto(key("session"), random),
                new SessionPolicy(Duration.ofDays(30), Duration.ofDays(7)), clock, new SessionRecoveryPolicy(Duration.ofMinutes(10)),
                new SessionRecoveryCrypto(key("session-recovery"), "local-1", random));
    }
    @Bean LoginService loginService(PersistentOtpService otp, PersistentSessionService sessions) { return new LoginService(otp, sessions); }
    @Bean MeService meService(PersistentSessionService sessions) { return new MeService(sessions); }
    @Bean PersistentAdminService adminService(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager tx, Clock clock) {
        return new PersistentAdminService(sessions, jdbc, tx, new AdminCrypto(key("admin:enc"), key("admin:mac"), "local-1", random),
                new AdminPolicy(Duration.ofMinutes(15), Duration.ofHours(1), 5, Duration.ofMinutes(2), Duration.ofMinutes(5)), clock);
    }
    @Bean PersistentProfileService profileService(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager tx, Clock clock) {
        return new PersistentProfileService(sessions, jdbc, tx, profileCrypto("profile"), Duration.ofMinutes(10), clock);
    }

    // ---- Drivers ----

    @Bean AdmissionCrypto admissionCrypto() { return new AdmissionCrypto(key("admission:enc"), key("admission:mac"), "local-1", random); }
    @Bean PersistentDriverApplicationService applications(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager tx,
            AdmissionCrypto crypto, Clock clock) {
        return new PersistentDriverApplicationService(sessions, jdbc, tx, crypto, Duration.ofMinutes(10), clock);
    }
    @Bean PrivateFileStore fileStore() throws IOException {
        return new PrivateFileStore(Files.createDirectories(dir.resolve("fichiers-prives")), key("files:enc"), key("files:mac"), "local-1", random);
    }
    @Bean VehiclePhotoService photos(PersistentSessionService sessions, JdbcTemplate jdbc, PrivateFileStore store, PersistentAdminService admin) {
        return new VehiclePhotoService(sessions, jdbc, store, admin);
    }
    @Bean DriverDocumentService documents(PersistentSessionService sessions, PersistentDriverApplicationService applications, JdbcTemplate jdbc,
            PrivateFileStore store, PersistentAdminService admin) {
        return new DriverDocumentService(sessions, applications, jdbc, store, admin);
    }
    @Bean AdmissionReviewService reviews(PersistentSessionService sessions, PersistentAdminService admin, PersistentDriverApplicationService applications,
            JdbcTemplate jdbc, AdmissionCrypto crypto) {
        return new AdmissionReviewService(sessions, admin, applications, jdbc, crypto);
    }
    @Bean DriverAccessService driverAccess(PersistentSessionService sessions, PersistentDriverApplicationService applications) {
        return new DriverAccessService(sessions, applications);
    }
    @Bean SubscriptionService subscriptions(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager tx,
            PersistentDriverApplicationService applications, Clock clock) {
        return new SubscriptionService(sessions, jdbc, tx, applications, profileCrypto("subscription"), clock);
    }

    // ---- Account, referral, privacy ----

    @Bean PersistentReferralService referrals(PersistentSessionService sessions, JdbcTemplate jdbc, Clock clock) {
        return new PersistentReferralService(sessions, jdbc, random, clock);
    }
    @Bean ProfileCrypto privacyCrypto() { return profileCrypto("privacy"); }
    @Bean PersistentPrivacyService privacy(PersistentSessionService sessions, JdbcTemplate jdbc, ProfileCrypto privacyCrypto, Clock clock) {
        return new PersistentPrivacyService(sessions, jdbc, privacyCrypto, clock);
    }
    @Bean PrivacyAdminService privacyAdmin(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc, ProfileCrypto privacyCrypto) {
        return new PrivacyAdminService(sessions, admin, jdbc, privacyCrypto);
    }

    // ---- Mobility ----

    @Bean JdbcFareScheduleRepository fares(JdbcTemplate jdbc) { return new JdbcFareScheduleRepository(jdbc); }
    @Bean PersistentQuoteService quotes(PersistentSessionService sessions, JdbcTemplate jdbc, JdbcFareScheduleRepository fares, Clock clock) {
        GoogleRoutesProvider google = routes();
        return new PersistentQuoteService(sessions, jdbc, profileCrypto("quote"), fares, "launch-v1",
                google != null ? google : LocalAppAssembly::straightLineMetres, CircleServiceArea.LAUNCH, clock);
    }
    @Bean PersistentRideService rides(PersistentSessionService sessions, JdbcTemplate jdbc, PersistentDriverApplicationService applications,
            PersistentQuoteService quotes, JdbcFareScheduleRepository fares, Clock clock) {
        // Google road time when its key is given; otherwise about 25 km/h in town, from the straight-line estimate.
        GoogleRoutesProvider google = routes();
        PickupTimeProvider pickup = google != null ? google
                : (from, to) -> straightLineMetres(from, to).divide(BigDecimal.valueOf(6.94), 0, RoundingMode.UP).intValueExact();
        return new PersistentRideService(sessions, jdbc, applications, quotes, fares, pickup, profileCrypto("ride"), clock);
    }

    /** Address search (K35) around the launch city when the Google server key is given; otherwise none (503). */
    @Bean GooglePlacesProvider places() {
        String key = System.getenv("WAKA_GOOGLE_SERVER_KEY");
        return key == null ? null : new GooglePlacesProvider(key,
                new GeoPoint(new BigDecimal("3.848"), new BigDecimal("11.502")), 20_000);
    }

    /**
     * Google Routes when the server key was saved by tools/save-google-maps-keys.ps1; otherwise null (straight lines).
     * One instance for quotes and pickups, so the daily allowance is shared.
     */
    private GoogleRoutesProvider routes;
    private synchronized GoogleRoutesProvider routes() {
        String key = System.getenv("WAKA_GOOGLE_SERVER_KEY");
        if (routes == null && key != null) routes = new GoogleRoutesProvider(key);
        return routes;
    }
    @Bean PersistentVoyageService voyages(PersistentSessionService sessions, JdbcTemplate jdbc, PersistentDriverApplicationService applications, Clock clock) {
        return new PersistentVoyageService(sessions, jdbc, applications, profileCrypto("voyage"), clock);
    }
    @Bean VoyageAssistanceService voyageAssistance(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc) {
        return new VoyageAssistanceService(sessions, admin, jdbc);
    }
    @Bean PersistentIncidentService incidents(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc, Clock clock) {
        return new PersistentIncidentService(sessions, admin, jdbc, profileCrypto("incident"), clock);
    }

    // ---- Money ----

    @Bean PaymentService payments(PersistentSessionService sessions, JdbcTemplate jdbc, DataSourceTransactionManager tx, Clock clock) {
        return new PaymentService(sessions, jdbc, tx, provider(), profileCrypto("payment"), clock);
    }
    @Bean FinanceAdminService finance(PersistentSessionService sessions, PersistentAdminService admin, JdbcTemplate jdbc) {
        return new FinanceAdminService(sessions, admin, jdbc);
    }
    /** Fapshi never repeats a lost notification, and this computer cannot receive any: pending items are looked up every 30 s. */
    @Bean(destroyMethod = "shutdownNow") ScheduledExecutorService paymentFollowUp(PaymentService payments) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "waka-payment-follow-up");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try { payments.refreshPending(50); } catch (RuntimeException ignored) { /* retried at the next round */ }
        }, 30, 30, TimeUnit.SECONDS);
        return executor;
    }

    /** Fapshi sandbox when its keys are given; otherwise every payment succeeds at once, for trying the screens. */
    private static PaymentProvider provider() {
        String user = System.getenv("WAKA_FAPSHI_COLLECT_USER");
        if (user == null) return new SimulatedProvider();
        return new FapshiProvider(false, new FapshiProvider.Credentials(user, System.getenv("WAKA_FAPSHI_COLLECT_KEY")),
                new FapshiProvider.Credentials(System.getenv("WAKA_FAPSHI_PAYOUT_USER"), System.getenv("WAKA_FAPSHI_PAYOUT_KEY")),
                System.getenv("WAKA_FAPSHI_WEBHOOK_SECRET"));
    }

    // ---- Local stand-ins (never for real users) ----

    /** Straight-line distance x 1.3, whole metres. A real map provider replaces it before launch. */
    static BigDecimal straightLineMetres(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.latitude().doubleValue()), lat2 = Math.toRadians(b.latitude().doubleValue());
        double dLat = lat2 - lat1, dLon = Math.toRadians(b.longitude().doubleValue() - a.longitude().doubleValue());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return BigDecimal.valueOf(Math.round(6_371_000 * 2 * Math.asin(Math.sqrt(h)) * 1.3));
    }

    /** Writes each login code to a private file of this computer instead of sending an SMS (SMS on hold). */
    static final class LocalFileCodes implements SmsGateway {
        private final Path file;
        LocalFileCodes(Path file) { this.file = file; }
        @Override public synchronized Submission send(com.waka.identity.otp.SmsMessage message) throws IOException {
            String phone = new String(message.destination());
            String line = Instant.now() + "  ******" + phone.substring(Math.max(0, phone.length() - 3)) + "  code " + new String(message.code())
                    + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return Submission.ACCEPTED;
        }
    }

    /** Accepts every payment and withdrawal at once, with the exact amount. Only for trying the screens. */
    static final class SimulatedProvider implements PaymentProvider {
        private final Map<String, Long> amounts = new ConcurrentHashMap<>();
        @Override public String name() { return "simulated"; }
        @Override public Started collect(String reference, long amountXaf, String channel, String phone, String description) {
            amounts.put("sim-" + reference, amountXaf);
            return new Started("sim-" + reference);
        }
        @Override public Status payment(String reference) {
            Long amount = amounts.get(reference);
            return amount == null ? new Status(State.PENDING, null, null) : new Status(State.SUCCEEDED, amount, "XAF");
        }
        @Override public Started send(String reference, long amountXaf, String channel, String phone, String description) {
            return collect(reference, amountXaf, channel, phone, description);
        }
        @Override public Status transfer(String reference) { return payment(reference); }
        @Override public boolean authentic(byte[] body, java.util.function.UnaryOperator<String> header) { return false; }
        @Override public Set<String> references(byte[] body) { return Set.of(); }
    }
}
