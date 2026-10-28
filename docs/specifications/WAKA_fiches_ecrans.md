# WAKA — fiches des écrans utiles

03.05 v1 — 10 octobre 2026. **Fiches rédigées — à relire ensemble à la fin du lot autorisé.** Le fondateur a demandé de réaliser 03.05 à 03.10 puis 04.01 inclus. Cet accord permet leur réalisation successive ; il ne valide pas les nouveaux résultats par anticipation.

Ces fiches décrivent les informations, actions, erreurs et accès à construire. Elles ne sont ni des maquettes, ni des écrans exécutables, ni des protections déjà installées. Aucun compte, chauffeur, véhicule ou paiement réel n'est utilisé. Les prix et exemples restent ceux des parcours adoptés.

## Lire et retrouver une fiche

Une fiche correspond à une tâche utile. Elle peut devenir une page, une partie de page ou une fenêtre de confirmation selon les futurs choix d'interface. Les identifiants **E01 à E29** restent stables ; leur ordre n'impose ni menu ni nombre de pages physiques. « Serveur et données » indique les informations nécessaires à cette tâche, sans fixer ici le schéma de données, les états complets ou les API.

| Référence | Document et passages utilisés |
|---|---|
| R | [Règles adoptées](WAKA_regles_adoptees.md), R01 à R10 |
| P | [Parcours partagé](WAKA_parcours_partage.md), P01 à P15, S03 à S10 et exceptions de S07 |
| V | [Parcours VIP](WAKA_parcours_vip.md), V01 à V12, T03 à T08 et K01 à K10 |
| A | [Parcours chauffeur](WAKA_parcours_chauffeur.md), A01 à A10, C02 à C10 |
| D | Dossier de référence (reference detaillee conservee hors du depot), §3, §4, §6 et §7 |
| L | Catalogue de réalisation (reference detaillee conservee hors du depot), objectifs 05, 06, 08, 10 et 12 à 17 |

Les identifiants d'exception du partagé s'écrivent ici **P/E01**, **P/E02**, etc., pour les distinguer des identifiants d'écran.

## Principes communs de présentation

Les informations d'une réservation viennent de l'état serveur autorisé. Une heure de dernière mise à jour et une incertitude explicite évitent de présenter une donnée ancienne comme actuelle. Après coupure, retrouver l'engagement et l'opération existants avant de répéter la même demande identifiée. Une répétition ne crée aucun deuxième débit, gain, essai, approbation ou engagement.

Le prix total est visible avant engagement. La retenue, le paiement confirmé, l'embarquement, la fin passager et le versement externe restent distincts. Les délais affichés viennent du serveur : devis cinq minutes, recherche deux minutes et réponse chauffeur trente secondes ; une acceptation valide ouvre ensuite trois minutes distinctes pour payer. Une confirmation tardive ne restaure pas une retenue libérée.

Un compte personnel peut utiliser son rôle client et son rôle chauffeur approuvé. Le changement de vue ne donne pas un droit nouveau. Le chauffeur ne manipule pas son téléphone en conduisant. Les actions de rendez-vous et de service doivent être utilisables à l'arrêt ; si le téléphone est indisponible, une exception relève de l'assistance.

Les permissions de localisation, de caméra ou de fichiers servent uniquement à l'action concernée. Leur refus doit être expliqué, avec saisie manuelle ou choix de fichier lorsque pertinent. Un choix marketing refusé ne supprime pas le service. Textes simples, contraste et informations autres que la couleur ou le son sont requis. Les paramètres précis de sécurité, d'OTP, de fichiers et de conservation seront définis et vérifiés aux points prévus, sans valeurs inventées ici.

## Accès et compte personnel

### E01 — Saisir le téléphone

**Rôle et source :** personne qui ouvre une session ; L05.01, L05.08 et A02.

**Informations :** numéro saisi, format attendu selon le canal retenu, explication de la vérification et accès à l'information de confidentialité.

**Actions :** corriger le numéro puis demander sa vérification ; ouvrir E02 uniquement avec une demande identifiée.

**Erreurs et reprise :** format incorrect, envoi indisponible ou réponse perdue ; aucune création de compte présentée comme réussie. Retrouver la demande avant de recommencer.

**Droits et permissions :** aucun accès aux données d'un autre compte ; aucune permission de contacts nécessaire à la saisie.

**Serveur et données :** validation du numéro, demande de vérification, résultat du canal et limites d'envoi. Aucun code temporaire ni numéro complet inutile dans les journaux.

### E02 — Vérifier le code temporaire de connexion

**Rôle et source :** personne concernée par E01 ; L05.02, L05.03 et L05.08. Ce code de connexion est distinct du code d'embarquement E09/E17.

**Informations :** destination masquée, état de la demande, expiration et possibilité de renvoi selon les limites serveur ; aucune durée ou quantité d'essais nouvelle fixée ici.

**Actions :** saisir le code ; demander un renvoi autorisé ; corriger le numéro en revenant au processus de vérification.

**Erreurs et reprise :** code incorrect, expiré ou réutilisé, essais/envois limités, réseau perdu. Une notification SMS seule n'ouvre pas une session.

