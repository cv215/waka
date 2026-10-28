# WAKA — modèle des données et relations

03.06 v1, 10 octobre 2026. **Réalisé — à relire dans le lot 03.05–04.01 autorisé.** Modèle conceptuel du vrai projet ; aucune table ni donnée personnelle créée.

## M01 — Principes de lecture

Un identifiant désigne un objet ; une relation relie deux objets. `1 → N` signifie qu'un objet peut en avoir plusieurs ; `0..1` signifie facultatif. Tous les objets sensibles conservent création et modification serveur, version et provenance utile. Les identifiants externes sont opaques ; connaître un identifiant ne donne aucun droit. Les noms ci-dessous seront conservés dans états, API et modules. XAF est une devise, les montants sont des francs entiers ; les calculs intermédiaires utilisent des fractions exactes ou un décimal exact, jamais un flottant binaire.

Les dates d'événements sont des instants UTC affichés dans un fuseau compréhensible. Les durées d'essai et de période sont en secondes ; les douze mois de parrainage restent calendaires. L'instantané accepté ne change pas lorsqu'une grille, un véhicule ou une catégorie évolue. Un champ absent n'est pas une preuve négative : distinguer inconnu, non applicable et valeur établie.

## M02 — Personnes, accès et admission

| ID | Objet | Identité, informations nécessaires et relations | Invariant ou protection |
|---|---|---|---|
| D01 | Personne | Identité interne vérifiée selon le parcours, contact utile ; 1 → N rôles, sessions, réservations payées ; au plus un rattachement de parrain | Une personne peut être cliente et chauffeuse ; identité réelle jamais dans les exemples ; pas de copie CNI généralisée des clients |
| D02 | AttributionRole | Personne, fonction, portée, début/fin, auteur et motif | Client, candidat, chauffeur approuvé et fonctions administratives séparés ; aucun rôle accordé par le téléphone |
| D03 | Session | Personne, appareil utile, identifiant, révocation et échéance ; empreinte des éléments secrets lorsque nécessaire | Aucun jeton ni code OTP dans journal ou Git ; authentification renforcée administration |
| D04 | DossierChauffeur | Personne, catégorie taxi/particulier demandée, véhicule, version de dossier ; 1 → N décisions | Recevoir n'est pas approuver ; changement véhicule/catégorie crée un examen versionné |
| D05 | DecisionAdmission | Dossier et version examinée, agent, fonction, résultat, date, motif | Append-only : une correction ajoute une décision ; aucune approbation d'une version différente |
| D06 | Vehicule | Huit champs C03 : marque, modèle, année, couleur, immatriculation, capacité totale autorisée, places WAKA, titulaire/droit ; dossiers et trajets référents | Capacité passagers = min(5, capacité autorisée hors chauffeur) ; changement soumis à approbation |
| D07 | Justificatif | Dossier/véhicule, famille, fichier privé, dépôt, examen, dates de validité applicables | Résultat d'examen distinct de validité temporelle ; justificatif refusé remplacé sans effacer historique utile |
| D08 | FichierPrive | Propriétaire, objet lié, clé interne de stockage, type vérifié, taille, empreinte, état du contrôle et conservation | Jamais chemin disque ou URL publique ; autorisation par objet avant accès ; aucun secret dans métadonnées |
| D09 | PhotoVehicule | Véhicule, version, vue PH01–PH06, fichier privé, décision | Six vues concordantes ; photo ≠ preuve de capacité ou état mécanique ; version admise conservée |

## M03 — Mobilité, prix et occupation

