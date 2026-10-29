package com.waka.identity.admission;

import java.util.List;
import java.util.Set;

/**
 * Catalogue v1 of driver documents (docs/specifications/WAKA_pieces_chauffeur.md). A requirement
 * TO_CONFIRM is shown but never satisfied, and while one applies the file cannot be approved.
 * Moving a requirement to ESTABLISHED needs a dated founder decision and a new catalogue version.
 */
public final class DocumentCatalog {
    public enum Status { ESTABLISHED, TO_CONFIRM }
    /** PERSON documents are reused across files; VEHICLE documents only for the same registration. */
    public enum Scope { PERSON, VEHICLE }
    public record Requirement(String code, String document, String explanation, Set<String> categories,
            boolean onlyForAuthorizedUse, Scope scope, Status status) {
        public boolean appliesTo(String category, String usageRight) {
            return categories.contains(category) && (!onlyForAuthorizedUse || "authorized_use".equals(usageRight));
        }
    }
    public static final String VERSION = "v1";
    private static final Set<String> BOTH = Set.of("taxi", "private");
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
                    Set.of("taxi"), false, Scope.VEHICLE, Status.TO_CONFIRM),
            new Requirement("PUBLIC_TRANSPORT_CARD", "Carte de transport public (« carte bleue ») du véhicule",
                    "La carte bleue du taxi est délivrée pour le véhicule à son exploitant.",
                    Set.of("taxi"), false, Scope.VEHICLE, Status.TO_CONFIRM),
            new Requirement("PAID_TRANSPORT_AUTHORIZATION", "Autorisation applicable au transport rémunéré de passagers avec un véhicule personnel",
                    "Transporter des passagers contre paiement avec ta voiture peut demander une autorisation : WAKA vérifie laquelle.",
                    Set.of("private"), false, Scope.VEHICLE, Status.TO_CONFIRM));

    private DocumentCatalog() { }

    public static List<Requirement> applicable(String category, String usageRight) {
        return V1.stream().filter(r -> r.appliesTo(category, usageRight)).toList();
    }
    public static Requirement byCode(String code) {
        return V1.stream().filter(r -> r.code.equals(code)).findFirst().orElse(null);
    }
}