**Droits et permissions :** code lié à sa demande ; aucune lecture générale des SMS exigée par cette fiche. Pas de code dans les captures de préparation.

**Serveur et données :** contrôle de validité et d'usage unique, demande identifiée, résultat de vérification et session future. L'intégration SMS reste dépendante de la qualification du canal.

### E03 — Profil, rôle et session

**Rôle et source :** titulaire authentifié ; R01, A01/A02, L05.03 à L05.06.

**Informations :** profil personnel utile, rôle client, candidature ou rôle chauffeur et état d'accès réel ; accès à la confidentialité et à la déconnexion.

**Actions :** renseigner les éléments autorisés du profil, ouvrir les tâches client ou chauffeur disponibles, accéder à sa candidature, fermer la session.

**Erreurs et reprise :** session expirée ou révoquée, accès chauffeur non approuvé, opération perdue. Retrouver l'état après reconnexion ; ne pas transformer un choix de rôle en approbation.

**Droits et permissions :** ses propres données ; aucun bouton permettant de s'attribuer le rôle administrateur ou de lire un autre compte.

**Serveur et données :** utilisateur, profil, session, rôle et approbation/accessibilité du dossier. Un téléphone reconnecté ne reçoit aucun jeton ou droit d'un autre compte.

### E04 — Confidentialité et demandes relatives au compte

**Rôle et source :** titulaire ; D§3.4, A/C10 et L05.07.

**Informations :** finalités utiles, choix marketing, droits et canal de demande, conséquences connues d'une suppression et données soumises à obligations de conservation encore à qualifier.

**Actions :** consulter l'information, modifier le choix marketing, demander accès ou suppression et suivre sa demande privée.

**Erreurs et reprise :** session perdue, demande déjà reçue, traitement en cours ; ne pas annoncer une suppression totale réalisée avant son résultat et les contrôles applicables.

**Droits et permissions :** le compte concerné et les agents habilités ; refus marketing sans perte du service. Aucune CNI passager exigée par défaut dans cet écran.

**Serveur et données :** choix daté, demande identifiée, périmètre et résultat du traitement ; obligations, conservation et suppression à qualifier à 03.09 avant implémentation.

## Réservation client : partagé et VIP

### E05 — Choisir départ, destination, mode et quantité

**Rôle et source :** client ; P01/P02, V01, R01 et P/E01.

**Informations :** points corrigibles, axes/créneaux réellement ouverts, partagé ou VIP, quantité annoncée, limite de cinq passagers hors chauffeur et capacité autorisée à vérifier. En VIP : véhicule entier, sans promesse de confort.

**Actions :** choisir ou corriger les points, sélectionner le mode et la quantité, demander un devis E06.

**Erreurs et reprise :** localisation refusée ou imprécise, route indisponible, quantité invalide, périmètre non servi. Aucune voiture ou distance inventée.

**Droits et permissions :** sa demande ; localisation facultative pour la saisie avec correction manuelle possible. Pas de carte nominative de tous les passagers.

**Serveur et données :** demande, points explicitement choisis, mode, quantité, territoire, calcul routier et grille. La demande ne réserve encore ni places ni véhicule.

### E06 — Lire et accepter le devis

**Rôle et source :** client ; P03/P04, V02/V03, R03/R04/R10.

**Informations :** distance de référence, prix d'une place et total partagé selon quantité, ou total du véhicule VIP indépendant des occupants ; quantité, mode, conditions d'annulation, validité et version utiles. À p = 600 F : une place 600 F, trois places 1 800 F, VIP 7 500 F.

**Actions :** accepter le devis et lancer la recherche ; revenir corriger la demande ; accepter explicitement un nouveau devis si nécessaire.

**Erreurs et reprise :** devis expiré, distance hors barème, calcul indisponible, nouvelle destination ou nouveau prix. Aucun changement silencieux ni arrondi intermédiaire sur le téléphone.

**Droits et permissions :** devis du payeur ; aucune réservation d'autrui visible.

**Serveur et données :** devis, distance, p, quantité, coefficients, montants exacts puis arrondis et échéance. Le serveur calcule ; l'écran n'est pas l'autorité tarifaire.

### E07 — Suivre la recherche

**Rôle et source :** client ; P04 à P07, V03 à V05, P/E02 à P/E05 et V/K01 à K08.

**Informations :** recherche en cours, limites serveur, état fiable de la réponse et indisponibilité explicable. Aucun chauffeur n'est annoncé retenu avant l'acceptation validée.

**Actions :** suivre la recherche ; après son échec ou expiration, demander explicitement une nouvelle recherche avec devis recevable ; ouvrir la retenue existante E08 lorsqu'elle est établie.

**Erreurs et reprise :** absence de candidat, refus, expiration, capacité ou exclusivité devenue indisponible, réponse perdue. Une boucle de propositions ne remet pas les compteurs à zéro.

**Droits et permissions :** sa demande ; informations chauffeur limitées au besoin réel de la mise en relation.

