# WAKA — premiers contrats d'API

03.08 v1 — 11 octobre 2026. **Réalisé — à relire à la fin du lot autorisé le 10 octobre.** Ce document prépare les échanges du vrai projet. Les 42 contrats décrits ne sont pas des routes installées ; aucun serveur, SMS, fichier personnel, paiement ni compte prestataire n'est activé.

## K01 — But et références

Une API est l'ensemble des demandes que l'application peut adresser au serveur. Une route identifie une opération ; le serveur vérifie qui demande, sur quel objet, avec quelles conditions, puis répond. Le téléphone propose des données et des actions. Il ne s'attribue ni prix, capacité disponible, admission, statut financier, essai ou droit administratif.

Références : [règles R01–R10](WAKA_regles_adoptees.md), [fiches E01–E29](WAKA_fiches_ecrans.md), [objets D01–D42](WAKA_modele_donnees.md), [transitions T01–T40 et règles S01–S08](WAKA_etats_et_transitions.md), [partagé](WAKA_parcours_partage.md), [VIP](WAKA_parcours_vip.md) et [chauffeur](WAKA_parcours_chauffeur.md). Les corrections du modèle concernant les bonus à la fin payée, le garde véhicule et la projection des engagements sur un nouvel itinéraire sont conservées.

Le préfixe stable retenu est **/api/v1**. Les noms de routes ci-dessous utilisent l'anglais pour le futur code ; les informations utilisateur restent expliquées en français. La version API ne remplace pas les versions des prix, règles, dossiers ou itinéraires. Un changement incompatible devra être présenté avant modification du contrat.

## K02 — Conventions communes

| Élément | Contrat |
|---|---|
| Transport et contenu | HTTPS avant échanges réels. JSON UTF-8 pour les données, application/problem+json pour les erreurs ; dépôt de fichier en multipart/form-data. Aucun secret dans l'URL. |
| Identifiant | Chaîne opaque attribuée par le serveur : personne, devis, recherche, proposition, réservation, paiement, tentative, décision, remboursement et versement ont des identifiants distincts. Connaître un ID ne donne aucun droit. |
| Montant | Entier JSON en francs XAF, champ nommé amountXaf ou nom métier terminé par Xaf ; currency vaut exactement XAF. Ni flottant monétaire ni conversion implicite. Les taux et calculs exacts restent serveur ; leur affichage ne permet pas de les modifier. |
| Quantité | Entier positif ; shared : places payées ; vip : occupants annoncés. Maximum cinq hors chauffeur, limité par capacité autorisée du véhicule. VIP total = 12,5 × prix d'une place, indépendant de ces occupants. |
| Position et distance | latitude/longitude numériques bornées à ±90/±180 degrés ; accuracyMeters en mètres, non négatif. Distances de devis/itinéraire en mètres, durées en secondes. Origine du point et correction manuelle conservées. |
| Temps | Chaîne date-heure UTC terminée par Z ; échéances et serverTime viennent du serveur. L'heure d'acquisition appareil est une déclaration, pas l'autorité. Intervalles actifs [début, fin[ selon 03.07. |
| Version | version et expectedVersion sont des entiers positifs. La version de l'objet commandé et itineraryVersion sont distinctes. Un nouveau dossier ou itinéraire ne remplace pas silencieusement la référence d'un engagement. |
| Autres entrées | phoneNumber/payerContact : chaînes normalisées selon territoire/canal qualifiés ; code : chaîne temporaire confidentielle ; clientInstanceRef/eventId/destinationRef : références opaques, sans identifiant matériel collecté par ce contrat. displayName/description/text/details : texte de taille bornée ; marketingChoice : booléen, refus sans perte de service. |
| Points et catégories | origin/destination : objet contenant un point documenté (référence serveur autorisée ou latitude/longitude corrigibles) et sa provenance ; jamais une distance ou un prix imposés. objectType/category/reasonCode/familyCode : codes de liste autorisée et versionnée. evidenceRefs/documentRefs/photoRefs : tableaux d'ID appartenant au périmètre, jamais URL libre d'un fichier tiers. |
| Absence de valeur | null n'a de sens que défini : échéance non applicable pour un accès provisoire, par exemple. Un résultat inconnu n'est ni zéro, ni échoué, ni confirmé. |
| Réponse | Objet métier autorisé, avec id, version si mutable, état métier, serverTime, updatedAt et liens de relecture utiles. Une date extérieure n'est pas une confirmation WAKA. Les listes sont paginées par curseur opaque, avec limite plafonnée côté serveur. |
| Création et attente | 201 pour un objet créé, avec Location ; 200 pour lecture ou commande terminée ; 202 pour un traitement reçu mais non achevé, avec objet/URI de suivi ; 204 pour une révocation terminée sans corps. |
| Vie privée | Réponses selon rôle et objet ; pas de sérialisation directe des tables. Cache-Control: no-store sur sessions, données privées et finances ; logs sans OTP, code d'embarquement, jeton, pièce, contact intégral ou coordonnées inutiles. |

Les champs mentionnés comme entrées sont obligatoires sauf « ? ». Les ID présents dans la route ne sont pas redemandés librement dans le corps. Toute commande sur un objet mutable indique expectedVersion, sauf événement prestataire authentifié, commande de création et reprise de sa propre révocation déjà réalisée. L'échéance est toujours revérifiée sous transaction : une version correcte ne suffit pas.

Les corps sont limités à des champs autorisés. Les tentatives d'envoyer status, approved, adminRole, availableCapacity, price, commission, trialStart ou paymentConfirmed pour les imposer sont rejetées. Un montant demandé de retrait ou une décision financière habilitée reste borné et contrôlé ; il ne remplace jamais le calcul serveur.

La lecture appartient au titulaire de l'objet ou à l'agent habilité dans son périmètre. Le chauffeur voit l'engagement et sa rémunération utile ; il ne voit pas le compte de paiement, les documents ou les autres réservations privées du client. Le client ne voit pas les autres passagers. L'administration utilise une session renforcée et une fonction explicite : admission, assistance, finance ou audit. Ces fonctions pourront être cumulées par le fondateur, mais sont contrôlées séparément. Leur moyen d'authentification et leurs habilitations seront précisés à 03.09 et construits ensuite ; un OTP client ne suffit pas à obtenir un accès administratif.

## K03 — Objets retournés et reprise

Les contrats utilisent les représentations suivantes. Leur contenu complet sera défini lors de chaque fonction, sans changer les responsabilités déjà fixées.

- **Session/Profile :** sessionId, expiration/révocation, profil utile, rôles réellement accordés et accès chauffeur calculé. Les preuves de session sont transmises uniquement par le mécanisme sécurisé retenu ; aucune valeur n'est reproduite ici.
- **Quote :** id, mode, passengerCount, points, distanceMeters, placePriceXaf, totalXaf, currency, règle/version et expiresAt. Aucun véhicule n'est encore réservé.
- **Search/Proposal :** ID, état, limites serveur, version et données minimales de mise en relation. Proposal expose son trajet et itineraryVersion au seul chauffeur ciblé ; Search peut renvoyer reservationId seulement après acceptation gagnante.
- **Reservation :** ID, état de prestation, mode, quantité, rendez-vous, référence d'itinéraire, prix/commission/net chauffeur figés et échéances ; état financier séparé, liens vers paiement/incident et résultat utile du remboursement. Chaque rôle reçoit uniquement ses montants. Le code d'embarquement n'est présenté qu'au payeur autorisé, sans log ni exemple.
- **Payment :** ID, objet payé, montant/devise attendus, canal, état, tentative en cours, date de dernière vérification et référence de suivi interne. Les références opérateur détaillées restent à finance lorsque nécessaires. L'état inconnu interdit un nouveau débit improvisé.
- **Application/File :** version examinable du dossier, catégorie demandée, huit champs véhicule, pièces et six vues, résultat des contrôles/validités, éléments à corriger et décisions autorisées. Un fichier reçu n'est pas approuvé ; aucune URL publique d'original n'est retournée.
- **Access/Purchase :** accès effectif et motif, admission, provisoire/essai/périodes payées, dates serveur, achats et paiements distincts. Une période active, programmée et un paiement confirmé sont des informations différentes.
- **Earnings/Transfer/Refund :** parts et dettes autorisées, disponibles/réservées/reprises, opération externe et état confirmé ou inconnu. Un solde chauffeur ne devient pas un retrait soumis au seuil parrain.
- **Incident/Decision :** objet concerné, déclarations, état d'examen, décisions/motifs autorisés et contestation ; pas de preuve privée d'un tiers divulguée. A06 utilise D36 avec une nature privée liée au compte, sans créer un nouvel objet de données ni la rendre visible comme incident de course.
- **WorkItem/Audit :** projection administrative filtrée par fonction et périmètre, références et anomalies utiles, dates et acteur autorisé ; pas d'export libre de toute la base.

**Après une coupure :** conserver localement l'ID obtenu, la clé de commande et son contenu nécessaire à la reprise, dans le stockage protégé à construire. Relire l'objet ; si la réponse initiale a été perdue, répéter la même commande avec la même clé. GET /me/activity fournit les références des engagements et opérations propres non résolus, sans nécessiter une nouvelle recherche ou un nouveau paiement. Les lectures ne donnent aucun ordre de débit, versement ou remboursement. L'écran affiche l'heure du dernier résultat fiable lorsqu'il ne peut pas rafraîchir.

## K04 — Idempotence et concurrence

**Idempotency-Key est un contrat WAKA**, requis pour les POST et PATCH des applications et de l'administration ci-dessous. La clé est opaque, liée côté serveur à l'acteur, à l'opération et à l'empreinte des entrées normalisées. Elle n'est ni une authentification ni un secret opérateur. Pour l'authentification initiale, le périmètre est la demande de vérification et son contexte contrôlé ; connaître la clé seule ne donne pas une session. La répétition autorisée ne crée pas deux sessions ou droits ; la délivrance sûre de la preuve de session après réponse perdue sera précisée à 05.03.

Même clé et mêmes entrées : retrouver le résultat enregistré ou l'opération en cours, sans répéter l'effet. La réponse rejouée conserve le sens du résultat initial et peut indiquer idempotentReplay=true ; il faut une lecture pour obtenir l'état actuel. Même clé avec contenu différent : 409 IDEMPOTENCY_KEY_REUSED. Une commande encore en traitement renvoie 202 et sa référence ; elle n'est pas exécutée une deuxième fois.

Le serveur retrouve une commande déjà reçue **avant** de refuser sa répétition pour une version ou une échéance désormais dépassée. Il contrôle toujours le titulaire et le droit de relecture. Une commande réellement nouvelle est soumise aux échéances présentes. Une clé neuve ne permet jamais de contourner les unicités métier : acceptation gagnante, encaissement attribué, bonus, essai, période et dette affectée.

Chaque reprise vérifie session, habilitation et périmètre **actuels**, puis refiltre la projection enregistrée selon ces droits. Si la relecture n'est plus autorisée, 403/404 selon K07 ; aucune ancienne clé ne restitue des champs de finance ou d'admission dont le droit a été retiré. L'effet historique reste conservé sans être réexécuté.

L'effet interne, l'enregistrement d'idempotence, l'audit et l'outbox sont inscrits dans la même transaction courte. Le garde véhicule précède trajets concernés et recherche dans l'ordre stable de 03.07. Capacité sur chaque segment, tous trajets du véhicule et VIP sont revérifiés ; une nouvelle version projette les engagements et retenues existants, ou est refusée. Un appel cartographique ou financier se fait hors transaction ; réessayer la transaction ne redéclenche pas l'appel externe.

PUT /driver/position porte un eventId unique et une date d'acquisition ; duplication ne crée pas une nouvelle mesure, mesure ancienne ne rajeunit pas la position. Les callbacks utilisent l'identifiant d'événement prestataire dans un périmètre fournisseur/compte marchand contrôlé, pas une clé fournie comme preuve par le téléphone.

**La conservation des clés reste à qualifier à 03.09 selon objet, délai de rapprochement et risques.** Aucune expiration arbitraire à 24 heures : une finance inconnue ou un remboursement non résolu ne doit pas redevenir exécutable par purge. Les unicités et références métier restent protégées même lorsqu'une politique de nettoyage est autorisée. La conservation des secrets et celle des preuves de commande sont distinctes.

## K05 — Catalogue des 42 contrats