| ID | Objet | Identité, informations nécessaires et relations | Invariant ou protection |
|---|---|---|---|
| D10 | Trajet | Chauffeur, véhicule admis, mode, disponibilité, état, version courante ; 1 → N versions d'itinéraire, propositions et réservations | Fin trajet distincte de fin de chaque réservation ; véhicule au pilote sans engagements exclusifs concurrents |
| D11 | VersionItineraire | Trajet, version, provenance du calcul routier, date ; 1 → N arrêts ordonnés et segments | Version immutable lorsqu'acceptée ; insertion future respecte engagements et détour min(10 min, 25 % durée de référence) |
| D12 | Arret | VersionItineraire, ordre, point de rendez-vous documenté, position nécessaire | Ordre unique par version ; points corrigés par la personne ne sont pas écrasés par le GPS |
| D13 | Segment | VersionItineraire, arrêt de début et fin adjacents, estimation routière | Ensemble des segments d'une réservation explicitement associé à la version ; pas seulement un compteur global |
| D14 | Devis | Payeur, mode, quantité/occupants, départ/descente, distance routière arrondie au mètre, p, prix total, règles et paramètres, échéance | Durée 5 min ; VIP prix ×12,5 indépendant des occupants ; prix changé exige nouveau devis accepté |
| D15 | Recherche | Payeur, devis accepté, début/échéance, état ; 1 → N propositions, au plus une acceptation gagnante | Fenêtre 2 min sans renouvellement silencieux ; refus/expiration proposition n'allongent pas cette durée |
| D16 | Proposition | Recherche, chauffeur/trajet/version, quantité et parcours proposé, création/échéance et réponse | 30 s dans recherche/devis ; ne constitue pas réservation confirmée ni retenue |
| D17 | Reservation | Payeur, chauffeur, trajet/version, devis, mode, places payées ou occupants VIP, arrêts/segments, état ; instantané financier ; incidents | Une réservation de groupe = un payeur ; acceptation valide fige P0/C0/D0, m/r/p et versions ; éligibilité, B0 et W0 définitifs établis à la fin payée |
| D18 | Retenue | Réservation, trajet/version, segments, quantité, mode/exclusivité, création/échéance/état | 3 min après acceptation valide ; une retenue active compte dans occupation ; conversion unique sans compter deux fois |
| D19 | PositionRecente | Chauffeur engagé/disponible, instant serveur et acquisition appareil distingués, précision utile | Fraîcheur 60 s pour proposition ; pas de carte de tous passagers ; accès limité aux participants pertinents |
| D20 | PreuvePrestation | Réservation, événement arrivée/embarquement/fin, acteur, date serveur, méthode et motif d'exception | Code temporaire protégé et consommé une fois ; GPS seul insuffisant ; fin et contestation distinctes |

Le serveur calcule l'occupation depuis les réservations engagées et retenues actives par segment, sans compter deux fois une retenue convertie. Au pilote, VIP active une exclusivité du véhicule ; ses occupants ne deviennent pas autant de réservations partagées. Le garde d'exclusivité et le verrou bref portent sur le véhicule, puis les trajets concernés : deux trajets du même véhicule ne peuvent pas contourner ce contrôle. Calcul routier et appel prestataire restent hors de la transaction de base.

Avant publication d'une nouvelle version d'itinéraire, projeter tous les engagements et retenues encore actifs sur ses segments, avec une association versionnée vers leurs points convenus. Conserver leur version de référence immutable, leur prix et leurs preuves. Si une projection sûre manque, si un point convenu change sans accord ou si une capacité/détour est violé, refuser la nouvelle version. Une insertion ne repart jamais d'une occupation vide parce que les réservations référencent l'ancienne version.

## M04 — Finance et rapprochement

| ID | Objet | Identité, informations nécessaires et relations | Invariant ou protection |
|---|---|---|---|
| D21 | Paiement | Objet payé (réservation OU achat abonnement), montant attendu, XAF, canal qualifié, état ; 1 → N tentatives, au plus un encaissement attribué | Aucun paiement client rechargeable au pilote ; montant serveur, paiement tardif n'attribue pas la place |
| D22 | TentativePaiement | Paiement, identifiant de demande sortante, référence opérateur si établie, état, horodatages et prochaine vérification | Résultat inconnu interdit un nouveau débit improvisé ; plusieurs tentatives seulement après résolution fiable de la précédente |
| D23 | EvenementPrestataire | Prestataire, compte marchand interne, identifiant externe unique, objet lié, authentification vérifiée, montant/devise/statut normalisés, traitement | Unique dans la portée prestataire/compte/événement ; charge brute sensible protégée, pas dans les logs ; événements répétés/désordonnés traités une seule fois |
| D24 | DecisionRemboursement | Réservation ou achat, incident/motif, auteur, date, montant demandé et version du calcul ; 1 → N opérations | Vérifier total confirmé + ordres de remboursement non résolus ≤ encaissé ; décider n'est pas payer ; Rtotal ne compte que confirmations |
| D25 | Remboursement | Décision, paiement d'origine, montant, XAF, référence sortante stable, résultat ; tentatives liées | Pas de deuxième instruction si résultat inconnu ; confirmation prestataire seule annonce remboursé |
| D26 | DetteBeneficiaire | Réservation et bénéficiaire, nature chauffeur/parrain, montant initial/conservé, état et blocage justifié | Gains ouverts après paiement, prestation et contrôles ; dette ≠ virement externe ; seuls montants contestés suspendus |
| D27 | Versement | Bénéficiaire, dettes affectées, montant, XAF, destination protégée, référence stable, état | Chauffeur par réservation rapprochée ; seuil 1 000 F réservé au retrait des bonus parrains ; frais WAKA séparés |
| D28 | JournalFinancier | Événement source, version de règle, date, devise ; 1 → N écritures de sens et comptes distincts | Journal immutable équilibré débit/crédit en XAF ; règle d'unicité par événement/effet ; aucune suppression d'écriture exécutée |
| D29 | EcritureFinanciere | Journal, compte bénéficiaire/WAKA/encaissement/remboursement/frais, sens, montant, lien de compensation | Les sommes dues ne sont pas la trésorerie libre ; corrections par écritures compensatrices ; somme débits = crédits |

