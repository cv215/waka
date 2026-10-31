# WAKA — référence des règles adoptées

Version de travail 03.01 v1 — 10 octobre 2026. Issue du dossier WAKA v2 du 8 octobre et des décisions du fondateur. **Référence acceptée pour la poursuite**, par « continuons » après sa livraison le 10 octobre. Elle prépare le futur code ; aucune fonction de l’application n’est créée par ce document.

Les messages du fondateur priment. Cette fiche rassemble les règles existantes ; le dossier reste leur référence détaillée. Un changement futur exige une décision datée et une version applicable aux nouveaux engagements. Les sommes sont en **XAF**, appelé F dans les exemples.

## R01 — Modes, capacité et exclusivité

- Partagé et VIP sont présents dès le premier lancement réel. Aucune gamme de confort ni majoration liée au confort.
- Une réservation partagée achète de une à cinq places, dans la capacité disponible sur les segments concernés.
- Le maximum est **cinq passagers hors chauffeur**. La capacité offerte vaut le minimum entre cinq et les places passagers autorisées du véhicule. Un véhicule autorisé pour cinq occupants au total peut accueillir au maximum quatre passagers avec son chauffeur.
- Le VIP réserve le véhicule entier pour le parcours exclusif. Le nombre de passagers doit respecter sa capacité autorisée. Le prix est indépendant du nombre d’occupants effectivement présents.
- Au pilote, la disponibilité VIP exige l’absence de réservation partagée active et de retenue concurrente engageant le véhicule. Une demande VIP ne retire aucune réservation partagée engagée. Aucun passager supplémentaire issu d’une autre réservation n’est ajouté pendant le parcours exclusif ; les engagements successifs compatibles restent une évolution ultérieure.

Sources : dossier §1.1, §3.2, §4.3, §6.1 et §7.2 ; restriction du pilote conservée dans le plan §10.02.

## R02 — Taxi, particulier et véhicule admis

Taxi et particulier ont les mêmes prix clients, la même commission par réservation et le même montant chauffeur avant abonnement. Les deux peuvent assurer partagé et VIP après approbation. Le taxi présente les preuves de son statut professionnel ; le particulier présente également un dossier recevable. Payer un abonnement ne remplace pas une autorisation ou une approbation.

Informations véhicule obligatoires : marque, modèle, année, couleur, immatriculation, capacité totale autorisée, places passagers proposées et titulaire ou droit d’utilisation. Six photos représentent le véhicule inscrit : avant, arrière avec plaque lisible, côté gauche, côté droit, habitacle avant et habitacle arrière.

Les justificatifs nécessaires doivent être vérifiés et valides. Le dépôt seul n’active pas le chauffeur. Un changement de véhicule ou de catégorie nécessite une nouvelle approbation. L’expiration d’une pièce essentielle bloque les nouvelles réservations ; l’assistance traite les engagements existants. Les intitulés administratifs applicables restent à qualifier.

Sources : dossier §1.1 et §3.1–3.3. Le parcours détaillé d’admission sera traité à 03.04.

## R03 — Prix, commission et répartition

| Symbole | Sens |
|---|---|
| p | Prix d’une place partagé, choisi dans la grille R04 |
| n | Nombre de places payées dans une réservation partagée, de 1 à 5 dans la capacité disponible |
| m | Coefficient de prix et de commission : n en partagé ; 12,5 en VIP |
| r | Coefficient des bonus : n en partagé ; 10 en VIP |
| P | Prix total accepté par le client |
| C | Commission brute prélevée sur cette réservation, comprenant les bonus |
| D | Montant dû au chauffeur avant abonnement |
| Bclient / Bchauffeur | Parts dues aux parrains éligibles du compte payeur et du chauffeur |
| W | Reste WAKA avant les autres coûts ; ce montant n’est pas un bénéfice |

`Arrondi(x)` signifie : franc entier le plus proche, moitié exacte vers le haut. **Les calculs intermédiaires restent exacts ; chaque montant final est arrondi une seule fois.** Ainsi 2 562,50 F devient 2 563 F.

```text
P = Arrondi(m × p)
C = Arrondi(m × (p + 300) / 15)
D = P − C
Bclient = 10 × r si le parrain du payeur est éligible ; sinon 0
Bchauffeur = 5 × r si le parrain du chauffeur est éligible ; sinon 0
W = C − Bclient − Bchauffeur

P = D + Bclient + Bchauffeur + W
```