Les routes sont toutes précédées de /api/v1. Les erreurs K07 s'appliquent partout ; chaque ligne ajoute les refus métier essentiels. Les familles de commandes à action fermée n'acceptent que les variantes décrites en K06, avec un schéma d'entrée propre à chacune.

### Compte et session — A01 à A07

| ID / méthode / route | Entrées | Droit et réponse | Refus ou reprise particulière |
|---|---|---|---|
| A01 POST /auth/phone-challenges | phoneNumber, purpose=login | Demandeur limité par contrôles anti-abus ; 202 : demande identifiée, destination masquée, expiration et état d'envoi | Format invalide ; canal indisponible/limité. Même clé retrouve la demande ; pas d'énumération des comptes ni OTP retourné. |
| A02 POST /auth/phone-challenges/{challengeId}/verifications | code, clientInstanceRef | Preuve de vérification valide dans contexte de la demande ; 200 : session et profil autorisés | Code incorrect, expiré, consommé hors reprise identifiée ; limites d'essais. Aucun code d'embarquement accepté ici. |
| A03 POST /auth/sessions/{sessionId}/revocations | — | Titulaire de cette session ; 204 après révocation serveur | La reprise de la révocation identifiée retrouve son résultat ; une autre session n'est pas accessible. |
| A04 GET /me | — | Session valide ; 200 Profile | Session expirée/révoquée ; changer de vue ne crée pas un rôle. |
| A05 PATCH /me | expectedVersion, displayName?, marketingChoice? | Titulaire ; 200 profil et choix daté | Champs non autorisés ; ni rôle administratif, ni statut chauffeur, ni contact de paiement modifié librement. Changement de téléphone sécurisé traité à la fonction correspondante. |
| A06 POST /me/privacy-requests | kind=access ou deletion, details? | Titulaire ; 201 demande privée identifiée | Répétition retrouve demande ; aucune suppression totale annoncée à réception. Conservation applicable à qualifier. |
| A07 GET /me/activity | cursor?, limit? | Titulaire ; 200 références et états propres : demandes, engagements, dossier, achats et opérations non résolues | Aucun objet d'autrui ; lecture pour reprise, sans nouvelle attribution ou ordre financier. |

### Devis, recherche et proposition — A08 à A13

| ID / méthode / route | Entrées | Droit et réponse | Refus ou reprise particulière |
|---|---|---|---|
| A08 POST /quotes | mode=shared/vip, passengerCount, origin, destination | Client ; 201 Quote calculé, validité cinq minutes | Quantité, territoire ou route non recevables ; grille non applicable. Aucun prix client accepté. |
| A09 POST /searches | quoteId | Payeur du devis ; 201 Search avec échéance de deux minutes | Devis expiré, recherche déjà engagée incompatible. Même commande retrouve recherche/réservation gagnante. |
| A10 GET /searches/{searchId} | — | Payeur ; 200 recherche et reservationId si gagnante | Aucun candidat annoncé réservé sans acceptation serveur ; données minimales. |
| A11 POST /searches/{searchId}/cancellations | expectedVersion, reasonCode | Payeur ; 200 recherche annulée si pas d'acceptation gagnante | Si aboutie, relire la réservation et demander A21 explicitement ; pas d'annulation ambiguë de l'engagement gagné. |
| A12 GET /driver/proposals | cursor?, limit? | Chauffeur ciblé ; 200 ses propositions et échéances | Accès/position/admission contrôlés ; lecture ne remet pas les délais à zéro. |
| A13 POST /proposals/{proposalId}/responses | expectedVersion, itineraryVersion, action=accept ou refuse | Chauffeur destinataire ; 201 Reservation + Retenue sur acceptation gagnante, 200 réponse refusée | Fin devis/recherche/réponse, version ou capacité changée, VIP occupé, accès inadmissible. Reprise ne prend ni deuxième chauffeur ni dernière place deux fois. |

### Trajet et position — A14 à A17

| ID / méthode / route | Entrées | Droit et réponse | Refus ou reprise particulière |
|---|---|---|---|
| A14 POST /trips | vehicleId, mode, origin, destination, availabilityWindow | Chauffeur titulaire du véhicule admis ; 201 trajet brouillon | Véhicule non approuvé ou incompatible ; capacité effective vient de l'approbation. Le brouillon ne crée aucun engagement. |
| A15 GET /trips/{tripId} | — | Chauffeur propriétaire ; 200 trajet, version et réservations utiles | Pas de dossiers/payements privés des clients ; engagements existants accessibles même si abonnement expiré. |
| A16 POST /trips/{tripId}/actions | expectedVersion, action, données propres à l'action K06 | Chauffeur propriétaire ; 200 trajet après publish/start/finish/cancel | Versions, admission, accès, engagements et sécurité ; terminer trajet ne termine pas chaque réservation. |
| A17 PUT /driver/position | eventId, acquiredAt, latitude, longitude, accuracyMeters | Chauffeur concerné dans contexte de service autorisé ; 200 mesure reçue et serverTime | Valeurs invalides, acquisition incohérente/ancienne ; localisation ne prouve ni embarquement ni service. Fraîcheur ≤60 s vérifiée pour proposition. |

### Réservation, paiement et assistance — A18 à A25

| ID / méthode / route | Entrées | Droit et réponse | Refus ou reprise particulière |
|---|---|---|---|
| A18 GET /reservations/{reservationId} | — | Payeur, chauffeur engagé ou agent habilité au dossier ; 200 projection Reservation adaptée | Documents/payments d'autrui non exposés ; code uniquement à son payeur. Une retenue expirée reste lisible selon droits. |
| A19 POST /reservations/{reservationId}/payments | expectedVersion, operator, payerContact | Payeur ; 201 paiement créé ou 202 instruction/tentative identifiée en cours | Retenue expirée, opérateur non qualifié, montant serveur. Nouveau débit seulement après échec terminal établi et retenue encore active ; inconnu n'autorise pas une nouvelle tentative. |
| A20 GET /payments/{paymentId} | — | Payeur de la réservation/achat, ou finance habilitée ; 200 Payment | Ne confirme pas une place ; confirmation tardive donne obligation de restitution et suivi distincts. |
| A21 POST /reservations/{reservationId}/cancellations | expectedVersion, reasonCode, details? | Payeur ou chauffeur engagé ; 200 réservation annulée et suivi financier existant | Avant embarquement seulement ; intégral si encaissé, pas de pénalité/bonus. Après embarquement : incident, sans prix conservé arbitraire. |
| A22 POST /reservations/{reservationId}/service-events | expectedVersion, action, données propres K06 | Chauffeur engagé, à l'arrêt ; 200 événement et état contrôlé | Paiement/identité/vehicle/code/pièces/échéance ; arrivée, embarquement et fin distincts. Exception motivée par assistance, pas par GPS seul. |
| A23 POST /incidents | objectType, objectId, affectedObjectVersion selon action, category, description, evidenceRefs? | Partie de cet objet ; 201 Incident | Objet hors droits, preuve privée d'un tiers non autorisée ; pas de promesse d'assistance permanente. Annulation de période programmée : réception recevable avant début bloque immédiatement son activation, voir K06. |
| A24 GET /incidents/{incidentId} | — | Déclarant/parties et agents dans leur périmètre ; 200 dossier et décisions autorisées | Filtrage des preuves et notes internes ; prix/état financier ne sont pas changés par lecture. |
| A25 POST /incidents/{incidentId}/messages | expectedVersion, kind=reply ou contest, text, evidenceRefs? | Partie autorisée au dossier ; 201 déclaration rattachée | Réponse perdue rejouable ; contestation rouvre l'examen, ne réembarque pas ni ne paie automatiquement. |

### Candidature, véhicule et fichiers — A26 à A31

| ID / méthode / route | Entrées | Droit et réponse | Refus ou reprise particulière |
|---|---|---|---|
| A26 POST /driver-applications | requestedCategory=taxi/private | Personne authentifiée ; 201 dossier brouillon | Dossier en cours déjà incompatible ; catégorie déclarée ne donne pas admission. |
| A27 GET /driver-applications/{applicationId} | — | Titulaire ou vérificateur habilité ; 200 Application | État d'examen, validités et accès séparés ; pas d'objet d'un autre candidat par ID connu. |
| A28 PATCH /driver-applications/{applicationId} | expectedVersion, vehicleFields?, documentRefs?, photoRefs? | Titulaire, brouillon/correction ou nouvelle version autorisée ; 200 version mise à jour | Huit champs, six vues liées ; aucun approved envoyé. Remplacement n'hérite pas automatiquement de l'examen ancien. |
| A29 POST /driver-applications/{applicationId}/submissions | expectedVersion | Titulaire ; 200 dossier soumis et références figées d'examen | Champs/pièces/vues nécessaires manquants ; exigences non qualifiées ; pas d'accès commercial par dépôt. |
| A30 POST /private-files | applicationId, vehicleId? selon usage, purpose, familyCode ou photoView, fichier | Titulaire du dossier ; 201 File privé reçu, contrôle distinct | Taille/type/contenu ou objet non autorisés ; originaux privés, pas d'URL publique. Reprise identifiée ne crée pas deux approbations. |
| A31 GET /private-files/{fileId}/content | — | Titulaire ou vérificateur au dossier dans portée nécessaire ; contenu contrôlé | Authentification et droit recontrôlés ; fichier rejeté/dangereux non servi. Ni URL Git ni téléchargement anonyme ; lecture tracée selon 03.09. |

### Accès et gains — A32 à A36

| ID / méthode / route | Entrées | Droit et réponse | Refus ou reprise particulière |
|---|---|---|---|
| A32 GET /driver/access | — | Titulaire chauffeur/candidat ; 200 accès effectif, périodes, historique et achats propres | Taxi sans abonnement ; particulier provisoire puis essai/payé selon règles ; pièce expirée non réparée par achat. |
| A33 POST /subscription-purchases | productId, qualifiedChannel, purchaseEvidence? selon canal | Particulier admis ; 201 achat identifié ou 202 vérification en cours | Produit serveur 3 000 XAF/30×24 h ; preuve canal vérifiée, pas de bonus/reconduction. Achat avant première prestation encore à décider ; voir K09, sans nouvelle interdiction métier. |
| A34 POST /me/referral-linkages | referralCode | Titulaire ; 201 rattachement facultatif ou 200 même rattachement retrouvé | Un niveau, auto-parrainage interdit, relation déjà établie incompatible ; correction exceptionnelle par assistance. |
| A35 GET /me/earnings | category=driver/referral, cursor?, limit? | Bénéficiaire ; 200 dettes, bonus et opérations propres, états/parts autorisés | Identités et parcours privés des filleuls non publiés ; chauffeur par réservation, seuil seulement referral. |
| A36 POST /me/referral-withdrawals | requestedAmountXaf, destinationRef | Parrain bénéficiaire ; 201 versement demandé/réservé ou 202 suivi externe | ≥1 000 XAF disponibles ; destination autorisée vérifiée, allocation atomique des dettes ; inconnu garde réservation des fonds, frais WAKA. |

### Administration et confirmations — A37 à A42

| ID / méthode / route | Entrées | Droit et réponse | Refus ou reprise particulière |
|---|---|---|---|
| A37 GET /admin/work-items | category=admission/assistance/finance, cursor?, limit? | Fonction correspondante et portée objet ; 200 tâches et références de détail utiles | Filtrage serveur ; pas d'export de base ni scope choisi librement par l'écran. |
| A38 POST /admin/driver-applications/{applicationId}/decisions | expectedVersion, action, reasonCode, details, documentDecisions?, photoDecisions? | Admission habilitée, version précise ; 201 décision append-only et résultat du dossier | approve/request_correction/refuse/suspend/review selon K06 ; conditions essentielles contrôlées, nouvelle pièce non automatiquement approuvée. |
| A39 POST /admin/incidents/{incidentId}/decisions | expectedVersion, decisionKind, reasonCode, facts, affectedObjectVersion | Assistance habilitée au dossier ; 201 décision motivée et suites identifiées | Absence/contact ≥5 min, service partiel par arrêts/itinéraire de référence, exception sécurisée ; décision n'est pas un remboursement confirmé. |
| A40 POST /admin/financial-orders | operation, sourceDecisionId ou références métier selon K06, expectedVersion | Finance habilitée et réauthentification applicable ; 201 ordre durable ou 202 opération en suivi | Encaissement/compensation/versement/remboursement séparés ; bounds/parts serveur, aucune écriture arbitraire ni double ordre externe. |
| A41 GET /admin/audit | objectType?, objectId?, cursor?, limit? | Fonction audit et périmètre habilité ; 200 actions nettoyées et références | Pas de secrets/contenu de pièces ; droit audit n'accorde pas finance ou admission. |
| A42 POST /provider-events/{providerCode} | Enveloppe fournisseur qualifiée et preuve d'authenticité | Adaptateur fournisseur/compte attendu ; 202 événement reçu pour traitement, 200 doublon identique retrouvé | 401 authentification invalide, 400 enveloppe illisible, 422 contenu incohérent vérifié, 409 même ID/contenu différent ; aucun rejet de callback ne signifie paiement échoué. |

