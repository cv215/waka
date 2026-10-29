package com.waka.identity.admission;

import com.waka.identity.admin.AdminException;
import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.session.PersistentSessionService;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 06.08.a: an ADMISSION agent examines submitted files (vehicle and six photos) and records an
 * append-only, reasoned decision. Approval stays unavailable until document requirements are
 * qualified. Authorization and effect share one transaction. Not assembled by default.
 */
public final class AdmissionReviewService {
    public static final List<String> VIEWS = List.of("PH01", "PH02", "PH03", "PH04", "PH05", "PH06");
    public record WorkItem(UUID applicationId, String requestedCategory, long version, Instant submittedAt) { }
    public record PhotoRef(String view, UUID fileId) { }
    public record PhotoReview(String view, UUID fileId, String result, String reasonCode) { }
    /** {@code documentType} is filled by the server from the catalogue; it is ignored on input. */
    public record DocumentReview(UUID documentId, String documentType, String result, String reasonCode) { }
    /** Catalogue v2: approval without this vehicle document, with a traced reason; never identity or licence. */
    public record Waiver(String code, String reasonCode) { }
    public record Decision(UUID decisionId, UUID applicationId, long examinedVersion, String action, String reasonCode,
            String details, List<PhotoReview> photoReviews, List<DocumentReview> documentReviews, List<Waiver> waivers, Instant decidedAt,
            boolean idempotentReplay) {
        @Override public String toString() { return "Decision[REDACTED]"; }
    }
    public record Detail(PersistentDriverApplicationService.ApplicationView application, Instant submittedAt,
            List<PhotoRef> photos, List<PersistentDriverApplicationService.RequirementStatus> requirements,
            List<Decision> decisions) {
        @Override public String toString() { return "AdmissionDetail[REDACTED]"; }
    }
    private record Out<T>(T value, AdmissionException.Code refusal) { }
    private final PersistentSessionService sessions;
    private final PersistentAdminService admin;
    private final PersistentDriverApplicationService applications;
    private final JdbcTemplate jdbc;
    private final AdmissionCrypto crypto;
    private final String gate;

    public AdmissionReviewService(PersistentSessionService sessions, PersistentAdminService admin,
            PersistentDriverApplicationService applications, JdbcTemplate jdbc, AdmissionCrypto crypto) {
        this.sessions = Objects.requireNonNull(sessions);
        this.admin = Objects.requireNonNull(admin);
        this.applications = Objects.requireNonNull(applications);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.crypto = Objects.requireNonNull(crypto);
        if (!sessions.usesDataSource(jdbc.getDataSource())) throw new IllegalArgumentException("REVIEW_DEPENDENCIES_MISMATCH");
        this.gate = sessions.identityGateHash();
    }