Le VIP applique **5 × 2,5 = 12,5** au prix d’une place et au calcul exact de sa commission. Les bonus VIP restent **100 F et 50 F**, selon l’éligibilité. La commission finance déjà ces parts : aucun deuxième prélèvement sur le chauffeur ou ajout au prix client. Une part sans parrain éligible reste à WAKA.

La commission d’une réservation de plusieurs places se calcule sur l’ensemble avant l’arrondi. À p = 205 F, cinq places donnent C = 168 F ; multiplier la commission unitaire arrondie de 34 F donnerait une somme incorrecte de 170 F.

Exemples de calcul documentaire avec les deux parrains éligibles :

| p et réservation | P client | C commission | D chauffeur | Bclient | Bchauffeur | W avant coûts |
|---|---:|---:|---:|---:|---:|---:|
| 600 F, partagé 1 place | 600 | 60 | 540 | 10 | 5 | 45 |
| 600 F, partagé 5 places | 3 000 | 300 | 2 700 | 50 | 25 | 225 |
| 600 F, VIP | **7 500** | **750** | **6 750** | **100** | **50** | **600** |
| 205 F, partagé 5 places | 1 025 | 168 | 857 | 50 | 25 | 93 |
| 205 F, VIP | 2 563 | 421 | 2 142 | 100 | 50 | 271 |

Le VIP à 7 500 F correspond à la tranche p = 600 F. Les autres tranches donnent leurs propres prix VIP. Le nombre réel d’occupants ne réduit pas ces prix. Dans une réservation de groupe, la part client rémunère le parrain du compte qui paie ; elle ne crée pas plusieurs comptes parrainés. Plusieurs réservations d’un même trajet gardent chacune leur répartition.

Sources : dossier §6.1–6.2. Les écritures applicatives et le moteur de calcul seront réalisés aux objectifs 07 et 14.

## R04 — Grille et distance de référence

Le devis utilise la distance routière de référence du passager, arrondie au mètre le plus proche avec une moitié vers le haut. Choisir ensuite la tranche, sans arrondi préalable au kilomètre. La borne basse est incluse, la borne haute exclue ; la dernière inclut **17 900 mètres**. Au-delà de 17 900 mètres après arrondi, aucun devis avant extension adoptée.

C1 ci-dessous est la commission arrondie d’une place. Pour plusieurs places, recalculer C selon R03 ; multiplier C1 est incorrect lorsque son arrondi intervient.