## K06 — Commandes fermées et contrôles utiles

**Trajet A16 :** publish contrôle admission, pièces, accès, itinéraire, véhicule, capacité et engagements ; start contrôle prise en charge ; finish exige toutes prestations traitées ; cancel porte reasonCode et traite les engagements affectés avec assistance lorsque nécessaire. Une nouvelle version d'itinéraire doit conserver les points convenus et projeter toutes occupations actives. Un changement de véhicule engagé ne se fait pas par une simple modification de body. Les demandes de modification non couvertes ici seront complétées à leur fonction, sans bypass.

**Service A22 :** arrive porte le point convenu et les éléments de présence documentés ; le serveur fixe l'arrivée et début d'attente. board porte le code temporaire, quantité reconnue et concordance du véhicule ; le serveur exige paiement confirmé, réservation admissible et pièces essentielles valides. finish porte l'arrêt de descente établi et les éléments de fin recevables ; elle finit cette prestation, pas le trajet ni le versement. Les erreurs ne permettent pas un champ « forcer ». Absence, interruption et embarquement manuel passent par A23/A39 avec preuves, motif et fonction assistance ; leurs commandes concrètes seront précisées à 13/16, sans valider un embarquement hors ligne par le seul téléphone.

**Candidature A28–A30 :** vehicleFields contient marque, modèle, année, couleur, immatriculation, capacité totale déclarée, places passagers proposées et déclaration du droit d'utilisation ; l'autorisation réelle relève de l'examen. photoView appartient à PH01–PH06 : avant, arrière/plaque, gauche, droite, habitacle avant, habitacle arrière. familyCode vient des exigences réellement qualifiées, pas d'une liste légale inventée. Les fileId doivent appartenir au même dossier/véhicule et avoir passé les contrôles requis. Dépôt et téléchargement sont protégés avant données réelles ; tailles/types, analyse et durée de conservation seront arrêtés au point pertinent.

**Admission A38 :** approve, request_correction ou refuse examinent la version exacte et ses fichiers ; suspend/review sont réservés à la situation autorisée avec motif et traitement des engagements existants. Chaque examen de document/photo indique son ID, sa version, résultat et motif. Une simple reprise de l'ancien « approve » ne valide pas une pièce remplacée.

**Finance A40 :** variantes fermées refund, payout, reconcile ou access_adjustment. refund référence la décision et le paiement ; le serveur vérifie l'origine encaissée, les montants initiaux et le cumul confirmé + ordres non résolus. payout référence des dettes admissibles et une destination vérifiée ; un chauffeur est réglé par réservation rapprochée. reconcile demande la vérification d'une référence déjà connue auprès du prestataire, sans statut financier imposé par l'agent. access_adjustment référence la décision, période et durée/preuve vérifiées selon R06 ; ce n'est pas un ajout libre de jours. Chaque variante a ses champs propres, son autorisation et son contrôle de version ; les variantes/champs inconnus sont refusés. Aucun DELETE de journal, de dette ou d'encaissement.

**Annulation d'une période programmée :** A23, category=scheduled_access_cancellation, référence la période et sa version. Si la demande recevable est reçue avant son début selon l'heure serveur, la même transaction inscrit la demande et annulation_en_examen ; l'activation est bloquée sans attendre l'agent ou le prestataire. A39 puis A40 établissent la décision et la restitution intégrale ; annulée_remboursement_en_suivi n'est pas annulée_remboursée. L'accès antérieur reste inchangé. Une finance inconnue ne réactive pas la période et le retard de revue ne convertit pas la demande en refus pour achat déjà commencé.

**Bonus et accès :** A13 fige P0/C0/D0, tarif/paramètres et versions. Bclient0/Bchauffeur0/W0, bénéficiaires et éligibilité sont établis une seule fois à la fin payée selon R05 ; des parts affichées avant sont conditionnelles. T25 n'est pas une route client : le serveur combine paiement rapproché, fin de service établie et contrôles, même lorsque leurs événements arrivent dans l'ordre inverse. Au nominal terminé, il établit les parts initiales ; pour un service interrompu reconnu, il conserve l'état interrompue, exige décision humaine et remboursement confirmé, puis ouvre uniquement les parts retenues R09, sans crédit automatique intégral. Si ce service interrompu est la toute première prestation, le déclenchement essai/fenêtre reste ouvert comme expliqué en K10. Première fin chauffeur payée nominale : essai unique de 604 800 secondes ; première fin payée nominale dans tout rôle : fenêtre unique de douze mois calendaires. Un achat confirmé attribue une seule période de 2 592 000 secondes, après accès actif et périodes payées déjà programmées, ou depuis confirmation serveur lorsque applicable. A32 donne le provisoire après approbation avant première prestation ; l'inactivité n'utilise pas l'essai.

## K07 — Erreurs compréhensibles

Ces associations constituent les choix du contrat WAKA. La sémantique HTTP suit [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html). Le corps d'erreur suit [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html) : son champ status correspond au statut réellement envoyé. Un 202 indique réception pour traitement, sans promettre son succès.

| HTTP | code WAKA stable / cas | Réaction attendue |
|---|---|---|
| 400 | INVALID_REQUEST, UNSUPPORTED_FIELD, IDEMPOTENCY_KEY_REQUIRED | Corriger format/champs ; aucune mutation métier demandée par champ interdit. |
| 401 | AUTHENTICATION_REQUIRED, SESSION_INVALID, VERIFICATION_INVALID ; callback PROVIDER_AUTH_INVALID | Reconnexion/vérification selon contexte ; aucune donnée d'autrui. Réponses de connexion évitent d'indiquer l'existence d'un compte. |
| 403 | ACTION_NOT_ALLOWED, SCOPE_REQUIRED | Fonction/situation ne permet pas l'action ; pas d'attribution de droit par relecture. |
| 404 | RESOURCE_NOT_FOUND | Objet absent ou inaccessible lorsque cette réponse évite de divulguer son existence. |
| 409 | VERSION_CONFLICT, WINDOW_EXPIRED, CAPACITY_UNAVAILABLE, VEHICLE_EXCLUSIVE, STATE_CONFLICT, IDEMPOTENCY_KEY_REUSED, PAYMENT_UNRESOLVED | Relire l'objet ; ne pas relancer avec une clé neuve pour contourner l'effet. Expiration métier reste 409, pas 408 réseau. |
| 413 / 415 | FILE_TOO_LARGE / CONTENT_TYPE_UNSUPPORTED | Corriger fichier ; limites réellement qualifiées affichées, pas d'approbation après échec. |
| 422 | INVALID_QUANTITY, ROUTE_OUTSIDE_SERVICE, APPLICATION_INCOMPLETE, INSUFFICIENT_AVAILABLE_FUNDS, PROVIDER_CONTENT_MISMATCH | Syntaxe lisible mais données non recevables ; corriger ou assistance. Le callback incohérent est rejeté/quarantainé, pas transformé en paiement échoué. |
| 429 | RATE_LIMITED | Attendre selon indication serveur et limites ; pas de boucle SMS ou recherche. |
| 503 | SERVICE_UNAVAILABLE, CHANNEL_UNQUALIFIED, POLICY_NOT_READY | Disponibilité/qualification manquante ; relire toute opération déjà engagée avant reprise. Aucune nouvelle règle tarifaire ou de droit déduite. |

L'API ne renvoie pas 200 avec un corps déclarant un échec HTTP, ni 409 avec status=422. Les champs detail, fieldErrors éventuels et correlationRef ne révèlent pas secrets, contenu de pièces, données d'autrui ou traces internes. La description aide l'utilisateur ; code guide le logiciel et reste stable. Une erreur réseau ou 5xx ne prouve jamais qu'aucun effet n'a eu lieu.

Un callback est authentifié **avant** d'appliquer un encaissement, remboursement, versement ou achat. Fournisseur/compte, identifiant, référence, montant, devise et statut sont rapprochés. Doublon identique : même effet ; événement ancien : pas de rétrogradation. Rejet, incohérence ou enveloppe suspecte : audit/quarantaine protégée et état attendu inchangé. Le protocole exact de signature, contrôle de replay et accusé réseau est à qualifier par fournisseur : l'adaptateur pourra traduire l'accusé contractuel sans diminuer les contrôles internes. Aucun accès de session client à A42 et aucune clé privée dans les exemples.

## K08 — Exemples documentaires nettoyés

Les libellés EXEMPLE_* sont des emplacements de documentation, pas des identifiants acceptables sur un serveur. Aucun téléphone, token, OTP, code d'embarquement ou référence financière réelle, même fictivement ressemblante, n'est fourni. Aucun appel n'a été exécuté.

Commande d'acceptation par le chauffeur destinataire :

```http
POST /api/v1/proposals/EXEMPLE_PROPOSITION/responses
Content-Type: application/json
Idempotency-Key: EXEMPLE_CLE_DE_REPRISE
```

```json
{
  "expectedVersion": 7,
  "itineraryVersion": 3,
  "action": "accept"
}
```

Réponse documentaire 201, Location: /api/v1/reservations/EXEMPLE_RESERVATION :

```json
{
  "reservation": {
    "id": "EXEMPLE_RESERVATION",
    "version": 1,
    "state": "retenue",
    "mode": "shared",
    "passengerCount": 3,
    "totalXaf": 1800,
    "currency": "XAF",
    "holdExpiresAt": "2026-10-11T10:03:00Z"
  },
  "payment": null,
  "serverTime": "2026-10-11T10:00:00Z",
  "idempotentReplay": false
}
```