**Serveur et données :** demande identifiée, fenêtres de recherche/réponse, candidats admissibles, réponses et retenue éventuelle. Après coupure, retrouver cette retenue avant d'engager un second chauffeur.

### E08 — Retenue, paiement et résultat financier

**Rôle et source :** payeur ; P07 à P09, V05 à V07, R07/R10, P/E06 à P/E09 et V/J02 à J05.

**Informations :** réservation identifiée, prix figé, chauffeur/véhicule utiles, trois minutes depuis acceptation, places ou exclusivité retenues, état de paiement et heure. « Vérification en cours » est distinct de succès ou échec.

**Actions :** suivre l'instruction Mobile Money de cette réservation ; consulter le résultat et l'aide ; après réponse perdue, demander l'état de l'opération existante. Une nouvelle tentative éventuelle exige un échec établi et le respect de la retenue.

**Erreurs et reprise :** refus, résultat inconnu, retenue expirée, confirmation tardive ou incohérente. Aucun bouton invitant à payer une deuxième fois lorsque le résultat est inconnu ; succès tardif traité sans recréer la réservation.

**Droits et permissions :** seul le payeur agit sur le paiement ; le chauffeur reçoit uniquement la confirmation et les données utiles à son engagement. Aucun secret opérateur affiché.

**Serveur et données :** retenue et échéance, demande de paiement, opérateur, référence, XAF, montant, statut et authenticité ; confirmation unique. Contrat et canal réels restent à obtenir.

### E09 — Rendez-vous, reconnaissance et suivi client

**Rôle et source :** participants autorisés ; P10 à P12, V08/V09, P/E11 à P/E14, V/J06/J07.

**Informations :** point convenu, chauffeur, plaque et données sélectionnées du véhicule, état de réservation, arrivée notifiée, cinq minutes de contact/attente, code temporaire d'embarquement et dernière position utile datée. VIP : exclusivité maintenue même avec sièges libres.

**Actions :** reconnaître le véhicule, présenter le code au bon chauffeur, contacter par le moyen qualifié, signaler discordance ou difficulté dans E12. Nouvelle destination : devis explicitement accepté, sinon destination existante ou assistance.

**Erreurs et reprise :** absence, mauvais véhicule, code indisponible, réseau perdu ou position ancienne. Pas de validation par GPS seul ; alternative manuelle tracée via assistance. Après départ, pas de remboursement intégral automatique annoncé.

**Droits et permissions :** participants de cette réservation et assistance habilitée ; position précise limitée au service. Pas de documents d'identité ou d'assurance publiés.

**Serveur et données :** réservation, rendez-vous, arrivée, code distinct de connexion, embarquement, événements et coordonnées utiles autorisées ; prix accepté conservé.

### E10 — Fin passager et historique de réservation

**Rôle et source :** client ; P13/P14, V10/V11, P/S10, R10 et L16.02.

**Informations :** destination, fin déclarée puis état contrôlé, prix accepté, historique et décisions/retours financiers de sa réservation ; accès à E11 et E12.

**Actions :** consulter les détails ; signaler une fin contestée ; retrouver une réservation après fermeture de l'application.

**Erreurs et reprise :** fin non encore établie, réponse perdue, contestation ou statut financier incertain. Ne pas confondre la fin passager avec celle du trajet chauffeur ou avec un versement confirmé.

**Droits et permissions :** historique personnel ; aucun accès aux autres clients du trajet. L'accès reste conservé selon les droits adoptés même si le compte possède un abonnement chauffeur expiré.

**Serveur et données :** réservation et versions historiques, événements de fin, décisions, écritures et opérations financières autorisées ; répétition d'une fin sans deuxième gain.

### E11 — Annulation et suivi du remboursement

**Rôle et source :** client et chauffeur pour leur engagement ; P/E10/P/E15, P/S08/S09, V/T07/T08, R08/R09.

**Informations :** réservation, état d'embarquement, motif, prix initial, décision, montant demandé et état externe du remboursement. Avant embarquement : prix client intégral, sans pénalité ni bonus acquis ; frais perdus à charge WAKA.

**Actions :** demander l'annulation avant embarquement et confirmer cette intention ; suivre le traitement ; après interruption, consulter la décision d'assistance et la contester via E12.

**Erreurs et reprise :** réponse d'annulation perdue, encaissement inconnu, remboursement envoyé mais non confirmé, service déjà commencé. Relire avant répétition ; aucune promesse de remboursement intégral automatique après départ.

**Droits et permissions :** parties de la réservation ; chaque personne voit ses montants utiles. Le client ne fixe pas seul une proportion de service contestée.

**Serveur et données :** annulation et libération identifiées, paiement, décision humaine, montants initiaux, cumul confirmé et reprises R09 ; écriture interne distincte d'argent remboursé.

### E12 — Aide, incident et décision motivée

**Rôle et source :** client/chauffeur concernés ; P/S07, V/T06, A/C08 à C10 et L16.04/L16.05.

**Informations :** réservation ou opération concernée, canal/horaires d'assistance réellement tenus, état du dossier et réponses utiles. Danger : contacts locaux qualifiés, sans promesse de secours permanent ou immédiat.