Les dettes, paiements, frais et journaux gardent des identifiants séparés. Le solde affiché est une projection des écritures confirmées et des dettes disponibles, jamais un montant modifiable envoyé par le mobile. Un verrou ou une contrainte d'unicité protège événement et effet ; les appels externes ne sont pas rejoués par une transaction SQL réessayée.

L'instantané de réservation garde prix/commission initiaux à acceptation ; les bonus initiaux sont établis une seule fois à la fin payée selon l'éligibilité à cet instant, et conservent le bénéficiaire et la fenêtre utilisés. Une annulation intégrale antérieure ne crée aucun bonus. Une correction ultérieure applique R09 à ces données établies, sans chercher le parrain actuel ni recalculer l'ancienne grille.

## M05 — Accès particulier, parrainage et exploitation

| ID | Objet | Identité, informations nécessaires et relations | Invariant ou protection |
|---|---|---|---|
| D30 | EssaiChauffeur | Personne vérifiée, réservation déclenchante, début/fin, état | Au plus un essai par personne ; première réservation chauffeur payée ET terminée ; 604 800 secondes ; provisoire préalable distingué |
| D31 | AchatAbonnement | Chauffeur particulier, produit/version, 3 000 XAF, paiement lié, état et période attribuée | Paiement distinct des courses ; pas de bonus ni reconduction automatique ; canal Android à qualifier |
| D32 | PeriodeAcces | Achat/essai et personne, nature provisoire/essai/payée, début/fin, durée vendue, ajustements liés | 2 592 000 secondes par achat payé ; une période par achat ; périodes payées ordonnées et non dupliquées ; taxi non concerné |
| D33 | AjustementAcces | Période, décision, motif, durée vérifiée ou remboursement, auteur et date | Incident WAKA complet ≥24 h qualifié avant prolongation ; combinaisons et achat avant première prestation à clarifier avant implémentation |
| D34 | RattachementParrain | Filleul, parrain facultatif, origine/décision ; fenêtre unique début/fin déclenchée par première réservation payée terminée dans tout rôle | Au plus un parrain/niveau ; douze mois calendaires ; auto-parrainage interdit ; rôles ne redémarrent pas la fenêtre |
| D35 | BonusParrain | Réservation, côté payeur/chauffeur, parrain, fenêtre, éligibilité finale, montant initial/conservé, dette | Unique (réservation, côté) ; mêmes bénéficiaires possibles pour deux côtés indépendants ; inscriptions/abonnements/gratuité exclus |
| D36 | Incident | Objet affecté, déclarant, catégorie, preuves minimales, décision et auteur, contestation/état | GPS seul insuffisant ; historique sûr ; décisions financières séparées ; revue humaine des soupçons |
| D37 | Avis | Réservation terminée, auteur autorisé, destinataire, contenu minimal, modération | Lié à prestation réelle ; accès/publication à préciser à 16.03 ; aucune étoile ou faux avis créé |
| D38 | PartageSuivi | Réservation, émetteur autorisé, portée, échéance et révocation ; empreinte du jeton | Lien temporaire limité ; fin de service/révocation retire accès ; pas de justificatif ou téléphone complet exposé |
| D39 | MessageOutbox | Événement source, destinataire/portée, type notification ou ordre externe, clé unique, état et tentatives | Enregistré avec la transaction ; livraison répétable, aucun double effet ; payload minimal ; canaux non contractés non promis |
| D40 | RequeteIdempotente | Acteur, opération, clé, empreinte de demande normalisée, état, objet/résultat référent | Même clé + même corps retrouve résultat ; même clé + autre corps refusé ; durée de conservation à préciser selon risques de reprise |
| D41 | AuditAction | Acteur et fonction exercée, objet, commande, date, motif, version, résultat | Pas de secrets, pièces ou GPS inutile ; intégrité et accès audit distincts ; journal technique distinct |
| D42 | VersionRegle | Famille grille/tarif/paramètre/remboursement/accès, contenu, effet à partir de, auteur, validation | Versions historiques nécessaires au calcul conservées ; modifier n'agit que sur engagements futurs applicables |