    /** A37 limited to admission: submitted files waiting for a decision, never the agent's own. */
    public List<WorkItem> workItems(char[] token, String client, char[] adminToken) {
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.ADMISSION, (session, context) -> new Out<>(
                jdbc.query("SELECT id,requested_category,version,submitted_at FROM driver_application WHERE gate_hash=? AND state='submitted' AND account_id<>? ORDER BY submitted_at,id LIMIT 50",
                        (rs, n) -> new WorkItem(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3),
                                instant(rs.getObject(4, LocalDateTime.class))), gate, context.accountId().toString()), null),
                AdmissionReviewService::refusedByAdmin));
    }

    /** The exact submitted version with its current photos and decision history. */
    public Detail detail(char[] token, String client, char[] adminToken, UUID applicationId) {
        if (applicationId == null) throw invalid();
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.ADMISSION, (session, context) -> {
            var examined = applications.examinedLocked(applicationId, session.serverTime());
            if (examined == null || examined.submittedAt() == null) return new Out<Detail>(null, AdmissionException.Code.NOT_FOUND);
            if (examined.candidate().equals(context.accountId())) return new Out<Detail>(null, AdmissionException.Code.CONFLICT_OF_INTEREST);
            List<PhotoRef> photos = currentPhotos(applicationId).entrySet().stream()
                    .map(e -> new PhotoRef(e.getKey(), e.getValue())).sorted((a, b) -> a.view.compareTo(b.view)).toList();
            return new Out<>(new Detail(examined.view(), examined.submittedAt(), photos,
                    applications.requirementsLocked(examined.candidate(), applicationId, session.serverTime()),
                    decisions(applicationId)), null);
        }, AdmissionReviewService::refusedByAdmin));
    }

    /**
     * A38. request_correction targets only the pieces marked to_correct; refuse closes the file; approve
     * requires that every applicable requirement is ESTABLISHED, every document accepted and valid
     * (earlier or in this decision) unless waived, the eight vehicle fields complete and all six photos acceptable.
     */
    public Decision decide(char[] token, String client, char[] adminToken, UUID applicationId, UUID key,
            long expectedVersion, String action, String reasonCode, String details, List<PhotoReview> reviews,
            List<DocumentReview> documents) {
        return decide(token, client, adminToken, applicationId, key, expectedVersion, action, reasonCode, details, reviews,
                documents, List.of());
    }

    /** Same, with waivers: only with approve, one per vehicle document, each with its reason code. */
    public Decision decide(char[] token, String client, char[] adminToken, UUID applicationId, UUID key,
            long expectedVersion, String action, String reasonCode, String details, List<PhotoReview> reviews,
            List<DocumentReview> documents, List<Waiver> waiverList) {
        if (applicationId == null || key == null || expectedVersion < 1) throw invalid();
        if (!"request_correction".equals(action) && !"refuse".equals(action) && !"approve".equals(action)) throw invalid();
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,47}")) throw invalid();
        String text = details(details);
        List<PhotoReview> photoReviews = reviews == null ? List.of() : List.copyOf(reviews);
        List<DocumentReview> documentReviews = documents == null ? List.of() : List.copyOf(documents);
        List<Waiver> waivers = waiverList == null ? List.of() : List.copyOf(waiverList);
        if (!waivers.isEmpty() && !"approve".equals(action)) throw invalid();
        var waivedCodes = new HashSet<String>();
        for (Waiver waiver : waivers) {
            if (waiver == null || waiver.code == null || !waiver.code.matches("[A-Z][A-Z0-9_]{2,39}") || !waivedCodes.add(waiver.code)
                    || waiver.reasonCode == null || !waiver.reasonCode.matches("[A-Z][A-Z0-9_]{2,47}")) throw invalid();
        }
        var seen = new HashSet<String>();
        for (PhotoReview review : photoReviews) {
            if (review == null || !VIEWS.contains(review.view) || review.fileId == null || !seen.add(review.view)
                    || invalidResult(review.result, review.reasonCode)) throw invalid();
        }
        for (DocumentReview review : documentReviews) {
            if (review == null || review.documentId == null || !seen.add(review.documentId.toString())
                    || invalidResult(review.result, review.reasonCode)) throw invalid();
        }
        return unwrap(admin.withAdmin(token, client, adminToken, AdminFunction.ADMISSION, (session, context) -> {
            String agent = context.accountId().toString();
            String request = crypto.mac("admission-decision-request-v1", agent, applicationId.toString(), key.toString());
            // Without waivers the payload stays the v2 one, so earlier decisions are still found on replay.
            String payload = waivers.isEmpty()
                    ? crypto.mac("admission-decision-payload-v2", agent, applicationId.toString(), Long.toString(expectedVersion),
                            action, reasonCode, text == null ? "" : text, canonical(photoReviews), canonicalDocuments(documentReviews))
                    : crypto.mac("admission-decision-payload-v3", agent, applicationId.toString(), Long.toString(expectedVersion),
                            action, reasonCode, text == null ? "" : text, canonical(photoReviews), canonicalDocuments(documentReviews),
                            canonicalWaivers(waivers));
            var previous = jdbc.query("SELECT id,payload_mac FROM admission_decision WHERE gate_hash=? AND request_mac=? FOR UPDATE",
                    (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, gate, request);
            if (!previous.isEmpty()) {
                if (!AdmissionCrypto.equal(previous.getFirst()[1], payload)) return new Out<Decision>(null, AdmissionException.Code.IDEMPOTENCY_KEY_REUSED);
                Decision original = decision(UUID.fromString(previous.getFirst()[0]));
                return new Out<>(withReplay(original), null);
            }
            Instant now = session.serverTime();
            var examined = applications.examinedLocked(applicationId, now);
            if (examined == null || examined.submittedAt() == null) return new Out<Decision>(null, AdmissionException.Code.NOT_FOUND);
            if (examined.candidate().equals(context.accountId())) return new Out<Decision>(null, AdmissionException.Code.CONFLICT_OF_INTEREST);
            Map<String, UUID> current = currentPhotos(applicationId);
            for (PhotoReview review : photoReviews) {
                if (!review.fileId.equals(current.get(review.view))) return new Out<Decision>(null, AdmissionException.Code.VERSION_CONFLICT);
            }
            var requirements = applications.requirementsLocked(examined.candidate(), applicationId, now);
            Map<UUID, String> currentDocuments = new java.util.HashMap<>();
            for (var requirement : requirements) if (requirement.documentId() != null) currentDocuments.put(requirement.documentId(), requirement.code());
            for (DocumentReview review : documentReviews) {
                if (!currentDocuments.containsKey(review.documentId)) return new Out<Decision>(null, AdmissionException.Code.VERSION_CONFLICT);
            }
            if ("approve".equals(action)) {
                // A requirement still to be confirmed is never satisfied: no approval while one applies.
                if (requirements.stream().anyMatch(r -> !"established".equals(r.status()))) {
                    return new Out<Decision>(null, AdmissionException.Code.APPROVAL_NOT_AVAILABLE);
                }
                boolean photosAcceptable = photoReviews.size() == VIEWS.size()
                        && photoReviews.stream().allMatch(r -> "acceptable".equals(r.result));
                // A waiver must name an applicable vehicle document; identity and licence are never waived.
                for (String code : waivedCodes) {
                    var waived = applications.requirement(code);
                    if (waived == null || !waived.waivable() || requirements.stream().noneMatch(r -> r.code().equals(code))) {
                        return new Out<Decision>(null, AdmissionException.Code.INVALID_REQUEST);
                    }
                }
                boolean documentsAcceptable = documentReviews.stream().allMatch(r -> "acceptable".equals(r.result))
                        && requirements.stream().allMatch(r -> waivedCodes.contains(r.code()) || r.documentId() != null && !r.expired()
                                && ("accepted".equals(r.reviewState()) || documentReviews.stream()
                                        .anyMatch(d -> d.documentId.equals(r.documentId()))));
                if (!photosAcceptable || !documentsAcceptable || !examined.view().missingVehicleFields().isEmpty()) {
                    return new Out<Decision>(null, AdmissionException.Code.INCOMPLETE);
                }
            }
            String target = switch (action) { case "refuse" -> "refused"; case "approve" -> "approved"; default -> "correction_requested"; };
            long next = applications.decideLocked(examined.candidate(), applicationId, expectedVersion, target, now);
            if (next < 0) return new Out<Decision>(null, AdmissionException.Code.VERSION_CONFLICT);
            UUID id = UUID.randomUUID();
            byte[] cipher = null;
            if (text != null) {
                byte[] plain = text.getBytes(StandardCharsets.UTF_8);
                try { cipher = crypto.encrypt(plain, detailsAad(id, applicationId)); }
                finally { Arrays.fill(plain, (byte) 0); }
            }
            jdbc.update("INSERT INTO admission_decision (gate_hash,id,application_id,examined_version,agent_account_id,admin_function,action,reason_code,key_id,details_cipher,request_mac,payload_mac,decided_at,catalogue_version) VALUES (?,?,?,?,?,'ADMISSION',?,?,?,?,?,?,?,?)",
                    gate, id.toString(), applicationId.toString(), expectedVersion, agent, action, reasonCode, crypto.keyId(),
                    cipher, request, payload, sql(now), applications.catalogueVersion());
            for (PhotoReview review : photoReviews) {
                jdbc.update("INSERT INTO admission_photo_review (gate_hash,decision_id,photo_view,file_id,result,reason_code) VALUES (?,?,?,?,?,?)",
                        gate, id.toString(), review.view, review.fileId.toString(), review.result, review.reasonCode);
            }
            for (DocumentReview review : documentReviews) {
                jdbc.update("INSERT INTO admission_document_review (gate_hash,decision_id,document_id,document_type,result,reason_code) VALUES (?,?,?,?,?,?)",
                        gate, id.toString(), review.documentId.toString(), currentDocuments.get(review.documentId), review.result, review.reasonCode);
                // The piece keeps this examination for later files while it stays current and valid.
                jdbc.update("UPDATE driver_document SET review_state=?,reviewed_at=? WHERE gate_hash=? AND id=? AND current_marker=1",
                        "acceptable".equals(review.result) ? "accepted" : "to_correct", sql(now), gate, review.documentId.toString());
            }
            for (Waiver waiver : waivers) {
                jdbc.update("INSERT INTO admission_waiver (gate_hash,decision_id,requirement_code,reason_code) VALUES (?,?,?,?)",
                        gate, id.toString(), waiver.code, waiver.reasonCode);
            }
            return new Out<>(new Decision(id, applicationId, expectedVersion, action, reasonCode, text,
                    sortedReviews(photoReviews), documentReviewsOf(id), waiversOf(id), now, false), null);
        }, AdmissionReviewService::refusedByAdmin));
    }

    private static boolean invalidResult(String result, String reason) {
        return !("acceptable".equals(result) || "to_correct".equals(result))
                || (reason != null && !reason.matches("[A-Z][A-Z0-9_]{2,47}"))
                || ("to_correct".equals(result) && reason == null);
    }
    private static String canonicalDocuments(List<DocumentReview> reviews) {
        return reviews.stream().sorted((a, b) -> a.documentId.compareTo(b.documentId))
                .map(r -> r.documentId + ":" + r.result + ":" + (r.reasonCode == null ? "" : r.reasonCode))
                .collect(Collectors.joining(";"));
    }
    private static String canonicalWaivers(List<Waiver> waivers) {
        return waivers.stream().sorted((a, b) -> a.code.compareTo(b.code)).map(w -> w.code + ":" + w.reasonCode)
                .collect(Collectors.joining(";"));
    }
    private List<Waiver> waiversOf(UUID decision) {
        return jdbc.query("SELECT requirement_code,reason_code FROM admission_waiver WHERE gate_hash=? AND decision_id=? ORDER BY requirement_code",
                (rs, n) -> new Waiver(rs.getString(1), rs.getString(2)), gate, decision.toString());
    }
    private List<DocumentReview> documentReviewsOf(UUID decision) {
        return jdbc.query("SELECT document_id,document_type,result,reason_code FROM admission_document_review WHERE gate_hash=? AND decision_id=? ORDER BY document_type",
                (rs, n) -> new DocumentReview(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4)),
                gate, decision.toString());
    }

    /** The candidate reads the decisions about their own file, without the agent's identity. */
    public List<Decision> candidateDecisions(char[] token, String client, UUID applicationId) {
        if (applicationId == null) throw invalid();
        return unwrap(sessions.withAuthenticatedSession(token, client, session -> {
            var owned = jdbc.query("SELECT id FROM driver_application WHERE gate_hash=? AND id=? AND account_id=?",
                    (rs, n) -> rs.getString(1), gate, applicationId.toString(), session.accountId().toString());
            if (owned.isEmpty()) return new Out<List<Decision>>(null, AdmissionException.Code.NOT_FOUND);
            return new Out<>(decisions(applicationId), null);
        }));
    }

    private List<Decision> decisions(UUID applicationId) {
        return jdbc.query("SELECT id FROM admission_decision WHERE gate_hash=? AND application_id=? ORDER BY decided_at,id",
                (rs, n) -> UUID.fromString(rs.getString(1)), gate, applicationId.toString())
                .stream().map(this::decision).toList();
    }
    private Decision decision(UUID id) {
        var row = jdbc.queryForMap("SELECT application_id,examined_version,action,reason_code,key_id,details_cipher,decided_at FROM admission_decision WHERE gate_hash=? AND id=?",
                gate, id.toString());
        UUID applicationId = UUID.fromString((String) row.get("application_id"));
        String details = null;
        byte[] cipher = (byte[]) row.get("details_cipher");
        if (cipher != null) {
            if (!crypto.keyId().equals(row.get("key_id"))) throw unavailable();
            byte[] plain = crypto.decrypt(cipher, detailsAad(id, applicationId));
            try { details = new String(plain, StandardCharsets.UTF_8); }
            finally { Arrays.fill(plain, (byte) 0); }
        }
        List<PhotoReview> reviews = jdbc.query("SELECT photo_view,file_id,result,reason_code FROM admission_photo_review WHERE gate_hash=? AND decision_id=? ORDER BY photo_view",
                (rs, n) -> new PhotoReview(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4)),
                gate, id.toString());
        return new Decision(id, applicationId, ((Number) row.get("examined_version")).longValue(), (String) row.get("action"),
                (String) row.get("reason_code"), details, reviews, documentReviewsOf(id), waiversOf(id),
                instant((LocalDateTime) row.get("decided_at")), false);
    }
    private Map<String, UUID> currentPhotos(UUID applicationId) {
        return jdbc.query("SELECT photo_view,id FROM vehicle_photo WHERE gate_hash=? AND application_id=? AND current_marker=1",
                (rs, n) -> Map.entry(rs.getString(1), UUID.fromString(rs.getString(2))), gate, applicationId.toString())
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Optional free text: NFC, trimmed, 1 to 500 characters, line breaks allowed, no other control or invisible format. */
    static String details(String input) {
        if (input == null) return null;
        String text = Normalizer.normalize(input, Normalizer.Form.NFC).strip();
        int count = text.codePointCount(0, text.length());
        if (count < 1 || count > 500) throw invalid();
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            int type = Character.getType(point);
            if ((type == Character.CONTROL && point != '\n') || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) throw invalid();
            i += Character.charCount(point);
        }
        return text;
    }
    private static String canonical(List<PhotoReview> reviews) {
        return sortedReviews(reviews).stream()
                .map(r -> r.view + ":" + r.fileId + ":" + r.result + ":" + (r.reasonCode == null ? "" : r.reasonCode))
                .collect(Collectors.joining(";"));
    }
    private static List<PhotoReview> sortedReviews(List<PhotoReview> reviews) {
        var sorted = new ArrayList<>(reviews);
        sorted.sort((a, b) -> a.view.compareTo(b.view));
        return List.copyOf(sorted);
    }
    private static Decision withReplay(Decision d) {
        return new Decision(d.decisionId, d.applicationId, d.examinedVersion, d.action, d.reasonCode, d.details,
                d.photoReviews, d.documentReviews, d.waivers, d.decidedAt, true);
    }
    private byte[] detailsAad(UUID decision, UUID application) {
        return AdmissionCrypto.aad("waka-admission-decision-v1", gate, decision.toString(), application.toString(), crypto.keyId());
    }
    private static <T> Out<T> refusedByAdmin(AdminException.Code code) {
        return new Out<>(null, switch (code) {
            case NOT_AUTHORIZED -> AdmissionException.Code.NOT_AUTHORIZED;
            case INVALID_ADMIN_SESSION -> AdmissionException.Code.INVALID_ADMIN_SESSION;
            default -> AdmissionException.Code.SERVICE_UNAVAILABLE;
        });
    }
    private static <T> T unwrap(Out<T> out) {
        if (out.refusal != null) throw new AdmissionException(out.refusal);
        return out.value;
    }
    private static LocalDateTime sql(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private static AdmissionException invalid() { return new AdmissionException(AdmissionException.Code.INVALID_REQUEST); }
    private static AdmissionException unavailable() { return new AdmissionException(AdmissionException.Code.SERVICE_UNAVAILABLE); }
    @Override public String toString() { return "AdmissionReviewService[REDACTED]"; }
}