**Actions :** décrire le problème, ouvrir ou retrouver la même demande, répondre à l'agent, consulter décision et motif, demander une contestation. Pas d'outil vidéo d'urgence ajouté.

**Erreurs et reprise :** réseau perdu, assistance hors créneau, téléphone indisponible, incident déjà ouvert ; conserver le dernier état connu et ne pas inventer un interlocuteur ou un délai.

**Droits et permissions :** parties et agents habilités, uniquement les informations utiles au dossier ; aucune pièce privée exigée par défaut dans un message libre.

**Serveur et données :** incident identifié, déclarations, événements disponibles, responsable, décision et journal. La sécurité de l'arrêt et la prestation reconnue sont traitées humainement ; seules sommes contestées sont contrôlées.

## Gains et parrainage

### E13 — Parrain facultatif, bonus et retrait

**Rôle et source :** titulaire dans l'un ou l'autre rôle ; R05, P14/P15, V11/V12 et L15.01 à L15.06.

**Informations :** rattachement facultatif sur un niveau, fenêtre de douze mois, gains en attente/disponibles/repris/retirés et historique. Seuil de retrait : 1 000 F disponibles, sans condition de courses personnelles ni commission WAKA de 1 % au pilote ; frais prestataire à charge WAKA.

**Actions :** renseigner un code facultatif selon le parcours d'inscription, consulter ses bonus, demander un retrait admissible et suivre son résultat ; correction exceptionnelle par E12.

**Erreurs et reprise :** code invalide, auto-parrainage, changement après gains, solde insuffisant, retrait inconnu ou simultané. Pas de dépense deux fois du même solde ; reprendre l'opération existante.

**Droits et permissions :** ses gains ; aucune liste publique d'identités filleules ou de trajets privés. Le chauffeur conserve un versement distinct E14.

**Serveur et données :** rattachement unique, éligibilité à la fin payée, montants 10 n/5 n en partagé et 100/50 VIP, écritures, solde disponible et versement. Aucun bonus de simple inscription, achat d'abonnement ou annulation intégrale.

### E14 — Gains chauffeur et versements par réservation

**Rôle et source :** chauffeur concerné ; P14/P15, V11/V12, R07 et L14.03/L14.07.

**Informations :** prix accepté, commission et net avant abonnement de chaque réservation, dû/en contrôle/envoyé/confirmé/échec et reprises éventuelles. Le total encaissé pour le service n'est pas son revenu net.

**Actions :** consulter le détail d'une réservation et de son versement ; demander de l'aide sur une anomalie. Pas de retrait manuel soumis au seuil parrain pour ces gains chauffeur.

**Erreurs et reprise :** prestation non terminée ou contestée, versement inconnu, montant incohérent. Aucun fonds annoncé disponible avant les contrôles, aucun second versement demandé par simple répétition.

**Droits et permissions :** ses propres montants ; pièces des autres bénéficiaires inaccessibles. Expiration d'accès chauffeur n'efface ni gains ni historique.

**Serveur et données :** réservation terminée payée et rapprochée, parts et journal, versement externe selon contrat ; fin du trajet chauffeur non requise pour régler cette réservation.

## Travail du chauffeur

### E15 — Trajet et disponibilité chauffeur

**Rôle et source :** chauffeur admis ; A10, P05/P12/P13, V/T03, D§7.2 et L08.05/L08.06/L13.05.

**Informations :** véhicule approuvé, départ, destination finale chauffeur, créneau, arrêts utiles, capacité vérifiée, trajet et réservations propres, fraîcheur de position et accès réel. Un trajet chauffeur ne se termine pas à chaque descente client.

**Actions :** préparer/publier le trajet lorsque l'accès le permet, retirer sa disponibilité selon les engagements, terminer le trajet seulement après traitement des réservations restantes.

**Erreurs et reprise :** dossier/accessibilité invalide, GPS ancien, réseau perdu, engagement actif ou modification d'itinéraire incompatible. Ne pas supprimer une réservation pour modifier ou terminer le trajet.

**Droits et permissions :** son trajet ; localisation chauffeur selon permissions et conditions à définir. Pas de données privées de toute la flotte affichées.

**Serveur et données :** trajet, véhicule/capacité, dernière position, engagements, accès et exclusivité ; vérification des réservations avant modification ou fin.

### E16 — Proposition reçue et réponse chauffeur

**Rôle et source :** chauffeur proposé ; P06/P07, V04/V05 et V/K02 à K08.

**Informations :** mode, montée/descente, quantité, prix et net associé avant abonnement, délai de réponse et contraintes ; partagé : compatibilité/détour ; VIP : engagement entier exclusif.

**Actions :** accepter ou refuser à l'arrêt ; consulter la décision serveur et la réservation effectivement retenue E17.

**Erreurs et reprise :** trente secondes écoulées, devis/recherche expirés, capacité ou exclusivité prise entre proposition et réponse, réponse perdue. Une acceptation locale ne prouve pas l'engagement ; aucun compteur prolongé.