| Distance routière en mètres | p : une place | C1 : commission une place | Prix VIP | Commission VIP |
|---|---:|---:|---:|---:|
| [0 ; 1 000[ | 150 | 30 | 1 875 | 375 |
| [1 000 ; 1 900[ | 205 | 34 | 2 563 | 421 |
| [1 900 ; 2 700[ | 260 | 37 | 3 250 | 467 |
| [2 700 ; 3 600[ | 315 | 41 | 3 938 | 513 |
| [3 600 ; 4 400[ | 370 | 45 | 4 625 | 558 |
| [4 400 ; 5 300[ | 430 | 49 | 5 375 | 608 |
| [5 300 ; 6 100[ | 485 | 52 | 6 063 | 654 |
| [6 100 ; 7 000[ | 540 | 56 | 6 750 | 700 |
| [7 000 ; 7 800[ | 600 | 60 | 7 500 | 750 |
| [7 800 ; 8 600[ | 655 | 64 | 8 188 | 796 |
| [8 600 ; 9 400[ | 715 | 68 | 8 938 | 846 |
| [9 400 ; 10 300[ | 770 | 71 | 9 625 | 892 |
| [10 300 ; 11 100[ | 830 | 75 | 10 375 | 942 |
| [11 100 ; 12 200[ | 890 | 79 | 11 125 | 992 |
| [12 200 ; 13 400[ | 950 | 83 | 11 875 | 1 042 |
| [13 400 ; 14 500[ | 1 010 | 87 | 12 625 | 1 092 |
| [14 500 ; 15 600[ | 1 070 | 91 | 13 375 | 1 142 |
| [15 600 ; 16 800[ | 1 130 | 95 | 14 125 | 1 192 |
| [16 800 ; 17 900] | 1 200 | 100 | 15 000 | 1 250 |

Le prix accepté reste figé. Un détour imposé par l’ajout d’un passager ne l’augmente pas. Un changement de destination demandé par le client exige un nouveau devis explicitement accepté et une trace ; aucun péage ou supplément n’est ajouté silencieusement. Cette grille est le choix WAKA du projet ; elle n’est pas présentée comme un barème réglementaire homologué.

Sources : dossier §4.2 et §6.3.

## R05 — Parrainage et disponibilité des gains

- Un compte personne a au plus un parrain facultatif, sur un niveau. Une correction exceptionnelle après gains nécessite assistance, justification et journal ; aucune réattribution libre.
- La période dure **douze mois calendaires** depuis la première réservation du filleul payée et terminée, comme client ou chauffeur. Changer de rôle ne redémarre pas la période. Si le jour n’existe pas dans le mois d’arrivée, prendre le dernier jour valide ; une réservation terminée exactement à l’expiration ne produit plus de bonus.
- L’éligibilité du parrain est contrôlée à la fin de chaque réservation payée. Les bonus deviennent disponibles après paiement confirmé, prestation effectivement terminée et contrôles applicables. Un bonus en attente n’est pas encore retirable.
- Aucun bonus acquis pour une simple inscription, un achat d’abonnement, une réservation entièrement annulée, un paiement échoué ou une course totalement gratuite. Toute promotion modifiant l’encaissement exige sa règle et son financement avant activation.
- Une même personne peut recevoir les deux parts si elle a parrainé indépendamment le payeur et le chauffeur. Auto-parrainage, comptes multiples d’une même personne et courses fabriquées sont interdits. Une adresse ou un téléphone partagé ne prouve pas automatiquement une fraude ; examen humain et motif traçable.
- Retrait des gains de parrainage à partir de **1 000 F disponibles**, sans nombre minimal de courses personnelles. Ce seuil concerne les parrains ; le versement chauffeur se fait par réservation terminée et rapprochée.
- Aucune commission WAKA de 1 % sur les retraits de parrainage au pilote. Les frais réels de versement chauffeur et parrains sont des coûts WAKA. L’épuisement d’un budget marketing n’efface pas les bonus acquis ; attente, disponibilité, retrait et reprise après remboursement restent distincts.

Sources : dossier §6.2, §6.5 et §7.4.

## R06 — Abonnement particulier et essai

Le **taxi approuvé n’a pas d’abonnement WAKA**. Le particulier paie **3 000 F pour une période glissante de 30 jours**, en plus des commissions. Aucun volume de clients ou revenu garanti n’est attaché à ce forfait.

L’essai unique de **sept périodes de vingt-quatre heures par personne vérifiée** commence à sa première réservation effectuée comme chauffeur, réellement terminée et payée. Les commissions et règles de parrainage s’appliquent aussi pendant l’essai.

L’accès provisoire initial après approbation permet au particulier d’atteindre ce premier engagement, conformément au point 12.01 du plan. L’inactivité préalable ne consomme pas l’essai. Le déclenchement reste lié à la prestation terminée et payée ; il n’est pas déplacé à l’inscription et un changement de téléphone ou de véhicule ne recrée pas un essai.

Les trente jours payés sont **trente périodes de vingt-quatre heures**. Un achat anticipé commence à la fin de l’essai ou de l’abonnement encore actif ; sinon il commence à la confirmation serveur du paiement. La durée payée n’est pas consommée avant sa date de début.

Afficher début et fin de l’accès. Le renouvellement est volontaire, sans reconduction automatique au lancement. Sans accès valide, aucune nouvelle réservation ; les engagements existants sont terminés ou traités par l’assistance. Les gains acquis et l’accès à l’historique restent conservés.

Conditions déjà adoptées :

| Situation | Traitement |
|---|---|
| Achat programmé, non encore activé | Remboursement intégral sur demande avant le début |
| Indisponibilité complète imputable à WAKA d’au moins 24 h sur le périmètre vendu | Prolongation de la durée vérifiée |
| Fermeture définitive ou passage validé au statut taxi | Remboursement de la portion inutilisée : Arrondi(montant payé × secondes restantes / durée payée), borné entre 0 et le montant payé |
| Faible demande | Aucun revenu garanti ; ce n’est pas une indisponibilité technique |
| Autre motif, discipline ou droit impératif applicable | Décision humaine avec motif ; aucune confiscation automatique |

WAKA supporte les frais de remboursement non récupérés. L’achat de l’abonnement ne produit aucun bonus supplémentaire. Ses recettes et frais sont suivis séparément des commissions. **Le canal de vente Android de cet accès reste à qualifier** ; le paiement Mobile Money des courses ne le détermine pas à lui seul.

Sources : dossier §5.2, §6.4 et §7.2 ; plan §12.01–12.02 pour l’accès provisoire.

## R07 — Paiement, confirmation et sommes dues

Les courses sont payées par Mobile Money, sans espèces. La confirmation du prestataire **côté serveur** doit établir opérateur, référence, devise XAF, montant et statut. Le retour à un écran ne prouve pas le paiement. Une notification répétée ne crée aucune deuxième écriture.

Un paiement réussi après expiration de la réservation déclenche le traitement et le remboursement suivi prévus ; il ne recrée pas une place vendue. Les gains chauffeur sont ouverts après la prestation selon les contrôles, puis versés par réservation terminée et rapprochée selon le contrat applicable.

L’argent encaissé, les sommes dues aux chauffeurs et parrains, les remboursements et les recettes WAKA restent séparés et rapprochés. Les fonds dus à des bénéficiaires ne deviennent pas une trésorerie libre. Aucun portefeuille client rechargeable et retirable n’est prévu au lancement sans étude dédiée. Les contrats prestataires et leurs frais restent à obtenir.

Sources : dossier §4.1, §4.3, §5.1, §6.7 et §7.4.

## R08 — Annulation avant embarquement

Remboursement intégral du prix client, sans pénalité client et sans bonus acquis. Les frais prestataires non récupérés sont supportés par WAKA. Annulations répétées et absences donnent lieu à examen humain et possibilité de contestation ; aucune amende automatique fondée sur le GPS.

Sources : dossier §4.4 et §6.6.

## R09 — Interruption et remboursements partiels cumulés

Après embarquement, l’assistance établit le service réellement fourni, fixe le remboursement et enregistre son motif. Le GPS seul ne suffit pas. Le montant conservé ne dépasse pas le prix initial accepté.

Pour un service partiel accepté, la part servie repose sur l’itinéraire routier de référence entre les arrêts documentés, bornée entre zéro et un, plutôt que sur les seuls kilomètres GPS ou la durée. Le prix conservé est cette part du prix accepté, arrondie au franc ; le remboursement est la différence. Une part contestée suspend seulement le versement concerné jusqu’à revue humaine.

Garder les montants initiaux `P0, C0, D0, Bclient0, Bchauffeur0, W0` et leur version. Additionner seulement les remboursements confirmés en `Rtotal`, entre zéro et P0. Pour une réservation payée de prix initial positif :

```text
T = P0 − Rtotal
Cret = Arrondi(C0 × T / P0)
Bclient_ret = Arrondi(Bclient0 × T / P0)
Bchauffeur_ret = Arrondi(Bchauffeur0 × T / P0)
Dret = T − Cret
Wret = Cret − Bclient_ret − Bchauffeur_ret
```

Chaque reprise vaut la part initiale moins sa part conservée. Recalculer depuis le **remboursement cumulé et les montants initiaux**, sans répartir et arrondir séparément chaque remboursement successif. Le chauffeur et WAKA sont les différences d’équilibrage.

Contrôles obligatoires : chaque part conservée reste dans sa part initiale ; `Cret ≥ Bclient_ret + Bchauffeur_ret` ; et `P0 = Rtotal + Dret + Bclient_ret + Bchauffeur_ret + Wret`. Les reprises réunies valent exactement Rtotal. Si un futur barème viole le budget des bonus, le mécanisme de limitation et de plus grands restes du §6.6, avec départage documenté, reste applicable avant écriture.

Exemple documentaire : VIP p = 600 F, P0 = 7 500 F et Rtotal = 2 500 F. Restent T = 5 000 F, Cret = 500 F, Dret = 4 500 F, bonus 67/33 F, Wret = 400 F. Les reprises sont 2 250 F chauffeur, 33/17 F parrains et 200 F WAKA : total 2 500 F.

Un retrait antérieur n’efface pas une reprise justifiée. Les gains futurs portent sa trace et le litige est traité humainement ; WAKA finance néanmoins le remboursement client selon ses engagements. Une écriture interne ne remplace pas la confirmation prestataire du remboursement.

Sources : dossier §6.6 et §7.4.

## R10 — Versions, paramètres et preuves encore attendues

Chaque réservation conserve son p, sa distance de devis, ses places, m et r, ses montants, ses parrains et leur éligibilité, le statut chauffeur et les versions des règles. Les remboursements utilisent cet instantané. Chaque abonnement conserve montant, confirmation, durée, début, fin et conditions. Changer de grille ou de statut ne réécrit pas les engagements historiques.

Les paramètres initiaux de devis, recherche, réponse et paiement sont ceux du §4.1 : cinq minutes de validité du devis, recherche de deux minutes et réponse chauffeur de trente secondes. L’acceptation doit être reçue **avant expiration du devis et dans les deux minutes de recherche**. Cette acceptation valide fige le prix et ouvre une retenue de places avec un délai distinct de paiement de trois minutes ; celui-ci ne consomme pas le reste du délai de devis. Leur évolution sera versionnée après observation ; ce ne sont pas des garanties d’un prestataire. Les paramètres de prise en charge, détour et fraîcheur GPS restent ceux du §4.2 ; leur parcours sera détaillé à 03.02.

| Élément extérieur | Preuve à obtenir ; état de cette fiche |
|---|---|
| Transport, particuliers, véhicules et assurances | Qualification du montage et des titres applicables avant activation ; aucune autorisation obtenue par cette rédaction |
| Encaissement, redistribution et remboursement | Contrat, capacités d’essai, frais, minimums, plafonds et délais ; aucun accord prestataire annoncé |
| Abonnement Android | Canal autorisé et coûts propres à qualifier avant vente |
| Cartes, communication, données privées et territoire | Conditions, disponibilité, droits, protection et observations réelles à établir dans les points correspondants |

Les frais illustratifs et provisions du budget restent des hypothèses. Ni un taux prestataire de 1 %, ni un coût de boutique, ni une rentabilité ne deviennent des faits confirmés dans cette référence. La rédaction coûte **0 F** ; elle n’achète ni service ni abonnement.

Sources : dossier §1.2, §3.1–3.4, §5.1–5.3, §6.4, §6.11 et plan objectif 02. La récupération indépendante du coffre et l’édition de documents réels restent au point 01.10.d.

## R11 — Lancement, zone et voyages entre villes (décisions du fondateur du 29 octobre 2026)

**Zone et grille.**
- Lancement à **Yaoundé** seulement ; d'autres villes suivront rapidement.
- La zone desservie est un cercle de 20 km autour du centre de Yaoundé (3,848° N ; 11,502° E). C'est un choix technique fait par Claude à la demande du fondateur (« le plus pratique pour les utilisateurs ») : large pour ne refuser personne en ville, gratuit, sans service de cartes. Ajouter une ville revient à ajouter un centre et un rayon.
- La grille **launch-v1** (R04) est celle mise en vente.

**Voyages entre villes (« Voyage »), dès le lancement.**
- **Annonce** : un chauffeur approuvé, taxi ou particulier, publie un voyage avec le lieu et la date de départ, ses escales éventuelles et l'arrivée. **Aucun abonnement n'est exigé pour les voyages**, même d'un particulier.
- **Places** : au plus les places passagers proposées du véhicule approuvé, et jamais plus de cinq (R01).
- **Prix** : le chauffeur fixe un prix par place pour **chaque paire départ–arrivée** possible entre ses arrêts. Un passager peut monter et descendre à une escale. Chaque prix est un nombre entier de francs, d'au moins 1 F et d'au plus **10 000 F par place**.
- **Commission WAKA : 10 %** du prix payé, arrondie au franc (moitié vers le haut) ; le chauffeur reçoit le reste. Pas de bonus de parrainage sur un voyage tant que le fondateur ne l'a pas décidé (R05 ne s'y applique pas automatiquement).
- **Paiement** : le client est débité à la réservation. L'argent n'est versé au chauffeur **qu'à l'embarquement** ; jusque-là, il reste dû au client.
- **Annulation par le chauffeur** : possible à tout moment avant l'embarquement ; chaque client est remboursé intégralement, sans frais.
- **Annulation par le client** : possible à tout moment avant l'embarquement. Il est remboursé intégralement si elle a lieu **12 h ou plus avant le départ**. Sinon, des frais sont retenus sur le prix : **15 %** entre 12 h et 3 h avant le départ, **30 %** à moins de 3 h. Ces frais sont partagés **moitié chauffeur, moitié WAKA**. Le reste est remboursé.
- **Arrondis** : les frais sont arrondis au franc (moitié vers le haut). Le chauffeur reçoit la moitié arrondie au franc inférieur, et WAKA le reste. Ainsi la somme rembourse exactement le prix.
- **Heures** : elles sont celles du serveur, à l'heure de Douala (UTC+1). La limite exacte de 12 h ou de 3 h compte dans le palier le plus favorable au client.

**Porte-monnaie dans l'application.**
- Le fondateur veut des comptes rechargeables par Mobile Money : les transferts dans l'application sont gratuits, les frais ne s'appliquent qu'aux dépôts et aux retraits.
- Ce choix **remplace l'exclusion du portefeuille énoncée en R07**, à une condition : l'argent est détenu **par un partenaire agréé** (opérateur Mobile Money ou banque), et WAKA tient le registre.
- Cette fonction sera traitée avec les paiements, à la fin. Elle exige un contrat avec ce partenaire et sa qualification réglementaire (monnaie électronique, COBAC/BEAC). Aucune somme réelle n'est manipulée avant.
## R12 — Décisions prises par Claude sur délégation du fondateur (30 octobre 2026)

Le fondateur a écrit : « prends les décisions toi-même ». Chaque choix ci-dessous est **le plus prudent** des options présentées. C'est un choix de Claude, que le fondateur peut changer à tout moment par une nouvelle version de règle.

**Parrainage (complète R05).**
- **Délai** : on ne peut saisir le code d'un parrain que tant que son compte n'a participé à **aucune course terminée et payée**, comme client ou comme chauffeur. Aujourd'hui, cela couvre l'embarquement d'un voyage payé, client ou chauffeur.
- **Parrainage mutuel interdit** : si A est le filleul de B, A ne peut pas devenir le parrain de B.
- **Essais de codes** : au plus **5 codes erronés par compte sur 24 heures glissantes**. Au-delà, il faut attendre.
- **Voyages entre villes** : aucun bonus de parrainage pour l'instant.

**Voyages (complète R11).**
- **Passager absent** : un passager payé qui ne se présente pas est traité **comme une annulation tardive**. 30 % sont retenus (moitié chauffeur, moitié WAKA) et 70 % sont remboursés. Seul le chauffeur peut le déclarer, et seulement après l'heure prévue à l'arrêt de montée.

**Abonnement particulier (cas ouverts de R06).**
- **Achat avant la première prestation** : refusé. L'accès provisoire suffit avant la première course, et l'essai doit pouvoir commencer sans être consommé par un achat.
- **Première prestation interrompue** : elle ne déclenche **ni l'essai** ni la **fenêtre de douze mois** du parrainage. Seule une prestation menée à son terme et payée les déclenche.
- **Remboursement d'une période** : une période payée **pas encore commencée** est remboursable en entier. Une période commencée n'est pas remboursée, sauf décision motivée de l'assistance (K06).

**Incidents (A23–A25, A39), décidés par Claude sur délégation.**
- **Qui signale** : un incident se rattache aujourd'hui à une **réservation de voyage**. Seules ses deux parties, le client et le chauffeur du voyage, peuvent le déclarer, le lire et y répondre.
- **Catégories** : absence contestée, sécurité, comportement, paiement, véhicule, autre.
- **Texte** : de 1 à 1 000 caractères, chiffré. Pas de pièce jointe pour l'instant. Une partie ne voit jamais l'identité de l'autre.
- **États** :
  - `open` à la déclaration ;
  - `decided` après la décision motivée de l'assistance ;
  - `contested` si une partie conteste, ce qui rouvre l'examen ;
  - une nouvelle décision le ramène à `decided`.
- **Décisions de l'assistance** : sans suite, remboursement à étudier par la finance, avertissement au chauffeur, revue du compte. Chacune porte un motif codé et un texte facultatif lu par les deux parties.
- **Une décision ne déplace aucun argent**. Un remboursement passera par la fonction finance et le prestataire de paiement (A40).
- **Une seule réclamation par réservation et par déclarant** : un nouveau signalement retrouve la première, et une contestation la rouvre.
**Données personnelles (A06).**
- Les demandes sont traitées par une **habilitation dédiée** `PRIVACY`, distincte de l'assistance aux courses.
- Les **durées de conservation** relèvent du droit applicable et ne peuvent pas être fixées par Claude. Elles restent **à qualifier** avant toute collecte réelle, comme prévu par les règles d'accès et de conservation.
## R13 — Courses en ville au lancement (décisions de Claude par délégation, 30 octobre 2026)

**Paramètres déjà adoptés et repris tels quels** (parcours partagé et VIP) :
- devis valable 5 minutes ; recherche de 2 minutes ; réponse du chauffeur en 30 secondes ;
- position du chauffeur fraîche de 60 secondes au plus ;
- prise en charge visée en 10 minutes de route au plus ;
- paiement en 3 minutes distinctes ;
- 5 minutes de contact et d'attente au point de rendez-vous ;
- remboursement intégral en cas d'annulation avant l'embarquement (R08).

**Choix de Claude**, chaque fois le plus prudent :
- **Pas de regroupement de clients différents dans une même voiture au lancement.** Une voiture n'a qu'une seule réservation en ville active à la fois. En partagé, cette réservation porte de 1 à 5 places pour un groupe ; en VIP, elle porte sur la voiture entière. Le regroupement viendra avec le calcul des détours, qui dépend du fournisseur de cartes.
- **Choix du chauffeur** : par **temps de route** jusqu'au point de départ, fourni par le fournisseur de cartes (`PickupTimeProvider`), jamais par la distance à vol d'oiseau. Les propositions partent une par une, au chauffeur le plus proche en temps, parmi les chauffeurs recevables : approuvés et avec un accès effectif, position fraîche, assez de places, aucune course en cours, à 10 minutes ou moins, jamais le client lui-même. Un chauffeur déjà sollicité pour cette recherche ne l'est pas une seconde fois.
- **Absence du client** : le chauffeur ne peut la déclarer qu'après 5 minutes d'attente au point convenu. Aucune pénalité automatique : le client est remboursé, et un examen humain reste possible par un incident (R08).
- **Absence du chauffeur ou véhicule non conforme** : le client annule sans frais (R08).
- **Embarquement** : par un code à 6 chiffres, comme pour les voyages. **Fin de course** : le règlement prévu par R03 est enregistré (prix, commission, part du chauffeur). Les bonus de parrainage seront établis avec le paiement réel.
## Retrouver les passages du dossier

- Décisions de référence §1.1 (reference detaillee conservee hors du depot).
- Catégories, capacité et véhicule §3 (reference detaillee conservee hors du depot).
- Paramètres et service §4 (reference detaillee conservee hors du depot).
- Conditions externes §5 (reference detaillee conservee hors du depot).
- Formules et grille §6.1–6.3 (reference detaillee conservee hors du depot).
- Abonnement, parrains et remboursements §6.4–6.7 (reference detaillee conservee hors du depot).
- Versions et contrôles §6.11 (reference detaillee conservee hors du depot).
- Accès provisoire, durée et début des périodes §7.2 (reference detaillee conservee hors du depot).
- Versements et incidents §7.4 (reference detaillee conservee hors du depot).

Pour la revue, lire d’abord R01, les exemples R03, R05, R06 et R09. La définition des écrans, données, états, API et fonctions reste dans les prochaines étapes ; le contrôle présent porte sur la fidélité du document et les calculs, sans transaction réelle.

## R14 — Argent : paiements, crédit WAKA et retraits (30 octobre 2026)

Décisions du fondateur :
- **Prestataire Fapshi**, ouvert avec un compte de particulier en attendant l'entreprise. Notch Pay reste branché comme solution de remplacement.
- **Mobile Money MTN et Orange.** Orange Money sort de l'attente ; seuls les SMS y restent.
- **Argent conservé dans l'application**, sans passer par une banque.
- **Frais** : WAKA supporte les frais d'encaissement, et les frais apparaissent au retrait (voir plus bas).

Le reste est décidé par Claude, en retenant le choix le plus prudent.

**Où est l'argent.**
- L'argent encaissé reste chez Fapshi, sur le service d'encaissement de WAKA. Les retraits partent d'un service de versement séparé : Fapshi interdit d'encaisser sur un service dont les versements sont activés. Le fondateur approvisionne ce service depuis le tableau de bord Fapshi, car l'interface de Fapshi ne permet pas de le faire automatiquement.
- **Registre** : WAKA tient un registre (« grand livre »). Chaque mouvement est inscrit une fois, au débit d'un compte et au crédit d'un autre, pour le même montant. Aucun franc n'apparaît ni ne disparaît.
- **Rapprochement** : le total que le registre attribue au prestataire doit égaler la somme des soldes réels de ses deux services.
- **Deux poches par personne** :
  - le **crédit**, qui reçoit les dépôts, les remboursements et les paiements arrivés en retard ;
  - les **gains**, qui reçoivent la part du chauffeur et, plus tard, les bonus de parrainage.

  Les deux poches peuvent payer une prestation.
- **Aucun transfert entre utilisateurs** au lancement. Cela remplace « transferts internes gratuits » (R11) tant qu'aucun avis juridique ne l'autorise.

**Payer une prestation.**
- **Moyens** : une course, des places de voyage ou une période d'abonnement se paient par MTN, Orange, le crédit ou les gains.
- **Montant** : c'est toujours celui du serveur.
- **Frais** : le client ne paie **aucun frais**, ni au paiement ni au dépôt. WAKA supporte les 3 % d'encaissement de Fapshi, et le crédit du client reçoit exactement ce qu'il a payé.
- **Garde** : l'argent d'une prestation payée attend dans sa garde jusqu'au règlement :
  - **course** : à la fin, la part du chauffeur (dans ses gains) et la commission (R03) ;
  - **voyage** : à l'embarquement, 90 % au chauffeur et 10 % à WAKA (R11) ;
  - **annulation ou absence** : le règlement de R08, R11 et R12. Le remboursement arrive **immédiatement dans le crédit** du client.
- **Confirmation (R07)** : un paiement n'est confirmé que par le statut relu chez le prestataire.
  - Ni l'écran du téléphone ni le contenu d'une notification ne suffisent. Une notification authentifiée sert seulement à déclencher cette relecture.
  - Fapshi ne renvoie jamais une notification perdue. Les paiements et retraits encore ouverts après une minute sont donc relus régulièrement.
  - Le montant doit correspondre exactement. Sinon, rien n'est crédité et le paiement passe en examen.
- **Paiement tardif** : un paiement arrivé après le délai de 3 minutes ne confirme pas la prestation ; il reste dans le crédit du client.

**Déposer et retirer.**
- **Dépôt** : de 500 à 200 000 F, sans frais. Le crédit ne peut pas dépasser 300 000 F, dépôts en attente compris.
- **Retrait** : de 500 à 200 000 F, et **seulement vers le numéro vérifié du compte**. Les frais sont prélevés en plus sur la même poche :
  - **2 % sur les gains**, pour les chauffeurs et les parrains ;
  - **3 % sur le crédit**, pour le client qui regrette un dépôt ou veut sortir un remboursement.
- Les frais sont arrondis au franc supérieur.
- **Échec d'un retrait** : tout revient dans la poche, frais compris.
- **Issue inconnue** : Fapshi ne protège pas contre une demande envoyée deux fois. Si l'issue d'une demande est inconnue, elle n'est **jamais renvoyée** : on la retrouve par l'identifiant WAKA, et rien n'est rendu avant le statut final.

**Prudence.**
- Un enregistrement introuvable chez le prestataire n'est jamais traité comme un échec : la finance le rapproche à la main.
- **Agrégateurs** : Fapshi indique ne pas accepter les « agrégateurs de paiement », et active le paiement direct en production selon l'usage. WAKA doit être présentée honnêtement comme un service de transport qui encaisse le prix de ses propres courses. Fapshi peut refuser ; Notch Pay reste alors prêt.
- **Avis juridique** à obtenir avant d'ouvrir les dépôts au public : un crédit rechargeable et retirable peut relever de la monnaie électronique (CEMAC/COBAC).
- **Bac à sable d'abord** : la forme réelle des réponses de Fapshi est à confirmer en bac à sable avant toute clé réelle. Les clés ne vont jamais dans Git.

## R15 — Partagé par direction : plusieurs clients dans une voiture (31 octobre 2026)

Cette règle remplace, pour le partagé, le choix « pas de regroupement » de R13.

**Décisions du fondateur.**
- **Partagé** : le chauffeur indique sa **direction**, c'est-à-dire le lieu où il va. WAKA lui propose des clients dont le départ et l'arrivée sont **sur son chemin**. Plusieurs clients qui ne se connaissent pas partagent la voiture, dans la limite des places offertes.
- **Détour** : **5 minutes au plus**. Le prix des clients déjà engagés ne change pas (R03 : un détour imposé par l'ajout d'un passager ne l'augmente pas).
- **VIP inchangé** : la voiture entière, le chauffeur libre le plus proche, aucun autre client.

**Précisions de Claude**, chaque fois la plus prudente :
- **Sans direction**, le chauffeur reste disponible comme dans R13 : une seule réservation à la fois, en partagé (un groupe) ou en VIP.
- **Avec une direction**, il ne reçoit que des demandes **partagées**, jamais de VIP. Il peut porter plusieurs réservations partagées en même temps, tant que leurs places additionnées ne dépassent pas ses places offertes.
- **Calcul du détour**, sur le trajet routier du chauffeur, sans trafic en direct :
  - détour = durée (position → départ du client → arrivée du client → direction) − durée (position → direction) ;
  - une demande n'est proposée que si ce détour, **ajouté aux détours déjà acceptés depuis que la voiture a pris son premier client**, reste de 5 minutes au plus. Ainsi, aucun client déjà engagé ne voit son trajet allongé de plus de 5 minutes en tout ;
  - le compteur repart de zéro quand la voiture n'a plus aucune réservation en cours.
- Les autres conditions de R13 restent : prise en charge à 10 minutes de route au plus, position fraîche, accès effectif, une proposition à la fois, paiement en 3 minutes, code d'embarquement par client.
- **Limite connue** : le détour est mesuré pour l'ordre « prendre le nouveau client, le déposer, puis continuer ». L'ordre réel des arrêts est choisi par le chauffeur ; la meilleure tournée sera une évolution ultérieure.
