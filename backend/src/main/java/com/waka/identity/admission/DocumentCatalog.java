package com.waka.identity.admission;

import java.util.List;
import java.util.Set;

/**
 * Driver document catalogue (docs/specifications/WAKA_pieces_chauffeur.md). A requirement TO_CONFIRM is
 * shown but never satisfied, and while one applies the file cannot be approved. Each version stays
 * unchanged; v1 is kept as the history of earlier decisions.
 *
 * <p>v2 (founder decision of 29 October 2026, based on sources [S4][S5][S6][S8], no official text
 * consulted): technical inspection, transport licence (first category) and public transport card are
 * required for taxis only; no transport authorisation is asked from a personal-car driver (it is WAKA's
 * own obligation as a platform); taxi insurance must cover passenger transport, personal-car insurance
 * may be the ordinary private one. VEHICLE documents may be waived by the agent at approval with a
 * traced reason; PERSON documents (identity, driving licence) never.
 */
public final class DocumentCatalog {
    public enum Status { ESTABLISHED, TO_CONFIRM }
    /** PERSON documents are reused across files; VEHICLE documents only for the same registration. */
    public enum Scope { PERSON, VEHICLE }
    /** {@code privateDocument}/{@code privateExplanation}, when set, replace the wording for a personal car. */
    public record Requirement(String code, String document, String explanation, Set<String> categories,
            boolean onlyForAuthorizedUse, Scope scope, Status status, String privateDocument, String privateExplanation) {
        public Requirement(String code, String document, String explanation, Set<String> categories,
                boolean onlyForAuthorizedUse, Scope scope, Status status) {
            this(code, document, explanation, categories, onlyForAuthorizedUse, scope, status, null, null);
        }
        public boolean appliesTo(String category, String usageRight) {
            return categories.contains(category) && (!onlyForAuthorizedUse || "authorized_use".equals(usageRight));
        }
        /** Identity and driving licence are never waived; vehicle documents may be, with a traced reason. */
        public boolean waivable() { return scope == Scope.VEHICLE; }
        public String documentFor(String category) {
            return "private".equals(category) && privateDocument != null ? privateDocument : document;
        }
        public String explanationFor(String category) {
            return "private".equals(category) && privateExplanation != null ? privateExplanation : explanation;
        }
    }
    public static final String VERSION = "v2";
    private static final Set<String> BOTH = Set.of("taxi", "private");
    private static final Set<String> TAXI = Set.of("taxi");

    public static final List<Requirement> V1 = List.of(
            new Requirement("IDENTITY", "Carte nationale d'identité ou passeport en cours de validité",
                    "Ta pièce d'identité permet de vérifier que le dossier est bien le tien.",
                    BOTH, false, Scope.PERSON, Status.ESTABLISHED),
            new Requirement("DRIVING_LICENCE", "Permis de conduire de la catégorie du véhicule, en cours de validité",
                    "Ton permis montre que tu peux conduire ce type de véhicule.",
                    BOTH, false, Scope.PERSON, Status.ESTABLISHED),
            new Requirement("REGISTRATION_CERTIFICATE", "Carte grise (certificat d'immatriculation) du véhicule inscrit",
                    "La carte grise identifie le véhicule et son propriétaire.",
                    BOTH, false, Scope.VEHICLE, Status.ESTABLISHED),
            new Requirement("INSURANCE", "Attestation d'assurance en cours de validité couvrant le transport de passagers proposé",
                    "L'assurance doit couvrir l'activité que tu proposes avec WAKA, pas seulement un usage personnel.",
                    BOTH, false, Scope.VEHICLE, Status.ESTABLISHED),
            new Requirement("TECHNICAL_INSPECTION", "Certificat de visite technique en cours de validité",
                    "La visite technique montre que le véhicule a été contrôlé récemment.",
                    BOTH, false, Scope.VEHICLE, Status.TO_CONFIRM),
            new Requirement("USE_AUTHORIZATION", "Autorisation écrite et signée du propriétaire d'utiliser le véhicule pour WAKA",
                    "Le véhicule n'est pas à ton nom : le propriétaire doit autoriser son utilisation.",
                    BOTH, true, Scope.VEHICLE, Status.ESTABLISHED),
            new Requirement("TRANSPORT_LICENCE", "Licence de transport du véhicule, au nom du propriétaire ou exploitant",
                    "Le taxi doit être couvert par la licence de transport de son exploitant ; ce n'est pas un titre personnel du chauffeur.",
                    TAXI, false, Scope.VEHICLE, Status.TO_CONFIRM),
            new Requirement("PUBLIC_TRANSPORT_CARD", "Carte de transport public (« carte bleue ») du véhicule",
                    "La carte bleue du taxi est délivrée pour le véhicule à son exploitant.",
                    TAXI, false, Scope.VEHICLE, Status.TO_CONFIRM),
            new Requirement("PAID_TRANSPORT_AUTHORIZATION", "Autorisation applicable au transport rémunéré de passagers avec un véhicule personnel",
                    "Transporter des passagers contre paiement avec ta voiture peut demander une autorisation : WAKA vérifie laquelle.",
                    Set.of("private"), false, Scope.VEHICLE, Status.TO_CONFIRM));

    public static final List<Requirement> V2 = List.of(
            V1.get(0),
            V1.get(1),
            V1.get(2),
            new Requirement("INSURANCE", "Attestation d'assurance du taxi en cours de validité, couvrant le transport de passagers",
                    "L'assurance du taxi doit couvrir le transport de passagers, pas seulement un usage personnel.",
                    BOTH, false, Scope.VEHICLE, Status.ESTABLISHED,
                    "Attestation d'assurance du véhicule en cours de validité",
                    "L'assurance montre que ta voiture est assurée pendant que tu conduis."),
            new Requirement("TECHNICAL_INSPECTION", "Certificat de visite technique du taxi en cours de validité",
                    "La visite technique montre que le taxi a été contrôlé récemment.",
                    TAXI, false, Scope.VEHICLE, Status.ESTABLISHED),
            V1.get(5),
            new Requirement("TRANSPORT_LICENCE", "Licence de transport de première catégorie du taxi, au nom du propriétaire ou exploitant",
                    "Le taxi doit être couvert par la licence de transport de son exploitant ; ce n'est pas un titre personnel du chauffeur.",
                    TAXI, false, Scope.VEHICLE, Status.ESTABLISHED),
            new Requirement("PUBLIC_TRANSPORT_CARD", "Carte de transport public (« carte bleue ») du taxi",
                    "La carte bleue du taxi est délivrée pour le véhicule à son exploitant.",
                    TAXI, false, Scope.VEHICLE, Status.ESTABLISHED));

    /** The catalogue in force. */
    public static final List<Requirement> CURRENT = V2;

    private DocumentCatalog() { }

    public static List<Requirement> applicable(String category, String usageRight) {
        return CURRENT.stream().filter(r -> r.appliesTo(category, usageRight)).toList();
    }
    /** Any code known to a version, so that a document of an earlier version still routes to its owner. */
    public static Requirement byCode(String code) {
        return CURRENT.stream().filter(r -> r.code.equals(code)).findFirst()
                .orElseGet(() -> V1.stream().filter(r -> r.code.equals(code)).findFirst().orElse(null));
    }
}