**Droits et permissions :** chauffeur ciblé, pour cette proposition ; données client réduites au besoin de prise en charge.

**Serveur et données :** proposition identifiée, réponse, échéances et vérification de capacité par segment ou de véhicule exclusif ; une seule acceptation gagne l'engagement concurrent.

### E17 — Réservations du trajet, montée et descente

**Rôle et source :** chauffeur engagé ; P07 à P13, V05 à V10, P/S10 et V/J05/J08.

**Informations :** ses réservations, état de paiement, quantités, arrêts et prix figés ; arrivée, cinq minutes d'attente, code à vérifier, embarquement et fin de chaque passager. VIP entier verrouillé même avec sièges libres.

**Actions :** à l'arrêt, notifier l'arrivée, vérifier le bon code, déclarer l'embarquement/la fin de la prestation et ouvrir l'assistance ; consulter E14. La fin du trajet reste E15.

**Erreurs et reprise :** paiement non confirmé, code erroné/réutilisé, groupe discordant, pièce expirée avant prise en charge, coupure ou fin contestée. Relire l'état ; alternative manuelle tracée, aucun arrêt automatique dangereux.

**Droits et permissions :** chauffeur de ce trajet et réservations utiles ; ne pas exposer une réservation à un autre chauffeur ni aux autres passagers.

**Serveur et données :** événements autorisés de montée/descente et incidents, capacité/libération, verrou VIP, paiement et fin unique ; libération physique distincte des versements et remboursements.

## Candidature et accès chauffeur

### E18 — Candidature taxi ou particulier

**Rôle et source :** candidat ; A01/A02/A06, A/C01/C02, R02.

**Informations :** catégorie demandée, données personnelles nécessaires, familles de pièces et état de candidature. Taxi : preuves professionnelles ; particulier : exigences également recevables, sans droit d'exercer acquis par abonnement.

**Actions :** renseigner sa candidature, ouvrir véhicule/pièces/photos puis demander l'examen quand le dossier permet cet envoi.

**Erreurs et reprise :** informations manquantes, exigences non qualifiées, candidature existante ou réseau perdu. Aucun accès commercial au simple choix taxi/particulier.

**Droits et permissions :** son dossier uniquement ; collecte réelle après protections et qualification nécessaires.

**Serveur et données :** compte, catégorie demandée, candidature identifiée et éléments manquants ; candidature distincte du rôle chauffeur approuvé.

### E19 — Véhicule et capacité à vérifier

**Rôle et source :** candidat ou chauffeur demandant un changement ; A03, A/C03/C08 et R02.

**Informations :** huit champs : marque, modèle, année, couleur, immatriculation, capacité totale autorisée, places passagers WAKA et titulaire ou droit d'utilisation. Plafond cinq hors chauffeur ; total cinq occupants donne au maximum quatre passagers.

**Actions :** renseigner/corriger les informations et soumettre le véhicule au dossier ; demander une nouvelle approbation lors d'un changement.

**Erreurs et reprise :** capacité incohérente, plaque discordante, donnée manquante, approbation non obtenue ; aucun nouveau véhicule substitué silencieusement à une réservation engagée.

**Droits et permissions :** propriétaire de la candidature et vérificateur habilité ; fiche client limitée aux informations sélectionnées après contrôle.

**Serveur et données :** véhicule déclaré, preuve recevable de capacité/droit d'utilisation, dossier et décisions ; sièges vus sur photo insuffisants pour valider la capacité.

### E20 — Justificatifs privés et validités

**Rôle et source :** candidat/chauffeur ; A04/A08, A/C02/C08/C10, R02.

**Informations :** pièces communes et propres à la catégorie selon la grille qualifiée ; manques, dépôt, examen, motif et validité temporelle séparée ; échéances/rappels trente, sept et un jours.

**Actions :** déposer ou remplacer ses pièces, répondre à une correction, consulter le motif et l'état d'examen.

**Erreurs et reprise :** fichier invalide selon limites futures, pièce manquante/refusée/expirée, réseau perdu ; un remplacement n'est pas une acceptation. Aucun intitulé légal ni date de validité inventés.

**Droits et permissions :** titulaire et agents habilités ; permission fichiers/caméra uniquement pour le dépôt pertinent. Aucun accès anonyme, entre comptes ou public aux originaux.

**Serveur et données :** stockage privé à construire avant dépôts, métadonnées, validité et décision, historique de remplacement ; refus des nouveaux engagements lorsqu'une pièce essentielle expire.

### E21 — Six photos du véhicule inscrit

**Rôle et source :** candidat/chauffeur ; A05, A/PH01 à PH06 et R02.

**Informations :** avant, arrière avec plaque lisible, côté gauche, côté droit, habitacle avant et habitacle arrière ; vue reçue/manquante/à corriger et motif utile.

**Actions :** choisir ou prendre chaque vue et corriger la vue demandée ; vérifier que les six représentent le même véhicule.

**Erreurs et reprise :** vue absente, floue ou discordante, permission refusée, fichier invalide, envoi interrompu. Six fichiers présents ne prouvent ni admission ni état mécanique.

