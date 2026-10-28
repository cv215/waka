# WAKA — états, transitions et reprises

03.07 v1 — 10 octobre 2026. **Réalisé — à relire à la fin du lot autorisé.** Spécification pour le futur serveur ; aucun automate, verrou, débit ou ordonnanceur exécuté.

## S01 — Une commande, plusieurs objets

Une transition comprend état avant, événement, acteur, conditions et effets atomiques. L'écran demande ; le serveur décide après contrôle des droits, de la version et de l'heure. Un refus garde l'état établi et explique la cause. Les informations historiques ne sont pas supprimées pour simuler une reprise.

Les noms des objets correspondent au [modèle M02–M05](WAKA_modele_donnees.md). Paiement, réservation, retenue, trajet, dette et versement sont des objets différents : aucune liste unique de statuts ne les remplace. L'achat d'abonnement, la période payée et l'accès effectif sont également distincts. Répéter la même commande identifiée retrouve son effet ; une même clé avec un autre contenu est refusée.

Transaction courte : droits → verrou et version → conditions et échéances → écriture d'état + audit + événement outbox → commit. Appels cartographiques et prestataires hors transaction. Pour l'acceptation, garde au niveau du véhicule puis trajets et recherche dans un ordre stable documenté ; tous les chemins d'acceptation/libération/confirmation suivent le même ordre pour limiter les interblocages. Le garde vérifie tous les trajets du même véhicule, sans limiter l'exclusivité au seul trajet courant. La transaction réessayée ne relance pas un paiement externe.

## S02 — Échéances et frontières du pilote