Le retrait parrain utilise Versement avec nature distincte et affectation des dettes disponibles. Pas de compte bancaire ni portefeuille client fictif créé. Un budget marketing ne supprime jamais une dette acquise. Demande de suppression et dossier de droits seront tracés via Incident de type droit des données avec accès restreint, puis traitement consacré à 03.09/17 ; ils ne deviennent pas un incident visible aux participants d'une course.

## M06 — Contraintes à matérialiser plus tard

| Contrôle | Portée et condition |
|---|---|
| Unicité de personne/essai/parrain | Personne vérifiée ; dédoublonnage prudent avec revue humaine, pas d'identité déduite d'un téléphone partagé |
| Une réservation gagnante | Recherche : au plus une acceptation convertie en retenue ; transaction sur trajet/version et recherche |
| Occupation/exclusivité | Garde et verrou véhicule puis trajets concernés ; tous engagements/retentions pertinents, y compris autres trajets du même véhicule ; projection versionnée avant changement ; plafond sur chaque segment ; VIP sans partagé actif ni retenue concurrente |
| Confirmation | Une attribution d'encaissement, une conversion de retenue, un effet financier par événement ; authenticité/produit/montant/devise établis |
| Bonus et essais | Une fenêtre personne, un essai personne, un bonus par réservation/côté ; déclencheur unique même si paiement et fin arrivent dans l'ordre inverse |
| Remboursement | Montants positifs ; réservé + confirmé ≤ encaissé ; compensation cumulée exacte, frais prestataires séparés |
| Périodes payées | Une période par achat ; nouvel achat après dernière période déjà programmée pour éviter chevauchements ; disposition constructive sans ajouter de jours gratuits |
| Audit et droits | Décision liée à version ; autorisation par propriétaire/rôle/portée sur chaque lecture et mutation |

Une unique clé SQL ne peut pas prouver une somme de capacité sur plusieurs segments. Les contraintes de domaine exigent transaction/verrou/version, puis des tests réels. Un nom de champ ne crée pas une protection technique.

## M07 — Exemples suivis de bout en bout

| Cas abstrait | Chaîne des objets | Vérification documentaire |
|---|---|---|
| Partagé 3 places, p=600 | Personne → Devis(1 800) → Recherche → Proposition → Reservation → Retenue(3 sur chaque segment) → Paiement → PreuvePrestation → DetteBeneficiaire/Bonus → Journal → Versement | P=1 800 ; C=180 ; D=1 620 ; B=30/15 ; W=135 ; libération après descente propre à cette réservation, trajet peut continuer |
| VIP 3 occupants, p=600 | Même chaîne, Reservation VIP → Retenue/exclusivité véhicule → Paiement(7 500) → Prestation → dettes/journal/versement | Occupants=3 mais P=7 500 pour le véhicule ; C=750 ; D=6 750 ; B=100/50 ; W=600 ; pas de réservation d'un tiers pendant exclusivité |
| Remboursement VIP cumulé 2 500 | Incident → DecisionRemboursement → Remboursement confirmé → Journal compensateur lié aux montants initiaux | T=5 000 ; Cret=500 ; Dret=4 500 ; Bret=67/33 ; Wret=400 ; 2 500+4 500+67+33+400=7 500 |
| Première prestation particulier | Dossier approuvé → accès provisoire → fin/payé de Reservation chauffeur → EssaiChauffeur unique → AchatAbonnement confirmé → PeriodeAcces | Essai 7×24 h ; période payée 30×24 h ; gains/course distincts d'abonnement ; achat avant première prestation reste ouvert |
| Parrain unique, deux rôles | Rattachement → première Reservation payée terminée du filleul client OU chauffeur → fenêtre → Bonus futurs éligibles → Dette → Versement | Fenêtre non redémarrée par changement de rôle ; retrait ≥1 000 F disponibles ; frais réels coût WAKA |

## M08 — Lecture dans VS Code, coût et limites

Depuis le dossier documentaire : `code outputs/WAKA_modele_donnees.md`, puis **Ctrl+Maj+V** pour l'aperçu. M02–M05 décrivent les objets ; M06 les protections à programmer ; M07 permet la revue concrète. Ce modèle ne réalise ni MySQL ni moteur de tarification ou écriture comptable.

Sources : [règles R01–R10](WAKA_regles_adoptees.md), [partagé P01–P15](WAKA_parcours_partage.md), [VIP V01–V12](WAKA_parcours_vip.md), [chauffeur A01–A10](WAKA_parcours_chauffeur.md), dossier §7.2–7.3 et catalogue 03.06. Les liens relatifs restent utilisables dans la copie du dépôt.

**Coût de réalisation documentaire : 0 F.** Champs SQL, index, chiffrement, stockage, rétention et migrations seront vérifiés lors de leur construction. Les preuves transport, prestataire et Android restent à obtenir ; aucune intégration n'est déclarée disponible.