**Droits et permissions :** dossier personnel et vérificateurs ; accès caméra/fichiers lié au dépôt. Originaux privés ; photos sélectionnées pour reconnaissance définies au point 06.10.

**Serveur et données :** vues attachées au bon dossier/véhicule, réception et décision ; contrôle de fichier, accès et reprise sans duplication d'approbation.

### E22 — Examen, correction et situation du dossier

**Rôle et source :** candidat/chauffeur ; A06 à A10, A/C04/C08, R02/R06.

**Informations :** dossier envoyé/en examen, décision datée, motifs et éléments à corriger, catégorie/véhicule effectivement approuvés, validités et conséquence sur les nouveaux engagements.

**Actions :** demander l'examen, consulter, corriger via E18 à E21, demander une revue par E12 ; retrouver sa situation après coupure.

**Erreurs et reprise :** incomplet, refusé, pièce expirée, suspension ou décision non reçue. Le compte ne s'approuve pas lui-même ; abonnement et autorisation restent distincts.

**Droits et permissions :** son dossier et fonctions habilitées. Aucun secret ou pièce d'un tiers dans une notification de décision.

**Serveur et données :** dossier, approbation, auteur/fonction/date/motif et véhicule lié ; pièce expirée : vérifier avant nouvelle prise en charge même si réservée, assistance pour les engagements, aucun arrêt dangereux automatique.

### E23 — Accès particulier, essai et achat programmé

**Rôle et source :** particulier concerné ; A10, A/C05 à C07, R06 et L12.01 à L12.06.

**Informations :** accès provisoire après approbation, essai unique de sept fois vingt-quatre heures depuis première réservation chauffeur payée et terminée, accès payé/programmé/expiré et dates serveur. Forfait 3 000 F pour trente fois vingt-quatre heures, en plus des commissions, sans clients garantis.

**Actions :** consulter son accès ; choisir volontairement l'achat admissible via le canal Android qualifié ; voir dates et résultat. Achat anticipé nominal : début à fin d'essai ou accès payé actif, sinon confirmation serveur.

**Erreurs et reprise :** achat inconnu/échoué, confirmation répétée, accès expiré, canal non qualifié. Aucun renouvellement automatique, bonus d'achat, nouveau débit ou deuxième période pour le même achat.

**Droits et permissions :** particulier titulaire ; taxi affiche absence d'abonnement WAKA et ne reçoit pas une obligation d'achat. Pièce expirée non réparée par paiement.

**Serveur et données :** accès provisoire, événement unique d'essai, achat et dates/conditions ; gains et historique conservés à expiration. **Achat avant première prestation : coordination avec essai encore à décider avant activation de ce cas**, sans report, prolongation ou suppression d'essai inventés.

### E24 — Historique d'accès, remboursement et prolongation

**Rôle et source :** chauffeur concerné ; A/C09, R06 et L12.07.

**Informations :** périodes, achats, décisions et résultat externe. Conditions : remboursement sur demande d'achat programmé non activé ; prolongation de durée vérifiée pour indisponibilité complète WAKA d'au moins vingt-quatre heures ; portion inutilisée remboursée lors de fermeture définitive ou passage taxi validé.

**Actions :** consulter, demander le traitement admissible et suivre une décision via E12 ; conserver l'accès à l'historique même sans abonnement actif.

**Erreurs et reprise :** remboursement non confirmé, motif nécessitant examen, indisponibilité non établie, changement taxi non approuvé. Faible demande n'est ni panne ni revenu garanti ; aucune confiscation automatique.

**Droits et permissions :** titulaire, assistance et finance selon fonction ; aucune auto-validation d'indisponibilité ou de changement de statut.

**Serveur et données :** période initiale, début/fin, durée et décision versionnées, secondes inutilisées, opération de remboursement et prolongation ; frais perdus WAKA. Combinaisons encore ouvertes précisées avant 12.07.

## Administration web

### E25 — Connexion administrative et fonction exercée

**Rôle et source :** agent nominatif habilité ; D§7.3, L05.05 et A/C01.

**Informations :** session administrative, fonction permise et explication d'un accès refusé ; authentification renforcée et récupération documentée à mettre en place.

**Actions :** ouvrir/fermer une session selon les moyens retenus ; accéder uniquement aux tâches autorisées ; réauthentification financière lors des actions sensibles applicables.

**Erreurs et reprise :** authentification ou habilitation absente, session expirée/révoquée, réponse perdue. Une URL connue ou un rôle client ne donne pas l'accès.

**Droits et permissions :** vérification, assistance et finance distinctes ; le fondateur peut les cumuler mais chaque action conserve la fonction exercée. Aucun moyen d'authentification nouveau fixé ici.

**Serveur et données :** session, habilitations et action auditable ; aucun secret, code temporaire ou jeton dans l'affichage ou le journal.

### E26 — Vérifier le dossier et décider l'admission

**Rôle et source :** agent de vérification ; A07 à A09, A/C02 à C04/C08, D§3.3 et L06.08/L06.09.