Décision de réalisation : les intervalles actifs sont **[début, fin[**. À l'instant exact de fin, l'objet est expiré ; l'heure contrôlée est celle du serveur sous transaction. Cela rend explicite le « avant expiration » des parcours. Paramètres versionnés, jamais horloge du téléphone ou simple retard d'un ordonnanceur.

| Objet | Début de la fenêtre | Fin | À la frontière |
|---|---|---|---|
| Devis | Création serveur | +300 s | Plus d'acceptation utilisant ce devis ; nouveau devis explicite |
| Recherche | Demande acceptée sur devis valide | +120 s, sans prolonger le devis | Plus de nouvelle réponse gagnante |
| Proposition | Envoi identifié | min(envoi+30 s, fin recherche, fin devis) | Réponse expirée ; recherche continue seulement s'il reste du temps |
| Retenue | Acceptation valide et prix figé | +180 s | Plus de conversion en réservation confirmée ; finance tardive traitée séparément |
| Position chauffeur | Mise à jour utilisable | Âge maximal 60 s | Âge ≤60 s admis ; au-delà, pas de proposition ; fraîcheur revérifiée selon l'action |
| Attente rendez-vous | Notification d'arrivée documentée au point convenu | Au moins +300 s avant qualification d'absence | Pas d'amende ou preuve GPS automatique ; contact/assistance et motif |
| Essai/période payée | Déclencheur ou début programmé | +604 800 / +2 592 000 s | Nouveaux engagements selon autre accès valide ; historique et gains conservés |
| Parrainage | Première réservation payée et terminée, tout rôle | +12 mois calendaires, jour rabattu au dernier jour valide | Fin exactement à expiration : pas de nouveau bonus |

La retenue de paiement est indépendante du temps restant du devis accepté. Le devis peut expirer ensuite sans raccourcir ces trois minutes. Une nouvelle demande hors fenêtre ne bénéficie pas d'une date déclarée par l'appareil. Une confirmation financière après la fin ne recrée pas la place ; même si la date fournisseur est antérieure, l'engagement expiré n'est pas ressuscité.

## S03 — Trajet, recherche et proposition

| ID | Objet : avant → après | Événement / acteur | Conditions et effets |
|---|---|---|---|
| T01 | Trajet : brouillon → publié | Publier / chauffeur propriétaire | Admission et pièces valides, catégorie/accès, véhicule et capacité, itinéraire recevable ; pas d'exclusivité incompatible |
| T02 | Trajet : publié → en_cours | Démarrer / chauffeur | Conditions de prise en charge et engagements vérifiés ; historique conservé |
| T03 | Trajet : en_cours → terminé | Terminer / chauffeur, assistance si exception | Toutes prestations affectées terminées ou résolues ; ne termine pas une réservation par simple clic global |
| T04 | Trajet : brouillon/publié/en_cours → annulé | Annuler / propriétaire ou assistance | Brouillon sans engagement simple ; sinon traitement de chaque réservation et sécurité avant mutation ; dettes/refunds séparés |
| T05 | Recherche : créée → en_cours | Lancer / payeur | Devis valide accepté ; recherche et date fixées une fois ; pas de chauffeur fictif |
| T06 | Proposition : envoyée → refusée/expirée | Refus chauffeur / échéance serveur | Un refus ne termine pas automatiquement toute recherche ; poursuivre dans fenêtre restante |
| T07 | Recherche : en_cours → aboutie ; Proposition : envoyée → acceptée | Accepter / chauffeur destinataire | now < fins devis/recherche/proposition ; version trajet, capacité par segment, admission, accès et VIP revérifiés ; création Reservation + Retenue ; une acceptation gagnante ; autres propositions closes |
| T08 | Recherche : en_cours → expirée/annulée | Fin fenêtre / payeur | Sans acceptation gagnante ; aucune retenue ou dette créée ; nouveau lancement nécessite devis encore valide et demande explicite |

## S04 — Réservation et retenue

| ID | Objet : avant → après | Événement / acteur | Conditions et effets |
|---|---|---|---|
| T09 | Reservation : demandée → retenue ; Retenue : créée → active | Effet de T07 / serveur | Prix/version/segments figés ; shared quantité complète partout ; VIP véhicule libre et exclusivité ; +180 s |
| T10 | Reservation : retenue → paiement_en_attente | Instruction identifiée / payeur via serveur | Retenue active ; Paiement et Tentative uniques pour l'instruction ; ne confirme pas la réservation |
| T11 | Reservation : retenue/paiement_en_attente → confirmée ; Retenue : active → convertie | Paiement confirmé / serveur prestataire vérifié | now < échéance, retenue toujours active, référence/montant/XAF/authenticité ; conversion atomique unique ; occupation remplacée, pas additionnée |
| T12 | Reservation : retenue/paiement_en_attente → expirée ; Retenue : active → expirée | Échéance / serveur | Libérer occupation/exclusivité ; garder suivi financier inconnu ; aucune nouvelle course attribuée par futur callback |
| T13 | Reservation : confirmée → chauffeur_arrivé | Arrivée / chauffeur engagé | Au point convenu selon éléments recevables ; début attente/contact conservé ; pas embarquée par GPS |
| T14 | Reservation : confirmée/chauffeur_arrivé → embarquée | Code vérifié / chauffeur engagé, exception assistance | Même réservation/personnes/véhicule, code temporaire protégé non consommé, pièces essentielles valides ; alternative manuelle motivée ; abandon sûr si anomalie |
| T15 | Reservation : embarquée → terminée | Fin à descente / chauffeur engagé, exception assistance | Preuve de fin, événement unique ; trajet peut continuer ; libérer les engagements futurs admissibles ; dette/bonus seulement selon T25 |
| T16 | Reservation : non embarquée → annulée | Annulation / payeur, chauffeur ou assistance autorisée | Arrivée n'est pas embarquement ; libérer retenue/places ; si encaissé, remboursement intégral suivi ; aucun bonus ou pénalité client |
| T17 | Reservation : chauffeur_arrivé → absence | Décision motivée / assistance | Contact et attente ≥5 min établis ; pas GPS seul ni amende automatique ; avant embarquement remboursement intégral si encaissé |
| T18 | Reservation : embarquée → interrompue | Incident / participants puis assistance | Sécuriser, arrêts et service établis ; dette contestée bloquée dans seule portée concernée ; calcul/confirmation financière séparés |
| T19 | Reservation : terminée/interrompue/annulée/absence/expirée → même état métier | Contestation/correction / assistance et finance habilitées | Ajouter Incident/Decision/écritures compensatrices ; ne réembarque pas pour corriger l'argent ; prix initial conservé |
| T20 | Retenue : active → libérée | Annulation valide / serveur | Même verrou trajet ; occupation libérée une seule fois ; retenue convertie ne peut ensuite être libérée comme retenue active |

Les demandes non gagnantes sont refusées/expirées dans Recherche/Proposition ; aucune réservation chauffeur supplémentaire n'est inventée pour chaque candidat. Modifier itinéraire ou véhicule engagé exige version compatible et traitement d'assistance ; aucun déplacement silencieux des passagers. Une nouvelle version d'itinéraire projette tous les engagements et retenues actifs sur ses segments avant contrôle ; référence initiale et prix conservés. Projection impossible ou point modifié sans accord : changement refusé.

## S05 — Paiement, remboursement, dette et versement

| ID | Objet : avant → après | Événement / acteur | Conditions et effets |
|---|---|---|---|
| T21 | Paiement : créé → en_attente | Instruction sortie / serveur | Clé stable, objet/produit/montant connus ; appel via outbox/adaptateur qualifié hors transaction |
| T22 | Paiement : en_attente → résultat_inconnu | Réponse absente / serveur | Aucun échec supposé ; retrouver opération par référence avant nouvelle instruction ; retenue expire normalement |
| T23 | Paiement : en_attente/résultat_inconnu → confirmé | Statut fiable / prestataire via serveur | Authentifier et rapprocher ; encaissement attribué une fois ; T11 si réservation encore retenue, sinon T24 ; achat : T35 selon canal |
| T24 | Paiement : confirmé ; Reservation : expirée/annulée/absence reste inchangée | Succès tardif / serveur et finance | Enregistrer argent encaissé et obligation de restitution ; remboursement suivi ; jamais recréer places/exclusivité ni ouvrir gains de course non réalisée |
| T25 | Dette : en_attente → due/disponible selon contrôles ; Bonus : candidat → acquis/ajusté | Paiement rapproché ET fin de service établie, contrôles / serveur et assistance si interruption | Nominal terminé : parts initiales ; interrompu reconnu : état interrompue conservé, décision humaine et remboursement confirmé, parts retenues R09 établies une fois sans crédit automatique intégral ; éligibilité à la fin effective établie, m/r et montants historiques ; journal équilibré unique ; incident suspend seulement portée contestée |
| T26 | Paiement : en_attente/résultat_inconnu → échoué | Échec terminal prouvé / prestataire vérifié | Aucun encaissement établi ; pas par timeout ; reprise distincte seulement après résolution ; confirmé ne redevient pas échoué sur ancien événement |
| T27 | Remboursement : demandé → contrôlé | Validation / assistance+finance selon droit | Prix/motif/décision, montant cumulé confirmé + non résolu bornés ; réservation et paiement reliés ; avant embarquement intégral, après selon R09 |
| T28 | Remboursement : contrôlé → envoyé → confirmé | Instruction/confirmation / finance via adaptateur | Référence stable et montant ; inconnu suivi comme sous-état de tentative ; compensation depuis Rtotal confirmé, événement unique |
| T29 | Remboursement : envoyé → échoué OU reste envoyé/inconnu | Résultat / prestataire vérifié | Échec terminal seulement si prouvé ; inconnu réserve montant et interdit renvoi ; aucun état remboursé par écran |
| T30 | Dette/Bonus acquis → ajusté ou bloqué dans portée | Remboursement/contestation / finance+assistance | Écriture compensatrice, parts initiales et cumul, pas effacement ; somme disponible ne masque pas une reprise après retrait ; litige humain |
| T31 | Versement : dû → vérification_en_cours → envoyé → confirmé | Versement / finance habilitée via serveur | Dette affectée une seule fois ; chauffeur par réservation rapprochée ; retrait parrain ≥1 000 F disponibles ; frais coût WAKA |
| T32 | Versement : envoyé → échoué OU résultat_inconnu | Résultat / adaptateur qualifié | Inconnu maintient affectation et bloque renvoi ; échec prouvé libère/reprogramme selon rapprochement ; pas double paiement |

Un événement invalide ou au montant différent est rejeté/quarantainé avec motif, **sans transformer le paiement attendu en échec**. Une notification ancienne « en attente » ne rétrograde pas « confirmé ». Une inversion ultérieure réelle (annulation prestataire, litige ou chargeback) crée une nouvelle décision/écriture tracée selon contrat ; elle ne supprime pas l'encaissement historique. Les effets internes et la livraison externe sont séparés.

## S06 — Dossier, pièces, achat et accès effectif

| ID | Objet : avant → après | Événement / acteur | Conditions et effets |
|---|---|---|---|
| T33 | Dossier : brouillon → soumis → en_verification → correction/refusé/approuvé | Soumettre candidat, examiner agent | Huit champs/six vues/pièces ; agent examine version précise et motive ; dépôt/paiement ne donnent pas approbation ; nouvelle version après correction |
| T34 | Justificatif : déposé → en_verification → accepté/refusé ; Fichier : reçu → contrôlé/rejeté | Vérification / agent et contrôles serveur | Fichier privé ; validité temporelle calculée séparément ; changement fichier ne conserve pas l'ancienne acceptation automatiquement |
| T35 | AchatAbonnement : créé/en_attente/inconnu → confirmé → période programmée ou active | Achat confirmé / adaptateur serveur du canal Android qualifié | 3 000 XAF, achat unique, 30×24 h ; programmation après fin d'accès actif et des périodes déjà programmées ; pas de bonus/auto-renouvellement ; coordination avant première prestation encore à décider |
| T36 | Essai : non_attribué → actif → terminé | Première réservation chauffeur payée terminée / serveur, puis échéance | Personne vérifiée unique ; accès provisoire avant ; 7×24 h ; aucune réinitialisation rôle/téléphone/véhicule |
| T37 | Periode payée : programmée → active → terminée | Date serveur | Intervalle daté ; activation ne redébite pas ; nouveau paiement volontaire distinct ; passage taxi vérifié applique remboursement inutilisé |
| T38 | Periode payée : programmée → annulation_en_examen → annulée_remboursement_en_suivi → annulée_remboursée | Demande reçue avant début / titulaire puis finance | Timestamp serveur de demande conservé ; activation suspendue dès réception recevable pendant qualification, sans attendre revue ou prestataire ; décision maintient annulation et restitution intégrale, résultat inconnu suivi sans activation ; dernier état seulement après confirmation externe ; accès antérieur inchangé |
| T39 | AjustementAcces : demandé → vérifié → appliqué | Panne/fermeture/passage taxi / assistance+finance | Panne WAKA complète ≥24 h → durée vérifiée ; sinon conditions R06 ; pas de droits ajoutés par GPS/absence de clients |
| T40 | Dossier approuvé → suspendu ; retour → examen/approbation valide | Décision / agent compétent et assistance | Motif et contestation ; nouveaux engagements interdits ; traiter en sécurité les engagements existants ; aucune confiscation automatique |

**Accès effectif calculé**, pas un statut financier universel : non admis → refus ; taxi admis/pièces valides → sans abonnement ; particulier admis → provisoire avant première prestation, ou essai/payé actif, sinon expiré. Suspension et pièce essentielle expirée priment pour nouveaux engagements. À l'expiration d'abonnement seul, engagements existants et gains ne disparaissent pas. Avant nouvelle prise en charge, une pièce essentielle expirée exige vérification/assistance, même pour engagement existant ; aucun arrêt automatique dangereux.

La fenêtre de parrainage est unique à la première réservation payée terminée du filleul dans tout rôle ; le déclenchement chauffeur d'essai est différent. Les répétitions/out-of-order retrouvent les mêmes événements déclenchants. La date de fin de prestation établie sert à l'éligibilité des parrains ; le traitement différé ne redémarre pas une fenêtre. Les cas non nominalement payés avant embarquement sont résolus humainement avant attribution de bonus.

## S07 — Objets transversaux et refus indispensables

| Famille | États ou règle d'évolution |
|---|---|
| Session/PartageSuivi | Actif, expiré, révoqué ; lecture vérifie l'échéance même si tâche de nettoyage n'a pas tourné ; révocation effective côté serveur |
| Incident | Ouvert, en_examen, décision, contesté, résolu ; auteur/droits/motif conservés ; contestation rouvre l'examen, pas la réservation physique |
| Outbox/Tentative externe | À envoyer, en_traitement, envoyé/confirmé, à_vérifier, échec prouvé ; retries identifiés, pas promesse d'exactement une livraison réseau |
| RequeteIdempotente | Reçue, en_traitement, terminée ; même corps retrouve référence ; autre corps conflit ; effet et clé inscrits avec transaction |
| VersionRegle/Itineraire/Decision/Journal | Versions établies immutables ; correction ajoute version ou compensation ; aucun DELETE financier |
| Avis | Enregistré, modéré/masqué selon 16.03 ; pas d'avis sans prestation réelle ; politique à préciser avant fonction |
| Position/validités | Actualité calculée selon temps/provenance ; une ancienne position ne reste pas fraîche parce que son état n'a pas changé |

Transitions interdites : demande → embarquée sans acceptation/paiement ; paiement inconnu → nouvel ordre de débit improvisé ; expirée → confirmée par succès tardif ; deux retenues pour la dernière place ; VIP avec partagé actif ; taxi auto-attribué ; fichier remplacé → automatiquement approuvé ; terminé trajet → toutes réservations terminées ; gain dû → versement confirmé sans preuve ; retrait antérieur → remboursement client effacé ; changement rôle → nouvel essai/window ; correction financière → effacement historique.

## S08 — Cas de contrôle avant implémentation

| Cas | Entrée documentaire | Issue attendue |
|---|---|---|
| Q01 — Deux acceptations | Même recherche, capacité d'une place | Une retenue gagnante ; seconde conflit, pas de paiement supplémentaire |
| Q02 — Quantité 3 | Un segment n'a que 2 places | Acceptation refusée entière ; aucune réservation de 2 sous couvert de 3 |
| Q03 — VIP concurrent | Une retenue partagée active | VIP refusé ; aucun passager évincé |
| Q04 — Exact devis/recherche/proposition | now = échéance la plus courte | Réponse expirée, aucune retenue |
| Q05 — Devis après acceptation | Devis expire pendant retenue valide | Paiement peut confirmer pendant les trois minutes distinctes |
| Q06 — Exact retenue | Confirmation traitée à son échéance | Réservation expirée, restitution suivie ; aucune place reprise |
| Q07 — Paiement inconnu | Réseau coupe après instruction | Retrouver même tentative ; expiration indépendante, aucun second débit |
| Q08 — Notification doublée/désordonnée | confirmé puis confirmé/pending | Un encaissement/effet ; aucune rétrogradation |
| Q09 — Annulation puis succès | Réservation annulée avant embarquement | Argent suivi/remboursé intégral, aucun bonus ni pénalité |
| Q10 — Fin d'un passager | Autres passagers continuent | Sa réservation terminée ; trajet encore en_cours |
| Q11 — Remboursement inconnu | Ordre envoyé sans réponse | Montant réservé, pas deuxième ordre ; reprendre par référence |
| Q12 — Remboursements successifs | VIP 7 500, cumul confirmé 2 500 | Recalcul T=5 000 depuis initial ; parts 4 500/67/33/400 ; pas arrondis incrémentaux |
| Q13 — Deux achats anticipés | Période et une suivante programmées | Chaque achat attribue une période unique en file, pas chevauchement ni jours gratuits |
| Q14 — Essai répété | Même fin/payé reçu deux fois | Un essai de 604 800 s, pas prolongation |
| Q15 — Exact fenêtre parrain | Fin prestation = expiration calendrier | Bonus nouveau nul ; acquis antérieurs conservés |
| Q16 — Pièce vs abonnement | Pièce essentielle expirée, réservation existante | Vérification avant prise en charge ; assistance sûre ; paiement ne légalise pas l'activité |

Compléments de reprise : une demande d'annulation de période programmée reçue avant début suspend son activation pendant qualification et paiement externe ; un résultat inconnu ne réactive pas la période. Une interruption avec service partiel reconnu conserve son état physique, mais ouvre uniquement les parts retenues après rapprochement, décision et remboursement confirmé. Les données initiales restent disponibles même si aucun gain intégral n'a d'abord été crédité.

Dans T25 et ce cas partiel, la confirmation concerne les remboursements positifs effectivement dus. Une décision motivée établissant 0 F à restituer selon R09 permet le règlement sans ordre ni confirmation externe fictive de 0 F. Pas de gain intégral attribué par défaut ; appliquer toujours les parts conservées calculées. À chaque reprise de commande, droits actuels et projection de réponse sont revérifiés/refiltrés, même si une ancienne clé existe.

Le déclenchement du premier essai et de la fenêtre de parrainage sur une **première prestation interrompue** doit être explicitement qualifié avant 12.02/15.02 : le dossier nomme une réservation payée et terminée mais distingue l'état interrompue. Aucun essai ou fenêtre n'est automatiquement supprimé ni accordé par ce seul statut. Les parts R09 des dossiers dont l'éligibilité est déjà établie restent réglables ; l'assistance tranche les cas dont ce premier déclencheur conditionne l'éligibilité avant paiement des bonus concernés. Ce point ouvert ne change pas la répartition proportionnelle adoptée.

Ces scénarios constituent l'oracle des futurs tests de domaine, de concurrence et d'intégration. Ils sont relus ici ; ils ne prouvent pas un comportement logiciel déjà construit.

## S09 — Sources, coût et décisions ouvertes

Sources : [règles R01–R10](WAKA_regles_adoptees.md), [partagé S03/P01–P15](WAKA_parcours_partage.md), [VIP](WAKA_parcours_vip.md), [chauffeur C05–C09](WAKA_parcours_chauffeur.md), dossier §7.2–7.3, [objets M02–M06](WAKA_modele_donnees.md). Les frontières demi-ouvertes et l'ordre des périodes programmées explicitent une réalisation sûre sans modifier prix ou durée vendue.

Ouverts avant leurs fonctions : achat avant première prestation/essai, combinaison prolongation et remboursement d'abonnement, garanties exactes prestataire et Android, titres/documents/assurance, conservation qualifiée. Les refus ne confisquent ni gains ni droits. **Coût : 0 F.** Dans le dossier documentaire : `code outputs/WAKA_etats_et_transitions.md`, aperçu Ctrl+Maj+V. Prochain point du lot : contrats API 03.08.