La répétition autorisée retrouve cette réservation ; son état actuel se lit avec A18. Les bonus ne sont pas acquis dans cette réponse. Une acceptation concurrente qui n'a jamais gagné renvoie, par exemple, HTTP 409 et Content-Type: application/problem+json :

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Les places demandées ne sont plus disponibles. Relis la recherche.",
  "code": "CAPACITY_UNAVAILABLE",
  "correlationRef": "EXEMPLE_REFERENCE_DIAGNOSTIC"
}
```

Cet exemple illustre le contrat ; il n'est ni une course démontrée ni une preuve de protection exécutée.

## K09 — Matrice de correspondance principale

Une ligne relie une famille de routes à ses écrans, objets et transitions ; elle ne prétend pas qu'une route unique exécute toutes ces transitions. Les échéances, notifications et confirmations internes n'ont pas besoin d'une commande arbitraire du client.

| Famille de contrats | Écrans concernés | Objets principaux | Transitions et contraintes |
|---|---|---|---|
| A01–A07 : compte, droits personnels et reprise | E01–E04, E25 | D01–D03, D36, D39–D41 | S07 sessions/idempotence ; droits objet et vie privée. Authentification administrative renforcée à compléter. |
| A08–A13 : devis, recherche et réponse | E05–E08, E16 | D10–D18, D19, D40, D42 | T05–T10 ; seule T07 valide une acceptation ; devis/recherche/réponse et retenue distincts. |
| A14–A17 : trajet et position | E15–E17 | D06, D10–D13, D17–D19, D41–D42 | T01–T04, contrôle capacité/exclusivité/version ; position datée selon S02. |
| A18–A22 : prestation et annulation | E08–E11, E17 | D17–D20, D21–D25, D36, D39–D41 | T10–T16, T20–T24/T26 ; confirmation financière authentifiée ; code/fin propre à réservation. |
| A23–A25/A39 : assistance et décisions | E04, E11–E12, E22, E24, E27 | D17, D20, D24, D33, D36, D41–D42 | T17–T19, T27, T30, T39 ; no-show, interruption, contestation et exception motivées. |
| A26–A31/A38 : dossier et fichiers | E18–E22, E26 | D04–D09, D02, D41 | T33–T34/T40 ; versions examinées, private files et validités. |
| A32–A33 : accès et achat | E23–E24 | D21–D25, D30–D33, D42 | T35–T39 ; essai et période uniques, accès effectif calculé. |
| A34–A36 : parrains, dettes et gains | E13–E14 | D26–D29, D34–D35, D40–D42 | T25/T30–T32 ; éligibilité fin payée, retrait réservé, versement chauffeur séparé. |
| A37/A40–A42 : administration et flux externes | E25–E29 | D02–D03, D05, D21–D29, D33, D36, D39–D42 | T21–T32, T35, T38–T40 selon objet ; preuve externe, journaux équilibrés et compensations. |

Les 29 fiches, 42 objets et 40 transitions sont des **sources vérifiées**, pas 42 fonctions construites. D37 Avis et D38 PartageSuivi n'ont pas de route dédiée dans ce minimum : leurs contrats seront complétés aux fonctions concernées, avec preuve de prestation ou accès temporaire révoqué. L'authentification administrative, les attributions de droits, l'arrêt des nouveaux engagements, les demandes de changement d'itinéraire, la diffusion des photos sélectionnées et le détail des traitements de confidentialité seront également complétés avant leur code ; les routes présentes ne donnent pas un accès indirect libre à ces actions. D39–D42 sont surtout internes et ne disposent pas d'une API publique de mutation.

## K10 — Limites et suite

Cette référence ne tranche pas la coordination d'un achat particulier **avant toute première prestation** avec le futur essai. A33/A32 doivent expliquer cette précision encore manquante ; aucune suppression, report ou prolongation d'essai n'est inventé. Le cas reste à arrêter avant activation de sa fonction : POLICY_NOT_READY décrit alors une configuration incomplète, pas une interdiction commerciale adoptée. De même, les combinaisons de remboursement/prolongation d'accès restent à préciser.

Le premier déclenchement essai et fenêtre de parrainage lorsqu'une **première prestation est interrompue** reste à qualifier avant 12.02/15.02. Ni essai ni fenêtre ne sont accordés ou supprimés automatiquement sur ce seul état. Les parts conservées R09 des bénéficiaires dont l'éligibilité est déjà établie restent traitables ; les cas où ce premier déclencheur conditionne un bonus nécessitent qualification humaine avant attribution. Cette limite ne modifie pas la répartition proportionnelle adoptée.

Pour le règlement d'une interruption reconnue, « remboursement confirmé » signifie confirmation des sommes effectivement dues. Si la décision motivée R09 établit qu'aucun remboursement n'est dû (0 F après arrondi), elle permet le règlement des parts conservées sans ordre externe fictif de 0 F. Les remboursements positifs réellement dus restent suivis jusqu'à confirmation.

Restent extérieurs : contrat Mobile Money et possibilités de redistribution/remboursement, frais/minimums/délais, canal Android autorisé, qualification transport/documents/assurances, SMS, cartes, disponibilité et obligations de conservation. Les preuves HTTP/JSON ou cette rédaction ne remplacent ni contrat, ni contrôle d'accès exécuté, ni test de concurrence et d'intégration.

**Coût observé : 0 F d'achat ou service facturable.** Lectures de sources et rédaction avec outils existants ; aucun SMS, géocodage, débit, hébergement ou abonnement activé. Les coûts futurs sont ceux à qualifier et provisionner, sans tarif logiciel supplémentaire inventé.

Dans le dossier documentaire, ouvrir cette référence avec :

```powershell
Set-Location 'DOSSIER_DOCUMENTAIRE_FONDATEUR'
code 'outputs/WAKA_contrats_api.md'
```

Ctrl+Maj+V affiche l'aperçu Markdown. Cette commande ouvre du texte ; elle ne démarre pas le serveur. Les contrôles réellement faits sont consignés dans 03.08_bilan.md (reference detaillee conservee hors du depot). Dans le lot autorisé, 03.09 précise les accès/conservation ; 03.10 devra être présenté avant réalisation, puis 04.01 préparera l'organisation documentaire du dépôt. La revue commune reste attendue à la fin du lot.

## K08 — Complément technique P01, réalisé au point 05.01

15 octobre 2026. Ce précontrôle est distinct des 42 contrats métier précédents ; A01/A02 ne sont pas installés par cette réalisation.

`POST /api/v1/identity/phone-format` reçoit un JSON UTF-8 avec une seule propriété chaîne `phoneNumber`, sans doublon, propriété supplémentaire, racine suivante ou valeur non chaîne. Le corps effectivement reçu est limité à 512 octets, même en transfert chunked. La chaîne est limitée à 40 caractères. La convention mobile camerounaise contrôlée est neuf chiffres ASCII commençant par 6, avec préfixe facultatif `+237` ou `00237` ; espaces ASCII/insécables/insécables fines, tirets ASCII et parenthèses sont retirés pour le contrôle. Les autres caractères et préfixes sont refusés. Il s’agit d’une convention syntaxique ; elle ne prouve ni attribution, opérateur, existence ni possession du numéro.

Réponse 200 exactement : `{"formatValid":true,"ownershipVerified":false,"country":"CM"}`. Le numéro normalisé reste en mémoire pour le contrôle et n’est ni renvoyé ni stocké. Aucun compte, challenge, session, accès base, SMS ou prestataire n’est appelé. Ce calcul sans état ni effet métier ne demande pas d’Idempotency-Key ; K04 continue de s’appliquer aux commandes métier qui créent un effet. Les erreurs génériques de 04.07 couvrent notamment 400, 413, 415 et 405 ; le contenu reçu n’est pas recopié. La référence est générée par le serveur, avec `no-store`.

Flutter aide d’abord à corriger la saisie, puis interroge le serveur avec la configuration locale autorisée de 04.05/04.06. La réponse exacte est contrôlée, limitée à 4 KiB, sans redirection ; délais de connexion/total de 3/8 secondes et fermeture à la fin de l’appel ou de l’écran. Le formulaire bloque le double clic pendant l’attente, signale les pannes sans ouvrir de session et explique la vérification par code encore nécessaire. L’écoute HTTP est limitée à la boucle locale ; aucun échange réel de production n’est autorisé par ce précontrôle.

Les sept tests serveur de format/contrôleur, les 14 tests Flutter de la suite courante, les 17 contrôles HTTP réels (dont deux en transfert chunked) et l’émulateur local sont consignés au bilan 05.01. Les tests de préparation et l’émulateur ne prouvent pas une réception de SMS. Le canal, les protections d’envoi et leurs coûts restent à qualifier en 02.11/05.02.

## K11 — Raccordement de connexion, sous-points 05.03.b–d

17 octobre 2026. Complément technique implémenté ; activation réelle et contrôle Android natif restent ouverts. Les preuves et limites figurent dans les bilans 05.03.b/c et 05.03.d ; ce paragraphe ne constitue pas un résultat de test. K11 évite toute confusion avec la matrice K09 déjà présente.

A01 précise le contexte : JSON avec exactement phoneNumber, purpose=login et clientInstanceRef, plus Idempotency-Key UUID canonique. La réponse 202 contient challengeId, submission, expiresAt et destinationHint (deux chiffres visibles seulement). ACCEPTED décrit une acceptation de demande par le transport, jamais une réception prouvée sur le téléphone.

A02 précise le téléphone lié : JSON avec exactement phoneNumber, code et clientInstanceRef, même défi dans l'URL et Idempotency-Key. Le téléphone normalisé est contrôlé contre le défi avant consommation ; la commande lie destination, contexte, défi et code. Une demande refusée avant validation peut être corrigée ; après enregistrement, mêmes entrées et clé pour la reprise, nouvelle clé pour un code modifié. Le serveur accepte les variantes de présentation qui normalisent vers la même destination.

La partie réalisée d'A02 délivre la session : sessionId, accountId, tokenType=Bearer, token, absoluteExpiresAt, idleExpiresAt. Le profil prévu dans le contrat complet est différé à 05.04 ; aucun rôle ni profil factice n'est renvoyé, A04 /me restait à réaliser à cette date ; sa réalisation du 18 octobre est décrite dans K12. Le secret doit aller dans le stockage protégé du mobile ; aucune valeur réelle dans les exemples ou le dossier.

Une reprise autorisée retrouve le même jeton/session, uniquement avant la plus courte des limites de reprise, preuve OTP, inactivité initiale et expiration absolue. Elle ne prolonge rien et revérifie le compte actif et la session non terminée. Le résultat temporaire est chiffré avec une clé dédiée injectée hors des sources. Expiration/déconnexion et entretien effacent le chiffré ; les marqueurs empêchent de recréer l'effet. Paramètres/gestion des clés et entretien restent à raccorder avant activation.

A03 utilise un corps vide, Authorization: Bearer, X-Waka-Client-Instance et Idempotency-Key. Le contrôle atomique relie jeton, contexte, session ciblée et clé persistante ; une autre cible ne peut être révoquée avec ce jeton. Une répétition exacte peut retrouver 204 même après la première déconnexion ; réutiliser la clé sur une autre cible donne 409. Une nouvelle clé sur une session déjà révoquée n'est pas présentée comme la reprise identifiée.

Les requêtes refusent doublons/champs inconnus et paramètres URL ; JSON UTF-8 limité à 1 024 octets réels pour A01/A02. Réponses et erreurs no-store. Codes utiles : 400,401,409 IDEMPOTENCY_KEY_REUSED,413,415,429,503. Une installation sans LoginService qualifié répond 503 aux commandes bien formées, sans transport SMS. L'environnement public HTTPS n'est pas activé par ces routes locales. Le parcours mobile indique les pannes et les reprises incertaines, sans annoncer une connexion ou une révocation serveur avant confirmation. Aucun test n'active un faux compte dans l'application.

Le fondateur autorise aussi le développement des intégrations nécessitant ensuite un service payant. Préférer une solution gratuite ou moins chère lorsque qualité/couverture/fiabilité conviennent ; une dépense concrète doit être présentée avant achat. Les accès et qualifications manquants bloquent l'activation réelle concernée, pas la préparation du code. Le SMS reste en attente jusqu'à demande explicite de reprise.

## K12 — Lecture du compte courant, sous-point 05.04.a

18 octobre 2026. A04 est précisé par `GET /api/v1/me`, sans corps ni paramètres d'URL, avec exactement un en-tête Authorization: Bearer et un X-Waka-Client-Instance. Aucun identifiant de compte fourni par le client ne permet de sélectionner une autre personne. Cette lecture authentifiée constitue une activité : elle renouvelle l'inactivité dans la limite de la vie absolue initiale.

Réponse 200 plate : accountId, sessionId, absoluteExpiresAt, idleExpiresAt, accountState=ACTIVE, roles=[CLIENT], serverTime. Les dates sont des instants UTC. Aucun téléphone, jeton, code ou empreinte privée. CLIENT est le droit de base d'un compte actif ; il ne représente ni une identité civile vérifiée ni une attribution D02 ou une admission chauffeur. A05 et les autres profils/droits restent à construire. Réponse et erreurs : no-store ; 401 pour preuve absente/invalide ou session refusée, 400 pour entrée ambiguë, 415 pour encodage interdit, 503 pour service absent ou validation indisponible. L'assemblage MeService reste désactivé par défaut.

La lecture et la révocation sont ordonnées par la garde serveur. Une prélecture indexée refuse un jeton inconnu sans prendre cette garde ; elle n'accorde jamais l'accès et ne remplace pas la limitation du trafic. Le contrôle complet du compte, du contexte et des échéances se fait sous verrou. Une ancienne session connue peut encore solliciter la garde : charge hostile, contention résiduelle et rétention restent à traiter avant exposition publique.

Le mobile contrôle l'appartenance de la réponse à la session envoyée et ne déduit aucun rôle du cache. Une session restaurée reste non confirmée jusqu'au retour valide ; une panne garde cet état. Un refus retire l'accès et déclenche l'effacement protégé local, dont l'échec reste visible. Une déconnexion en attente conserve la priorité. L'heure du téléphone ne remplace pas l'heure du serveur pour valider les échéances reçues. Les bilans datés indiquent les contrôles réellement exécutés, sans qualifier SMS, appareils physiques ou lancement public.

## K13 — Profil modifiable, sous-point 05.04.b

18 octobre 2026. Contrat précisé pour la réalisation en cours ; les résultats de vérification sont consignés séparément au bilan. A05 reste partiel : seul le nom d'affichage est traité ; marketingChoice est refusé jusqu'à 05.07, sans présumer un consentement ou un refus. Le nom reste facultatif et ne vérifie pas l'identité civile.

`GET /api/v1/me/profile` complète A04 sans modifier les sept champs de `/me`. Il reçoit uniquement les preuves Bearer et X-Waka-Client-Instance, sans corps ni paramètres. Réponse exacte à six champs : accountId, version, displayName, updatedAt, serverTime, idempotentReplay. Pour le profil initial non modifié : version 1, displayName et updatedAt null, idempotentReplay false ; la lecture ne crée aucune ligne de profil.

`PATCH /api/v1/me` reçoit exactement expectedVersion et displayName, avec les mêmes preuves et un Idempotency-Key UUID canonique. expectedVersion est un entier JSON positif au plus égal à 9 007 199 254 740 991. displayName null signifie effacer ; une chaîne est normalisée NFC, espaces de bord supprimés, bornée à 1–80 points de code et 320 octets UTF-8, sans contrôles, formats invisibles ou séquence Unicode malformée. Aucun changement de téléphone, rôle, paiement ou approbation n'est accepté. Corps UTF-8 limité à 2 048 octets réellement reçus, pas de doublons, champs supplémentaires, paramètres ni encodage de transport non pris en charge.

La validation de session, la vérification du titulaire, le contrôle de version, la mutation et son marqueur de reprise se font dans une même transaction sous la garde de session, avant libération de celle-ci. La première modification passe de version 1 à 2 ; les modifications suivantes incrémentent la version. Une commande fondée sur une version périmée donne 409 VERSION_CONFLICT et impose une relecture avant une nouvelle intention. Une révocation commise avant la commande interdit celle-ci.

L'identité de commande est liée au compte et à l'opération. Même clé et contenu normalisé : résultat original à six champs, avec idempotentReplay true ; serverTime est alors l'heure du résultat initial. Une relecture est nécessaire pour connaître le profil courant. Une clé réutilisée avec d'autres entrées donne 409 IDEMPOTENCY_KEY_REUSED. La réponse de reprise est chiffrée et temporaire, avec durée injectée explicitement ; après expiration, 410 REPLAY_EXPIRED signifie que le résultat enregistré ne peut plus être restitué, sans autoriser une nouvelle exécution de la commande. Le marqueur reste conservé. Ce délai technique ne définit aucune durée légale de conservation.

Le nom et les résultats enregistrés sont chiffrés avec une clé dédiée, et leur intégrité liée au compte et à la version ; aucune valeur claire dans les tables ou empreintes de commande non protégées. Rotation, conservation et qualification de la gestion des clés restent à compléter avant des données réelles. L'assemblage du service reste explicite et désactivé par défaut : requêtes valides sans service, 503. Réponses et erreurs no-store, sans secret ou nom dans les messages de diagnostic.

Flutter doit sauvegarder la commande dans son stockage protégé avant tout PATCH. Une réponse perdue conserve la même clé et les mêmes entrées. Déconnexion et session refusée retirent l'accès au profil ; aucune commande d'un ancien compte ne passe à un autre. Après succès/reprise, relire le profil avant une autre édition. Une erreur de stockage, un refus ou un conflit ne doit pas afficher une sauvegarde réussie.

### K14 — Candidature brouillon et véhicule déclaré (05.04.c.1)

Routes réalisées côté serveur, non assemblées par défaut (503 sans configuration qualifiée) : A26 `POST /api/v1/driver-applications` (corps exact `requestedCategory` = `taxi` ou `private`, Idempotency-Key, réponse 201), A27 `GET /api/v1/driver-applications/{applicationId}`, A28 `PATCH /api/v1/driver-applications/{applicationId}` limité à `expectedVersion` et à l'objet `vehicleFields`.

Précision technique : `GET /api/v1/me/driver-application` relit le dossier ouvert du titulaire sans connaître son identifiant (404 s'il n'existe pas). Un seul dossier ouvert par compte : 409 `APPLICATION_ALREADY_OPEN`.

Contenu de `vehicleFields` :
- exactement huit clés, chacune pouvant valoir `null` (inconnu en brouillon) : brand, model, year, color, registration, authorizedTotalCapacity, offeredPassengerSeats, usageRight (`holder` ou `authorized_use`) ;
- l'objet complet remplace le précédent ;
- les places proposées restent au plus min(5, capacité totale − 1), chauffeur compris dans le total ;
- les textes Unicode sont contrôlés comme le nom de profil (au plus 40 caractères) ;
- l'immatriculation est mise en majuscules (`A–Z 0–9 espace tiret`, de 2 à 16 caractères) ;
- l'année va de 1900 à l'année serveur + 1, la capacité totale de 2 à 99.

Ce sont des bornes de saisie, pas des règles légales.

Réponse à onze champs : applicationId, accountId, requestedCategory, state (`draft` seulement), version, vehicleFields, missingVehicleFields, createdAt, updatedAt, serverTime, idempotentReplay.

Comportement :
- le dossier d'un autre candidat répond 404 `RESOURCE_NOT_FOUND`, comme un identifiant inconnu ;
- les codes 409 `VERSION_CONFLICT` et `IDEMPOTENCY_KEY_REUSED`, et 410 `REPLAY_EXPIRED`, suivent le profil ;
- les champs véhicule et les résultats de reprise sont chiffrés avec des clés dédiées, liés au compte, au dossier et à la version.

Refusés jusqu'à leur point : `documentRefs` et `photoRefs` (06.03), l'envoi A29, les décisions A38 et les habilitations D02 (05.05), et tout état ou approbation transmis par le client. Un brouillon ne donne ni admission, ni rôle chauffeur, ni accès commercial.
### K15 — Accès administrateur nominatif (05.05)

Routes réalisées côté serveur, non assemblées par défaut :

| Route | Corps | Réponse |
|---|---|---|
| `POST /api/v1/admin/second-factor/enrollments` | sans corps | 201 avec `secret` et `otpauthUri`, montrés une seule fois |
| `POST /api/v1/admin/second-factor/confirmations` | `{code}` | 204 |
| `POST /api/v1/admin/sessions` | `{code}` | 201 avec adminSessionToken, adminSessionId, functions, idleExpiresAt, absoluteExpiresAt, serverTime |
| `GET /api/v1/admin/me` | sans corps | 200 avec les fonctions actuelles |
| `POST /api/v1/admin/sessions/current/revocations` | sans corps | 204 |

Un code doit faire exactement six chiffres ; un code mal formé est refusé en 400 et ne compte pas comme un échec. La session administrative se présente dans l'en-tête `X-Waka-Admin-Session` (43 caractères base64url), en plus de la session client.

Codes d'erreur :
- 403 `ACTION_NOT_ALLOWED` : aucune habilitation active ;
- 409 `ENROLLMENT_REQUIRED` ou `ALREADY_ENROLLED` ;
- 401 `INVALID_SECOND_FACTOR` (code faux ou déjà utilisé) ou `INVALID_ADMIN_SESSION` ;
- 429 `RATE_LIMITED` après des échecs répétés.

**Exception explicite à l'Idempotency-Key :** l'ouverture de session administrative n'est pas rejouable. Un code TOTP ne sert qu'une fois, aucun jeton n'est conservé de façon récupérable, et une réponse perdue se résout avec un nouveau code ; la session orpheline expire par inactivité.

Aucune attribution d'habilitation par HTTP dans ce point. Le premier accès du fondateur est une opération de maintenance locale, unique et documentée (`identity/admin/README.md`). Les fonctions sont relues à chaque requête : un retrait d'habilitation prend effet immédiatement.
### K16 — Photos du véhicule et stockage privé (06.03/06.04)

Routes réalisées côté serveur, non assemblées par défaut :

| Route | Rôle |
|---|---|
| `POST /api/v1/driver-applications/{applicationId}/photos/{PH01..PH06}` | Corps brut `image/jpeg` ou `image/png` de 10 Mo au plus, avec Idempotency-Key ; réponse 201 (fileId, applicationId, view, state, width, height, byteSize, createdAt, idempotentReplay). |
| `GET /api/v1/driver-applications/{applicationId}/photos` | État des six vues, `missing` ou `received`. |
| `GET /api/v1/private-files/{fileId}/content` | A31, titulaire seulement. Réponse `image/jpeg` avec `no-store`, `nosniff` et une CSP fermée. |

Pour les photos, cette route remplace le multipart prévu par A30 : le corps est lu avec une limite stricte, et aucun analyseur générique n'est ouvert.

Contrôles appliqués :
- le format est reconnu par ses octets de signature, en accord avec le type annoncé ;
- les dimensions vont de 320 × 240 à 8 000 × 8 000 (40 mégapixels au plus) et sont contrôlées avant le décodage ;
- l'image est réencodée en JPEG sans métadonnées (le GPS est retiré), et seule cette version nettoyée est conservée ;
- le stockage est chiffré en AES-GCM avec une clé dédiée, sous un nom aléatoire, dans un dossier injecté hors de Git.

Refus :
- 400 `FILE_REJECTED` (fichier invalide), 415 (type non accepté), 413 (trop gros) ;
- 404 `RESOURCE_NOT_FOUND`, aussi bien pour un dossier ou un fichier d'un autre compte ;
- 409 `IDEMPOTENCY_KEY_REUSED` ou `NOT_EDITABLE`.

Un dépôt n'est jamais une approbation. Une nouvelle photo d'une vue remplace la précédente, qui reste dans l'historique comme `replaced`. Pas d'antivirus à ce stade : c'est une limite à lever avant les vraies données. Les justificatifs (familles de pièces à qualifier) ne passent pas par ces routes.
### K17 — Envoi du dossier et examen d'admission (06.08.a)

Routes réalisées côté serveur, non assemblées par défaut :

| Route | Rôle |
|---|---|
| A29 `POST /api/v1/driver-applications/{id}/submissions` | Corps exact `{expectedVersion}` avec Idempotency-Key. Exige les huit champs véhicule, les six photos courantes **et** des exigences de pièces déclarées qualifiées dans la configuration (par défaut : non). Réponses 409 `REQUIREMENTS_NOT_QUALIFIED`, `INCOMPLETE`, `NOT_EDITABLE` ou `VERSION_CONFLICT`. |
| `GET /api/v1/driver-applications/{id}/decisions` | Le candidat lit les décisions et motifs qui le concernent, sans le nom de l'agent. |
| A37 `GET /api/v1/admin/work-items?category=admission` | Seule catégorie existante ; les autres sont refusées en 400. |
| `GET /api/v1/admin/driver-applications/{id}` | Version envoyée, véhicule, photos courantes et historique des décisions. |
| A38 `POST /api/v1/admin/driver-applications/{id}/decisions` | `{expectedVersion, action, reasonCode, details?, photoDecisions?}`. |
| A31 côté agent : `GET /api/v1/admin/private-files/{fileId}/content` | Lecture d'une photo d'un dossier envoyé, tracée dans `audit_event`. |

Toutes les routes d'agent exigent la session client, la session administrative (`X-Waka-Admin-Session`) et la fonction ADMISSION, contrôlées **dans la même transaction** que l'effet.

Règles de décision :
- actions `request_correction` et `refuse` ; `approve` répond 409 `APPROVAL_NOT_AVAILABLE` tant que les pièces ne sont pas qualifiées ;
- `reasonCode` respecte `[A-Z][A-Z0-9_]{2,47}` ; `details` fait au plus 500 caractères et est chiffré ;
- `photoDecisions` est lié à l'identifiant exact de chaque photo courante ;
- une décision ne s'ajoute qu'une fois par version examinée ;
- 403 `CONFLICT_OF_INTEREST` si l'agent examine son propre dossier.

États : `draft` → `submitted` → `correction_requested` (modifiable, renvoi possible) ou `refused` (fermé, nouveau dossier possible). La réponse du dossier garde ses onze champs ; seule la liste des états grandit.
### K18 — Pièces justificatives et approbation (catalogue v1)

Le catalogue des pièces est décrit dans `WAKA_pieces_chauffeur.md`. Chaque exigence est `established` (établie) ou `to_confirm` (à confirmer). Routes réalisées côté serveur, non assemblées par défaut :

| Route | Rôle |
|---|---|
| `GET /api/v1/driver-applications/{id}/requirements` | Pièces applicables à la catégorie et au droit d'utilisation déclarés, seulement celles-là. Champs : code, document, explanation, status, requiredForSubmission, documentId, reviewState (`received`/`accepted`/`to_correct`), validUntil, expired, satisfied. Une exigence `to_confirm` n'est jamais `satisfied`. |
| `POST /api/v1/driver-applications/{id}/documents/{code}` | Image brute JPEG ou PNG de la pièce, avec Idempotency-Key et en-tête facultatif `X-Waka-Valid-Until: yyyy-MM-dd`. Contrôlée et nettoyée comme les photos, puis chiffrée. Le PDF n'est pas accepté à ce stade. |
| `GET /api/v1/driver-documents/{documentId}/content` | Lecture par le titulaire. |
| `GET /api/v1/admin/driver-documents/{documentId}/content` | Lecture par un agent ADMISSION, uniquement pour un candidat ayant déjà envoyé un dossier ; tracée dans le journal d'audit. |

Refus au dépôt :
- 409 `DOCUMENT_NOT_APPLICABLE` : pièce étrangère à la situation (titre de taxi pour une voiture personnelle, autorisation d'utilisation pour le titulaire de la carte grise…) ;
- 409 `REGISTRATION_REQUIRED` : pièce du véhicule déposée avant l'immatriculation ;
- 400 `DOCUMENT_EXPIRED` : date de validité déjà dépassée.

Réutilisation :
- les pièces de la **personne** (identité, permis) servent d'un dossier à l'autre ;
- les pièces du **véhicule** servent tant que l'immatriculation est la même (clé opaque) ;
- une pièce acceptée et encore valide reste acceptée au renouvellement ;
- une pièce expirée redevient à fournir.

Envoi A29 : il exige, en plus des champs et des photos, une pièce présente, valide et non « à corriger » pour chaque exigence **établie**. Les pièces des exigences **à confirmer** sont facultatives.

Décision A38 : elle accepte `documentDecisions: [{documentId, result, reasonCode?}]`. Le type de pièce est fixé par le serveur ; l'agent ne peut pas l'envoyer.
- `request_correction` ne fait corriger que les pièces marquées `to_correct` ; les autres restent acceptées.
- `approve` est refusé en 409 `APPROVAL_NOT_AVAILABLE` tant qu'une exigence applicable est **à confirmer**.
- `approve` est refusé en 409 `INCOMPLETE` si une pièce n'est pas acceptée et valide, si les six photos ne sont pas jugées acceptables dans la décision, ou s'il manque un champ du véhicule.
- La version du catalogue est enregistrée avec chaque décision.

Avec le catalogue v1, chaque parcours compte au moins une exigence à confirmer : aucune approbation n'est donc possible avant la décision datée du fondateur.
### K19 — Habilitations et journal d'audit (06.09)

K15 réservait l'attribution des habilitations à la maintenance locale. L'attribution initiale du fondateur y reste : elle n'est possible qu'une fois, tant qu'aucune habilitation n'existe. Toutes les suivantes passent par HTTP, entre agents déjà habilités. Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503).

| Route | Fonction exigée | Rôle |
|---|---|---|
| `GET /api/v1/admin/grants` | HABILITATIONS | Habilitations actives et retirées, les plus récentes d'abord (200 au plus). |
| `POST /api/v1/admin/grants` | HABILITATIONS | Corps `{accountId, function, reasonCode}`. Répond 201 pour une nouvelle habilitation. Si une habilitation identique est déjà active, la même est rendue en 200 : une commande répétée n'a qu'un seul effet. |
| `POST /api/v1/admin/grants/{grantId}/revocations` | HABILITATIONS | Corps `{reasonCode}`. Retrait daté et motivé. Une habilitation déjà retirée est rendue telle quelle, avec son premier motif. |
| `GET /api/v1/admin/audit` | AUDIT | A41. Paramètres facultatifs `objectType`, `objectId`, `cursor` (identifiant de la dernière entrée déjà lue) et `limit` (1 à 100, 50 par défaut). Entrées de la plus récente à la plus ancienne. |

Règles :
- `reasonCode` est un code en majuscules (`[A-Z][A-Z0-9_]{2,47}`), jamais un texte libre.
- Personne ne s'attribue ni ne se retire une habilitation : refus 403 `SELF_GRANT_FORBIDDEN`. Un agent ne peut donc pas élever ses propres droits.
- Le compte cible doit exister et être actif, sinon 404 `RESOURCE_NOT_FOUND`. La même réponse vaut pour une habilitation inconnue.
- Le dernier titulaire actif de HABILITATIONS ne peut pas perdre cette fonction : 409 `LAST_HABILITATIONS_HOLDER`. Cette garde est défensive : l'interdiction de se retirer sa propre habilitation l'empêche déjà en pratique.
- L'effet est immédiat : à l'appel suivant, `withAdmin` relit les habilitations dans la même transaction que l'opération.
- Chaque attribution (`ADMIN_GRANT`) et chaque retrait (`ADMIN_REVOKE`) sont inscrits dans `audit_event`, avec l'agent, la session d'administration et le motif.
- Le journal ne renvoie que des références opaques et des codes : jamais le contenu d'une pièce, un nom, un téléphone ou un secret.
- La fonction AUDIT n'accorde ni l'admission ni la finance, et inversement.
- Paramètres inconnus, répétés ou encodés : 400, sans appel au service.

### K20 — Devis A08 (préparé, non assemblé)

`POST /api/v1/quotes`, réservé au client connecté, exige `Idempotency-Key`. Corps exact : `{mode, passengerCount, origin:{latitude, longitude}, destination:{latitude, longitude}}`.

- `mode` vaut `shared` ou `vip`.
- `passengerCount` va de 1 à 5. En partagé, c'est le nombre de places payées. En VIP, ce sont les occupants : ils ne changent jamais le prix, égal à 25/2 fois une place.
- Les coordonnées sont des nombres décimaux simples, avec six décimales au plus. Une notation exponentielle, une chaîne de caractères, une valeur hors bornes ou deux points identiques sont refusées (400).
- **Aucun prix, aucune distance, aucune version de grille ni aucune tranche n'est accepté du client** : un champ en plus est refusé (400).

Calcul côté serveur, dans cet ordre :
1. Vérification que les deux points sont dans le territoire servi.
2. Distance routière de référence fournie par le fournisseur d'itinéraire du serveur, demandée hors de la transaction de session.
3. Arrondi au mètre, moitié vers le haut (R04).
4. Prix tiré de la version de grille **configurée explicitement** (aucune version « active » implicite).
5. Arrondi au franc seulement à la fin.

Réponse 201 : `quoteId, mode, passengerCount, distanceMetres, fareVersion, bandCode, placePriceXaf, totalXaf, currency, createdAt, expiresAt (+300 s), idempotentReplay, route`. Les points ne sont jamais renvoyés ; ils sont stockés chiffrés. `route` est le trajet routier du client (polyligne encodé, forme simplifiée, tracé en bleu), obtenu dans le même appel Google que la distance, sans coût en plus. Il sert seulement à l'affichage et n'est gardé qu'en mémoire : il peut valoir `null` (fournisseur sans tracé, tracé illisible, ou réponse rejouée après un redémarrage).

Même clé, même demande : le même devis est rendu en 200, même s'il a expiré. Son prix et son échéance ne changent pas, et le fournisseur n'est pas rappelé. Même clé avec une autre demande : 409 `IDEMPOTENCY_KEY_REUSED`.

Refus :
- 422 `OUTSIDE_SERVICE_AREA` : point hors du territoire, sans appel au fournisseur ;
- 422 `NO_ROUTE` : aucun itinéraire entre les deux points ;
- 422 `DISTANCE_OUT_OF_RANGE` : plus de 17 900 m après arrondi ;
- 503 `ROUTE_UNAVAILABLE` : fournisseur sans réponse fiable ;
- 503 : service non assemblé.

**Non assemblé** tant que trois points restent ouverts : le fournisseur d'itinéraire (API cartographique externe, reportée à la fin par le fondateur), le territoire servi (R10) et la version de grille mise en vente (aucune date de vente adoptée). Le devis ne réserve rien. A09 et la suite restent à faire.

### K21 — Demandes sur ses données (A06) et activité du compte (A07)

Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503).

**`POST /api/v1/me/privacy-requests`** exige `Idempotency-Key`. Corps : `{kind, details?}`.
- `kind` vaut `access` ou `deletion`.
- `details` est un texte facultatif de 1 à 500 caractères (sauts de ligne admis, aucun caractère de contrôle caché). Il est stocké chiffré et n'est jamais renvoyé.
- Réponse 201 : `{requestId, kind, state:"received", receivedAt, created:true}`.

Une seule demande ouverte par type et par compte :
- même clé et même demande : la même réponse, en 200 ;
- même clé avec une autre demande : 409 `IDEMPOTENCY_KEY_REUSED` ;
- nouvelle demande d'un type déjà ouvert : la demande ouverte est rendue en 200, avec `created:false`. Le nouveau texte n'est pas conservé.

**Recevoir une demande de suppression ne supprime rien, ne ferme pas le compte et n'annonce aucune suppression totale.** Le traitement suit la procédure A05 de `WAKA_acces_et_conservation.md`. Les durées de conservation, les exceptions et la fonction habilitée qui traitera les demandes restent à qualifier.

**`GET /api/v1/me/activity`**, paramètres facultatifs `cursor` et `limit` (1 à 50, 20 par défaut).
- Réponse : `{items:[{objectType, objectId, state, at}], nextCursor}`, les éléments les plus récents d'abord.
- Contenu : le dossier chauffeur, les demandes sur ses données et les devis (`valid`/`expired`) du seul titulaire. Réservations, paiements et achats s'ajouteront avec leurs points.
- La lecture ne modifie rien. Un paramètre inconnu ou répété est refusé (400).

### K22 — Code et rattachement de parrainage (A34)

Ces routes appliquent R05. Elles sont réalisées côté serveur et ne sont pas assemblées par défaut (503).

- **`GET /api/v1/me/referral`** renvoie `{referralCode, referrerAttached, attachedAt}`.
  - Le code est attribué à la première lecture, puis ne change plus.
  - Il compte 8 caractères, sans 0, O, 1 ni I, pour pouvoir être dicté ou recopié sans erreur.
  - Il ne révèle aucune identité.
- **`POST /api/v1/me/referral-linkages`**, corps `{referralCode}`. Le premier rattachement répond 201. Le même code renvoyé répond 200 avec le même rattachement (idempotence naturelle, sans clé).

Refus :
- 422 `SELF_REFERRAL` : c'est son propre code ;
- 422 `CODE_NOT_RECOGNIZED` : code inconnu, ou titulaire du code désactivé. La réponse est la même dans les deux cas, pour ne pas confirmer l'existence d'un code à un inconnu ;
- 409 `REFERRER_ALREADY_SET` : un autre parrain est déjà rattaché. Il n'y a pas de réattribution libre : une correction passe par l'assistance, avec justification et journal.

Un seul niveau : le parrain est gardé tel quel, sans chaîne. Le rattachement ne crée aucun bonus ; les bonus naîtront des réservations payées et terminées (R03, R05).

**À décider avant activation :**
1. Jusqu'à quand un compte peut-il rattacher un parrain ? Par exemple, seulement avant sa première réservation payée et terminée. R05 ne fixe pas ce délai, et aucun délai n'est appliqué tant que la décision n'est pas prise ; aucune réservation n'existe encore.
2. Deux comptes peuvent-ils se parrainer mutuellement ? Ce n'est pas un auto-parrainage, et ce n'est pas interdit en l'état.
3. Faut-il limiter le nombre d'essais de codes par compte ? Il y a environ 850 milliards de codes possibles, mais aucune limite n'est encore appliquée.

### K23 — Accès effectif du chauffeur (A32, lecture seule)

`GET /api/v1/driver/access` renvoie `{access, basis, applicationId, category, admissionState, reasons, documentsToRenew, trial, serverTime}` pour le seul titulaire. Comme les routes précédentes, elle est réalisée côté serveur et n'est pas assemblée par défaut (503).

| Situation | `access` | `basis` / `reasons` |
|---|---|---|
| Aucun dossier | `none` | `NO_APPLICATION` |
| Dossier non approuvé (brouillon, envoyé, correction demandée, refusé) | `none` | `NOT_APPROVED` |
| Taxi approuvé, pièces essentielles valides | `active` | `taxi_no_subscription` : aucun abonnement pour un taxi (R02/R06) |
| Particulier approuvé, pièces valides | `provisional` | `provisional_before_first_service`, `trial: not_started` (R06, K06) |
| Approuvé, mais une pièce essentielle expirée | `new_reservations_blocked` | `DOCUMENT_EXPIRED` + `documentsToRenew` (R02) |
| Approuvé, mais une pièce non valide | `new_reservations_blocked` | `DOCUMENT_NOT_VALID` |
| Approuvé, mais une exigence redevenue à confirmer | `new_reservations_blocked` | `REQUIREMENTS_NOT_QUALIFIED` |

Lire cet accès ne l'accorde pas, ne le prolonge pas et ne consomme pas l'essai. Un blocage ne touche que les **nouvelles** réservations ; les engagements existants relèvent de l'assistance (R02).

**Hors de ce point**, parce que les objets correspondants n'existent pas encore : l'essai de sept périodes de 24 h, les périodes payées et les achats (R06, A33), ainsi que les cas ouverts listés dans `docs/decisions/README.md` (achat avant la première prestation, première prestation interrompue, prolongation et remboursement). Ils seront ajoutés avec les réservations et les paiements, sans changer les règles ci-dessus.

### K24 — Catalogue v2 et dérogations à l'approbation (A29, A38)

Les règles suivent la décision du fondateur du 29 octobre 2026, décrite dans `WAKA_pieces_chauffeur.md`.

- **A29 (envoi du dossier)** : seules les pièces de la personne (identité, permis) sont `requiredForSubmission`. Elles doivent être présentes et valides. Les pièces du véhicule peuvent manquer au moment de l'envoi ; l'agent les juge ou y déroge. Une pièce marquée `to_correct` doit être remplacée avant le nouvel envoi.
- **A38 (approbation)** : le champ facultatif `waivers: [{code, reasonCode}]` n'est admis qu'avec `approve`.
  - Une dérogation porte sur une pièce du véhicule applicable au dossier, avec un motif codé obligatoire.
  - Si elle vise `IDENTITY` ou `DRIVING_LICENCE`, ou une pièce non applicable : 400 `INVALID_REQUEST`.
  - Une pièce du véhicule sans document acceptable et sans dérogation : 409 `INCOMPLETE`.
  - Les dérogations sont enregistrées avec la décision (`admission_waiver`) et renvoyées dans `waivers`, à l'agent comme au candidat.
  - Sans dérogation, l'empreinte de la décision reste celle d'avant : les décisions antérieures sont retrouvées à l'identique.
- **A32 (accès effectif)** : une pièce dérogée par la dernière approbation ne bloque pas l'accès. Elle reste « à fournir » dans la liste des pièces du chauffeur.
- La version du catalogue enregistrée avec chaque décision vaut désormais `v2`.

### K25 — Voyages entre villes : annonces (R11, première partie)

Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503). Réservations et paiements viendront avec le prestataire de paiement.

| Route | Rôle |
|---|---|
| `POST /api/v1/voyages` | Exige `Idempotency-Key`. Corps `{stops:[{place, plannedAt}], seats, prices:{"0-1": prix, ...}}`. 201 à la publication, 200 si la même annonce est renvoyée avec la même clé. |
| `GET /api/v1/voyages` | Voyages publiés à venir, les plus proches d'abord (50 au plus), pour tout client connecté. **L'identité du chauffeur n'est jamais montrée.** |
| `GET /api/v1/driver/voyages` | Les voyages du chauffeur connecté. |
| `POST /api/v1/voyages/{id}/cancellations` | Corps `{reasonCode}`. Annulation par son chauffeur ; la répéter ne change rien. Un autre chauffeur reçoit 404. |

Règles :
- **Arrêts** : 2 à 6, dont le départ, les escales et l'arrivée. Chaque nom de lieu fait 2 à 60 caractères. Les heures sont des instants UTC strictement croissants.
- **Bornes techniques**, modifiables : départ au moins 1 h et au plus 60 jours à l'avance, voyage d'au plus 48 h.
- **Prix** : une valeur par paire d'arrêts (départ avant arrivée), en francs entiers, de 1 à 10 000 F par place. Toute paire manquante ou en trop est refusée (400).
- **Places** : de 1 à 5, et jamais plus que les places passagers du véhicule approuvé. Au-delà : 422 `TOO_MANY_SEATS`.
- **Chauffeur** : son accès doit être `active` ou `provisional` (K23). **Aucun abonnement n'est exigé.** Sinon : 403 `DRIVER_NOT_ALLOWED`.
- **Calculs** (`VoyageRules`, testés) : commission de 10 % arrondie au franc ; versement au chauffeur à l'embarquement ; annulation par le client gratuite à 12 h ou plus du départ, puis 15 % de frais, et 30 % à moins de 3 h, partagés moitié chauffeur (arrondi au franc inférieur), moitié WAKA ; annulation par le chauffeur sans frais. Ces calculs seront branchés sur les réservations.

### K26 — Voyages entre villes : réservation de places (R11, deuxième partie)

Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503). **Aucun paiement réel n'existe encore.**

| Route | Rôle |
|---|---|
| `POST /api/v1/voyages/{id}/reservations` | Exige `Idempotency-Key`. Corps exact `{fromIndex, toIndex, seats}`. **Le prix vient de la liste du chauffeur, jamais du client.** 201 à la création, 200 si la même demande est renvoyée avec la même clé. |
| `GET /api/v1/me/voyage-reservations` | Les réservations de la personne connectée, les plus récentes d'abord. |
| `POST /api/v1/voyage-reservations/{id}/cancellations` | Corps vide. Annulation par le client ; la répéter ne change rien. |

Règles :
- **Places par tronçon** : les places sont comptées tronçon par tronçon. Un passager Yaoundé → Edéa et un autre Edéa → Douala peuvent occuper le même siège. La demande est refusée si un tronçon serait dépassé : 409 `NOT_ENOUGH_SEATS`.
- **Refus** : réserver son propre voyage donne 422 `OWN_VOYAGE` ; réserver après l'heure prévue à l'arrêt de montée donne 422 `DEPARTED`.
- **Attente de paiement** : une réservation commence `awaiting_payment` et garde ses places pendant la fenêtre de paiement de 3 minutes (R10, reprise faute de paramètre propre aux voyages). Passé ce délai, elle s'affiche `expired` et libère ses places. Les états `confirmed` et `boarded` viendront avec le prestataire de paiement.
- **Annulation par le client** :
  - réservation non payée : elle se termine, sans montant ;
  - réservation payée : le règlement R11 (0, 15 ou 30 %, à l'heure prévue de l'arrêt de montée) est calculé et enregistré (`refundXaf`, `driverXaf`, `wakaXaf`, `feePercent`).
- **Annulation du voyage par son chauffeur** : chaque réservation active est annulée ; une réservation payée est remboursée en entier.
- La liste des voyages indique, pour chaque paire d'arrêts, les places encore libres (`availableSeats`).
- **Aucune somme ne bouge** : les montants enregistrés serviront au prestataire de paiement.

### K27 — Voyages entre villes : passagers et embarquement (R11, troisième partie)

Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503).

| Route | Rôle |
|---|---|
| `GET /api/v1/voyages/{id}/passengers` | Pour le chauffeur de ce voyage seulement : réservations actives et embarquées, avec tronçon, places et état. **Ni l'identité du client ni son code ne sont montrés.** Un autre compte reçoit 404. |
| `POST /api/v1/voyages/{id}/boardings` | Corps `{boardingCode}` (6 chiffres), saisi par le chauffeur. Une réservation payée passe à `boarded`, et le règlement R11 est enregistré : chauffeur 90 %, WAKA 10 %. Le même code renvoyé retrouve le même embarquement. |

Règles :
- **Code d'embarquement** : à la réservation, le serveur tire un code de 6 chiffres, unique dans le voyage. Il est conservé sous forme d'empreinte à clé (pour la recherche) et chiffré (pour l'affichage). Seul son client le voit (`boardingCode`), et seulement tant que la réservation peut encore embarquer.
- **Refus** : code inconnu, 422 `WRONG_CODE` (compté) ; réservation non payée, 409 `NOT_PAID` ; après 10 codes erronés sur un voyage, 429 `BOARDING_LOCKED`, et l'assistance doit intervenir. Cette protection empêche de deviner un code.
- **Argent** : le règlement est seulement calculé et enregistré ; le versement réel au chauffeur viendra avec le prestataire de paiement.

### K28 — Décisions déléguées R12 : parrainage et passager absent

**`POST /api/v1/me/referral-linkages`** (complète K22) ajoute trois refus :
- 422 `MUTUAL_REFERRAL` : la personne visée est déjà le filleul de ce compte ;
- 422 `WINDOW_CLOSED` : le compte a déjà participé à une course terminée et payée, aujourd'hui un voyage embarqué, comme client ou comme chauffeur ;
- 429 `TOO_MANY_ATTEMPTS` : 5 codes non reconnus en 24 heures glissantes. Le verrou s'applique avant toute recherche, même pour un code valable. Seuls les échecs sont comptés, sans garder le code essayé.

**`POST /api/v1/voyages/{id}/no-shows`**, corps `{reservationId}`, réservé au chauffeur du voyage :
- une réservation payée passe à `no_show` et le règlement d'une annulation tardive est enregistré : 30 % retenus, moitié chauffeur, moitié WAKA ; 70 % remboursés ;
- refus avant l'heure prévue à l'arrêt de montée : 422 `TOO_EARLY` ; refus pour une réservation non payée : 409 `NOT_PAID` ;
- répéter l'opération rend le même résultat.

**Correction de capacité.** Un passager embarqué garde sa place sur son tronçon : elle n'est pas revendue pendant le trajet.

### K29 — Fonction PRIVACY et assistance aux voyages (R12)

Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503).

| Route | Fonction | Rôle |
|---|---|---|
| `GET /api/v1/admin/privacy-requests` | PRIVACY | Demandes sur les données, les ouvertes d'abord. Le texte du demandeur est déchiffré pour l'agent, et chaque lecture est inscrite au journal (`PRIVACY_REQUEST_READ`). L'agent ne voit jamais ses propres demandes. |
| `POST /api/v1/admin/privacy-requests/{id}/closures` | PRIVACY | Corps `{outcome: completed\|refused, reasonCode}`. La clôture est inscrite au journal ; la répéter ne change rien. **Clôturer ne supprime rien** : la suppression suit la procédure A05. Une demande clôturée permet d'en faire une nouvelle. |
| `GET /api/v1/admin/locked-voyages` | ASSISTANCE | Voyages bloqués après 10 codes d'embarquement faux, sauf ceux que l'agent conduit lui-même. |
| `POST /api/v1/admin/voyages/{id}/boarding-unlocks` | ASSISTANCE | Corps `{reasonCode}`. Remet le compteur à zéro ; l'action est inscrite au journal (`VOYAGE_BOARDING_UNLOCKED`). |

`PRIVACY` est une nouvelle fonction d'habilitation, attribuée comme les autres (K19).

### K30 — Incidents (A23–A25, A39 ; règle R12)

Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503).

| Route | Qui | Rôle |
|---|---|---|
| `POST /api/v1/incidents` | Client ou chauffeur de la réservation | Exige `Idempotency-Key`. Corps `{reservationId, category, description}`. 201 à la création, 200 si l'incident existe déjà : un seul incident par réservation et par déclarant. |
| `GET /api/v1/me/incidents` | Les deux parties | Leurs incidents avec tous les messages. Chaque partie ne voit que des rôles (`client`, `driver`, `assistance`), jamais l'identité de l'autre. |
| `POST /api/v1/incidents/{id}/messages` | Les deux parties | Exige `Idempotency-Key`. Corps `{kind: reply\|contest, text}`. Une contestation n'est possible qu'après une décision (sinon 409 `NOT_DECIDED`) ; elle passe l'incident à `contested` et rouvre l'examen. |
| `GET /api/v1/admin/incidents` | ASSISTANCE | Incidents ouverts et contestés d'abord. L'agent ne voit jamais ceux où il est partie. |
| `POST /api/v1/admin/incidents/{id}/decisions` | ASSISTANCE | Exige `Idempotency-Key`. Corps `{expectedVersion, decision, reasonCode, text?}`. Décisions possibles : `no_action`, `refund_review`, `driver_warning`, `account_review`. Une version différente donne 409 `VERSION_CONFLICT` ; si l'agent est partie, 403 `CONFLICT_OF_INTEREST`. La décision est inscrite au journal. **Elle ne déplace aucun argent** : un remboursement passera par la finance (A40). |

Les textes font de 1 à 1 000 caractères et sont stockés chiffrés. Aucune pièce jointe n'est acceptée pour l'instant.

### K31 — Courses en ville (R13)

Ces routes sont réalisées côté serveur et ne sont pas assemblées par défaut (503). Elles attendent le fournisseur de cartes (distance du devis, temps de route jusqu'au client) et le paiement.

| Route | Qui | Rôle |
|---|---|---|
| `PUT /api/v1/driver/presence` | Chauffeur avec accès effectif | Corps `{online, latitude?, longitude?}`. La position est chiffrée ; son heure est celle du serveur. Hors ligne, la position est effacée. |
| `POST /api/v1/searches` | Client | Exige `Idempotency-Key`. Corps `{quoteId}`. Recherche de 2 minutes dans la validité du devis ; une seule recherche par devis. |
| `GET /api/v1/searches/{id}` | Client | État de la recherche et, si elle a abouti, la course avec le code d'embarquement. Lire la recherche envoie la proposition suivante quand aucune n'est en cours. |
| `POST /api/v1/searches/{id}/cancellations` | Client | Annule une recherche qui n'a pas encore abouti. |
| `GET /api/v1/driver/proposals` | Chauffeur | Propositions en cours : montée, descente, places, prix, part du chauffeur, temps de route jusqu'au client, échéance. |
| `POST /api/v1/proposals/{id}/responses` | Chauffeur | Corps `{action: accept\|refuse}`. L'acceptation (201) crée la seule course active de la voiture, en attente de paiement pendant 3 minutes ; le refus répond 204. |
| `GET /api/v1/rides/{id}` | Ses deux parties | Course vue par le client (avec le code) ou par le chauffeur (sans le code). |
| `POST /api/v1/rides/{id}/driver-actions` | Chauffeur | Corps `{action: arrive\|board\|finish\|client_absent\|cancel, boardingCode?}`. Arrivée et embarquement seulement après paiement ; absence du client après 5 minutes d'attente, remboursée ; annulation par le chauffeur, remboursée. |
| `POST /api/v1/rides/{id}/cancellations` | Client | Avant l'embarquement : remboursement intégral, sans pénalité (R08). |

Une proposition part au chauffeur recevable le plus proche **en temps de route** (au plus 10 minutes), un seul à la fois. Elle expire au plus tôt des trois échéances : 30 secondes, fin de la recherche, fin du devis. Le prix est celui du devis, recalculé par le moteur de prix R03 et contrôlé.

### K37 — Partagé par direction (R15)

- **`PUT /api/v1/driver/presence`** accepte aussi `destinationLatitude` et `destinationLongitude`, c'est-à-dire la direction du chauffeur. Elle exige la position et doit être redonnée à chaque mise à jour : l'omettre retire la direction. La réponse ajoute `destination`, qui vaut `null` sans direction, et `route`, le trajet routier du chauffeur jusqu'à sa direction (polyligne encodé, tracé en vert). Ce trajet est recalculé au plus toutes les 5 minutes, ou quand la direction change.
- **Tournée** (révision du 31 octobre 2026) : la réponse de présence ajoute `plan` et `stops`. `stops` est la liste ordonnée des arrêts des courses en cours (`{kind: pickup|dropoff, reservationId, point}`, 10 au plus ; pas de départ pour un client déjà à bord). `plan` est le trajet routier position → arrêts → direction (polyligne encodé, tracé en jaune), ou `null` sans course en cours. Il est recalculé quand les arrêts changent, sinon au plus toutes les 5 minutes.
- **Propositions** (`GET /api/v1/driver/proposals`) : chacune ajoute `detourSeconds` (le détour que ce client ajoute à la direction du chauffeur, de 0 à 7 200 secondes, affiché et non plafonné ; 0 sans direction) et `route` (le trajet du client repris du devis, tracé en bleu, ou `null`).
- **Avec une direction** : le chauffeur ne reçoit que des demandes partagées, de clients à 10 minutes au plus qui vont dans son sens (R15 révisée). Il peut en accepter plusieurs tant que les places le permettent. Une acceptation qui ne tient plus répond 409 `CAR_BUSY`.
- **`GET /api/v1/driver/rides`** : les courses que le chauffeur porte en ce moment, 10 au plus, les plus anciennes d'abord.
- **`GET /api/v1/proposals/{id}/paths`** : les tracés d'une proposition, réservés au chauffeur qui l'a reçue, pour l'affichage seulement. Chacun est un polyligne encodé au format Google, en forme simplifiée (« overview ») :
  - `normal` : le trajet du chauffeur jusqu'à sa direction, ou `null` sans direction ;
  - `client` : le trajet du client ;
  - `combined` : le trajet du chauffeur avec ce client.

  Le serveur interroge Google au plus une fois par proposition (3 appels au plus, comptés dans le plafond journalier), puis réutilise les tracés. Le trajet du client reprend celui du devis quand il est encore en mémoire, ce qui économise un appel.
- **Navigation** : l'application ouvre Google Maps ou Waze avec le point à rejoindre déjà rempli. C'est gratuit, et WAKA ne fait aucun appel de navigation.

### K36 — Suivre le chauffeur et retrouver sa course (R13)

- **Course vue par le client** (`GET /api/v1/rides/{id}` et les autres réponses de course) : deux champs s'ajoutent, `driverPosition {latitude, longitude}` et `driverPositionAt`. Ils sont remplis seulement pour le client, de l'acceptation jusqu'à l'embarquement (`awaiting_payment`, `confirmed`, `driver_arrived`), et seulement si la position du chauffeur date de moins d'une minute. Sinon ils valent `null`, et toujours pour le chauffeur.
- **Trajet en bleu** : chaque course ajoute aussi `route`, le trajet routier du devis (polyligne encodé, affichage seulement), pour les deux parties. Il ne coûte aucun appel Google : il vaut `null` si le serveur ne l'a plus en mémoire.
- **`GET /api/v1/me/active-ride`** : la course encore en cours du compte, comme client ou comme chauffeur (la plus récente), pour la retrouver après avoir fermé l'application. Répond 404 `RESOURCE_NOT_FOUND` s'il n'y en a pas.

### K35 — Recherche d'adresse pour une course en ville (R13)

Réalisées côté serveur, assemblées seulement quand la clé serveur Google est fournie par l'environnement (sinon 503). Réservées à une session valide. Ce qui est tapé passe dans le corps, jamais dans l'adresse.

| Route | Rôle |
|---|---|
| `POST /api/v1/places/suggestions` | Corps `{query, sessionToken}` : 2 à 100 caractères, et un jeton de session choisi par le téléphone. Répond au plus 5 lieux `{placeId, mainText, secondaryText}`, au Cameroun, les plus proches de Yaoundé d'abord. |
| `POST /api/v1/places/locations` | Corps `{placeId, sessionToken}`. Répond `{placeId, latitude, longitude}` à six décimales, ou 404 si Google ne connaît pas la position. |

Le même jeton regroupe la saisie et le choix final en une seule session facturée ; le téléphone en prend un nouveau après chaque lieu choisi. La clé Google reste sur le serveur. Le serveur fait au plus 300 appels par jour à Google Places et 300 à Google Routes, puis répond 503. La distance du devis (K31) est celle de la route en voiture, sans trafic en direct.

### K32 — Essai et périodes d'abonnement (R06, R12)

- **Essai** : il démarre une seule fois, à la première prestation terminée et payée d'un chauffeur particulier (fin d'une course en ville, ou embarquement d'un voyage), et dure 7 × 24 h.
- **`POST /api/v1/subscription-purchases`** : corps vide, `Idempotency-Key`. L'achat est réservé au particulier approuvé dont l'essai a commencé ; sinon 409 `TRIAL_NOT_STARTED` ou 403 `NOT_PRIVATE_DRIVER`. La période coûte 3 000 F et reste `awaiting_payment` jusqu'à la confirmation du paiement.
- **Période payée** : elle commence au plus tard de trois dates (la confirmation du paiement, la fin de l'essai, la fin de la dernière période payée) et dure 30 × 24 h.
- **`GET /api/v1/driver/subscriptions`** : les périodes du chauffeur.
- **Accès (K23)**, dans l'ordre : `provisional` avant la première prestation, puis `active` avec `basis: trial`, puis `active` avec `basis: paid_period`, sinon `subscription_required`. Dans ce dernier cas, il n'y a plus de nouvelles courses en ville, mais **les voyages restent possibles** (R11).

### K33 — Paiements, crédit WAKA et retraits (R14)

Réalisées côté serveur, non assemblées par défaut (503). Le prestataire Fapshi (ou Notch Pay en remplacement) n'est branché qu'avec ses clés de bac à sable fournies par l'environnement.

| Route | Qui | Rôle |
|---|---|---|
| `POST /api/v1/payments` | Client | `Idempotency-Key`. Corps `{purpose: ride\|voyage\|subscription\|topup, targetId?, method: cm.mtn\|cm.orange\|credit\|earnings, phone?, amountXaf?}`. `targetId` pour une prestation, `amountXaf` pour un dépôt seulement ; le montant d'une prestation est celui du serveur. Aucun frais pour le client. Avec `credit` ou `earnings`, le paiement est immédiat ; avec Mobile Money, il reste `pending` jusqu'au statut du prestataire. 409 `NOT_PAYABLE`, `INSUFFICIENT_CREDIT` ou `CREDIT_LIMIT`. |
| `GET /api/v1/payments/{id}` | Le payeur | État : `pending`, `succeeded`, `failed` ou `review`. `applied` indique si la prestation a été confirmée. Un paiement `pending` est relu chez le prestataire avant la réponse. |
| `GET /api/v1/wallet` | Chacun | Soldes des deux poches (`creditXaf`, `earningsXaf`) et 50 derniers mouvements avec leur poche : dépôt, paiement de prestation, règlement, retrait, retrait annulé. |
| `POST /api/v1/withdrawals` | Chacun | `Idempotency-Key`. Corps `{pocket: credit\|earnings, amountXaf, method, phone}`. Seulement vers le numéro vérifié du compte, sinon 422 `PHONE_NOT_ACCOUNT`. Frais en plus : 3 % sur le crédit, 2 % sur les gains. |
| `GET /api/v1/withdrawals/{id}` | Son titulaire | État : `pending`, `succeeded` ou `failed`. Un retrait échoué est rendu entièrement. |
| `POST /api/v1/payment-notifications` | Le prestataire | Sans session. Fapshi : secret exact dans `x-wh-secret` (Notch Pay : signature HMAC-SHA256 dans `x-notch-signature`), sinon 401. Répond 204 ; la notification déclenche seulement une relecture du statut. |

### K34 — Finance de l'administration (R14)

Les routes sont réservées à la fonction FINANCE, en session d'administration. Elles ne sont pas assemblées par défaut (503). Aucune identité ni aucun numéro n'est affiché.

| Route | Rôle |
|---|---|
| `GET /api/v1/admin/finance/overview` | Totaux du registre (crédits, gains, garde, retraits en route, recettes) et montant attendu chez le prestataire, qui en est la somme exacte ; nombre de cas à régler. |
| `GET /api/v1/admin/finance/cases` | Paiements dont le montant ne correspond pas, et paiements ou retraits sans statut final depuis une heure. |
| `POST /api/v1/admin/payments/{id}/resolutions` | Corps `{outcome: received\|not_received, receivedXaf, reasonCode}`. Après vérification dans le tableau de bord du prestataire, le montant réellement reçu (au plus le montant demandé) va au crédit du payeur. Une seule fois, inscrit au journal ; jamais sur son propre argent. |
| `POST /api/v1/admin/withdrawals/{id}/resolutions` | Corps `{outcome: sent\|not_sent, reasonCode}`. « Envoyé » clôt le retrait ; « pas envoyé » rend tout, frais compris. |