**Informations :** catégorie demandée, véhicule, capacité, six vues, pièces privées, validités et exigences qualifiées, historique des corrections/décisions.

**Actions :** demander correction, refuser ou approuver avec motif/date/auteur ; examiner nouvelle catégorie/véhicule ou expiration ; orienter les engagements existants vers l'assistance.

**Erreurs et reprise :** pièce inconnue, titre non qualifié, incohérence, décision concurrente ou perdue. Aucun fichier déposé ou abonnement ne permet d'ignorer les conditions d'approbation.

**Droits et permissions :** agents habilités au dossier ; aucune publication de pièce. La fonction de vérification ne vaut pas droit de verser ou d'effacer une écriture financière.

**Serveur et données :** dossier et version examinés, pièces/vues, décision nominative et validités ; remplacement et décision conservés séparément, accès chauffeur contrôlé.

### E27 — Assistance : traiter le dossier et les engagements

**Rôle et source :** agent d'assistance ; P/E10 à P/E15, V/J06 à J08, A/C08/C09 et D§7.4.

**Informations :** demande, parties, réservation/achat, événements disponibles, pièces uniquement si nécessaires et autorisées, déclarations, engagements encore actifs et opérations incertaines.

**Actions :** recueillir les faits, définir la prestation reconnue et la décision motivée, traiter absence/sécurité/expiration, transmettre la décision financière à la fonction autorisée, répondre aux participants.

**Erreurs et reprise :** preuves insuffisantes, part contestée, interlocuteur indisponible, décision perdue. GPS seul insuffisant ; avant/après embarquement distincts ; suspendre seulement le versement concerné, sans arrêt dangereux automatique.

**Droits et permissions :** périmètre du dossier, accès aux preuves nécessaires ; pas d'accès financier global ou à tous les justificatifs par commodité.

**Serveur et données :** incident, responsable, état des engagements, décision et historique ; corrections tracées sans effacer les paiements initiaux ni prétendre une opération externe confirmée.

### E28 — Finance : rapprochement, versement et remboursement

**Rôle et source :** agent finance ; P14/P15, V11/V12, A/C07/C09, R05 à R09, D§6.7 et L14/L15.05.

**Informations :** opérations identifiées, montants/devise/résultats, écarts de rapprochement, parts initiales et cumul confirmé ; fonds dus aux chauffeurs/parrains/remboursements séparés des recettes WAKA. Achats d'accès séparés des commissions.

**Actions :** rapprocher les références prestataire, traiter un paiement inconnu/tardif, exécuter les versements admissibles et remboursements motivés, suivre l'opération existante avant reprise, enregistrer les corrections compensatrices.

**Erreurs et reprise :** montant/référence/devise incohérents, doublon, notification désordonnée, versement ou remboursement inconnu, fonds déjà versés. Aucun double transfert ni écriture effacée ; remboursement client et récupération contractuelle séparés.

**Droits et permissions :** finance habilitée, réauthentification applicable et journal nominatif ; accès aux données financières nécessaires, sans collection générale de pièces d'identité.

**Serveur et données :** paiements/événements, réservations, achats, parts, solde réservé de retrait parrain, remboursements cumulés, versements et rapprochement ; contrat/minimums/frais/délais restent à obtenir.

### E29 — Droits, journal et suivi d'exploitation

**Rôle et source :** fonction administrative habilitée ; D§7.3/§7.5, A/C01/C10 et L17.04/L17.07/L17.10.

**Informations :** actions datées et fonction exercée, décisions et opérations identifiées, accès accordés/refusés, anomalies utiles à traiter et état du périmètre réellement exploité. Aucun tableau de recettes fictives.

**Actions :** consulter le journal dans son périmètre, retrouver une décision, orienter une anomalie au responsable ; arrêter les nouveaux engagements selon la procédure adoptée et suivre ceux déjà pris.

**Erreurs et reprise :** droits insuffisants, information ancienne, responsable absent, réponse perdue. Une suspension d'exploitation n'efface ni réservations, ni historique, ni sommes dues.

**Droits et permissions :** accès selon fonction, distinct des pouvoirs de vérification et finance ; aucun bouton d'effacement de preuve ou d'auto-attribution d'habilitation. Gestion technique des droits à préciser à 03.09.

**Serveur et données :** événements d'audit, acteur/fonction/date/motif, paramètres/version, anomalies et suspension ciblée ; secrets, références complètes sensibles et GPS inutiles masqués dans les journaux techniques.

## Couverture des trois parcours : 37 actions

Les tableaux suivants relient **les actions des acteurs**, issues des sources, aux fiches. Ce ne sont ni du code ni des tests d'application. La même fiche reçoit plusieurs situations au lieu de multiplier les écrans décoratifs.

### Partagé — P01 à P15

| Action source | Fiches utiles | Point à conserver |
|---|---|---|
| P01 | E05 | Points choisis et correction de position |
| P02 | E05 | Mode et quantité dans la capacité |
| P03 | E06 | Devis total, distance et annulation |
| P04 | E06, E07 | Acceptation du devis puis recherche |
| P05 | E07, E15 | Chauffeur admissible et trajet compatible |
| P06 | E07, E16 | Proposition et réponse trente secondes |
| P07 | E07, E08, E16, E17 | Acceptation validée et retenue distincte |
| P08 | E08 | Une demande de paiement liée à la réservation |
| P09 | E08, E17 | Confirmation serveur unique |
| P10 | E09, E17, E12 | Rendez-vous, contact et arrivée |
| P11 | E09, E17, E12 | Code/exception et embarquement autorisé |
| P12 | E09, E17, E15 | Service partagé, prix conservé |
| P13 | E10, E17, E15 | Fin passager distincte du trajet |
| P14 | E10, E13, E14, E28 | Parts et disponibilité après contrôles |
| P15 | E11, E13, E14, E28 | Résultat externe et reprise identifiée |

### VIP — V01 à V12

| Action source | Fiches utiles | Point à conserver |
|---|---|---|
| V01 | E05 | Véhicule entier et nombre admissible |
| V02 | E06 | Total 12,5 p indépendant des occupants |
| V03 | E07, E15 | Recherche de disponibilité exclusive |
| V04 | E07, E16 | Proposition VIP, prix et net |
| V05 | E08, E16, E17 | Exclusivité retenue et conflits revérifiés |
| V06 | E08 | Paiement du total véhicule |
| V07 | E08, E17 | Confirmation et maintien d'exclusivité |
| V08 | E09, E17, E12 | Reconnaissance, groupe et embarquement |
| V09 | E09, E17 | Aucun autre client malgré sièges libres |
| V10 | E10, E17, E15 | Fin/libération unique, trajet distinct |
| V11 | E13, E14, E28 | Commission et bonus VIP inchangés |
| V12 | E11, E13, E14, E28 | Versement et remboursements suivis |

### Admission — A01 à A10

| Action source | Fiches utiles | Point à conserver |
|---|---|---|
| A01 | E03, E18 | Catégorie choisie sans approbation automatique |
| A02 | E01, E02, E03, E18 | Compte et informations nécessaires |
| A03 | E19, E26 | Véhicule et capacité vérifiés |
| A04 | E20, E26 | Dépôt privé et examen distincts |
| A05 | E21, E26 | Six vues du même véhicule |
| A06 | E18, E22, E26 | Envoi et examen identifiés |
| A07 | E25, E26 | Agent habilité, concordance et motif |
| A08 | E18, E19, E20, E21, E22, E26 | Correction distincte d'acceptation |
| A09 | E22, E26 | Décision explicite liée au véhicule/catégorie |
| A10 | E03, E15, E22, E23, E24 | Accès réel avant nouvelles demandes |

## Couverture des exceptions et limites à conserver

| Famille source | Fiches et vérification documentaire attendue |
|---|---|
| P/E01 à P/E05 | E05 à E08/E16 : calcul absent, indisponibilité, refus, échéance et capacité concurrente restent explicables |
| P/E06 à P/E09 ; V/J02 à J05 | E08/E28 : échec distinct d'inconnu, aucune deuxième instruction de paiement, confirmation tardive sans réservation recréée |
| P/E10/P/E11 ; V/J06 | E09/E11/E12/E17 : attente cinq minutes, annulation avant embarquement et remboursement suivi |
| P/E12 à P/E14 ; V/J07 | E09/E12/E17/E27 : code indisponible, destination changée et interruption humaine sans preuve GPS seule |
| P/E15 ; V/J08 | E11/E13/E14/E28 : résultat externe retrouvé avant reprise, aucune somme fictivement versée |
| V/K01 à K10 | E07/E08/E15/E16/E17 : engagements partagés et VIP protégés dans les deux sens, sièges libres non revendus, libération fiable |
| A/C05 à C09 | E22/E23/E24/E26/E27/E28 : accès provisoire, essai unique, achat programmé, expirations distinctes et conditions de remboursement |
| A/C10 ; D§3.4 ; L05 | E01 à E04/E18 à E29 : compte/session et données propres, droits administratifs distincts, aucune protection déclarée déjà opérationnelle |

Les notifications utiles ouvrent la fiche concernée avec une référence autorisée ; leur réception n'exécute pas une action financière. Les codes, originaux et données sensibles ne figurent pas dans leur contenu. Les notifications, avis légitimes et partage temporaire prévus à l'objectif 16 seront détaillés aux points correspondants ; ce catalogue des trois parcours ne leur invente pas de politique ni de page supplémentaire.

Les conditions externes restent celles de R10. Une fiche de paiement n'obtient aucun contrat ; une fiche chauffeur n'obtient aucune autorisation ; un écran de confidentialité ne chiffre ni ne protège un stockage. Le cas d'achat avant première prestation et les combinaisons de prolongation/remboursement d'accès restent explicitement ouverts dans C06/C09 avant leur implémentation dépendante.

**Coût de réalisation documentaire : 0 F.** Aucun appel de carte, SMS, achat, installation ou paiement. Ce point s'arrête à la présentation des tâches ; données, états, API, droits détaillés et organisation technique restent aux points 03.06 à 03.10 du lot.
