# WAKA — parcours d’une réservation partagée

03.02 v1 — 10 octobre 2026. **Parcours accepté pour la poursuite**, par le « continuons » reçu après son bilan. Son exécution avait été autorisée par le précédent « continuons », après livraison de 03.01. Ce document précise les exigences du vrai projet ; les cas chiffrés sont des cas de préparation, sans personne réelle ni transaction exécutée.

## S01 — But et acteurs

Suivre une réservation depuis le choix du parcours jusqu’à sa fin et au traitement des sommes dues. Le client achète des places pour son départ et sa destination ; il n’achète pas toutes les places du véhicule. Une réservation passager et le trajet du chauffeur sont deux engagements distincts.

| Acteur | Responsabilité dans ce parcours |
|---|---|
| Client payeur | Choisit les points et les places, accepte le prix présenté, paie, vérifie le véhicule et suit sa réservation |
| Chauffeur | Reçoit une proposition compatible, accepte ou refuse, assure la prise en charge et le transport, déclare les événements |
| Serveur WAKA | Vérifie les règles, décide la capacité et les échéances, conserve l’état fiable et les montants, contrôle les confirmations |
| Prestataire Mobile Money | Confirme ou invalide les opérations selon le contrat qui reste à obtenir ; une page de téléphone ne le remplace pas |
| Assistance | Traite les exceptions, recueille les déclarations et motive les décisions ; aucun incident contesté n’est résolu par le GPS seul |

L’application affiche et transmet des actions. Le serveur conserve la référence fiable. Le prestataire établit le résultat financier externe. La conception des écrans, données, états complets et échanges techniques reste dans les points 03.05 à 03.08.

## S02 — Deux cas de référence

Le départ est A et la destination D, deux points abstraits. La distance routière de devis vaut **7 200 mètres**, dans la tranche `[7 000 ; 7 800[`, donc **p = 600 F par place**. Le véhicule est approuvé pour cinq passagers hors chauffeur et dispose des places nécessaires sur chacun des segments concernés. Il n’a aucun engagement VIP exclusif concurrent. Ces conditions sont des hypothèses des cas, pas la preuve d’un véhicule disponible.

Les deux parrains sont supposés éligibles à la fin de la prestation. Leurs parts sont déjà comprises dans la commission. Si l’un n’est pas éligible, sa part reste à WAKA ; le prix client et le net chauffeur ne changent pas. Le statut taxi ou particulier ne change aucune ligne ci-dessous ; l’abonnement particulier est distinct.

| Cas | Places | Distance m | p | P client | C commission | D chauffeur | Bclient | Bchauffeur | W avant coûts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A — une place | 1 | 7 200 | 600 | 600 | 60 | 540 | 10 | 5 | 45 |
| B — trois places | 3 | 7 200 | 600 | 1 800 | 180 | 1 620 | 30 | 15 | 135 |

Les montants du tableau sont ceux d’une prestation payée, effectivement terminée et contrôlée. Ils ne sont pas des gains disponibles au moment du devis. `W` est le reste avant les coûts, pas un bénéfice. Le calcul exact et l’arrondi final sont ceux de R03 ; ne pas multiplier une commission unitaire déjà arrondie pour une réservation de groupe.

## S03 — Conditions et paramètres utilisés

Avant de proposer un chauffeur, le serveur vérifie son approbation, les pièces essentielles valides, le véhicule inscrit, la disponibilité, l’accès particulier valide ou provisoire prévu par R06, et la compatibilité du parcours. Le taxi approuvé n’a pas d’abonnement WAKA. Le parcours d’admission et de renouvellement sera détaillé à 03.04.

La capacité offerte ne dépasse ni cinq passagers hors chauffeur ni les places autorisées du véhicule. Chaque segment de la réservation doit disposer de la quantité demandée, en comptant les réservations engagées et les retenues de paiement encore actives. Une proposition reçue ne garantit pas une place : la capacité est revérifiée à l’acceptation. Deux réponses concurrentes ne peuvent pas engager la même dernière place.

| Paramètre du pilote | Valeur | Conséquence dans ce parcours |
|---|---:|---|
| Validité du devis | 5 minutes | Après expiration, nouveau devis présenté et accepté ; aucun changement de prix silencieux |
| Durée maximale de recherche | 2 minutes | Refus et réponses expirées restent dans cette même fenêtre |
| Réponse à une proposition chauffeur | 30 secondes | N’allonge ni la recherche ni la validité du devis |
| Retenue après acceptation chauffeur | 3 minutes | Délai distinct pour le paiement, compté depuis l’acceptation valide |
| Fraîcheur maximale de position chauffeur | 60 secondes | Au-delà, pas de nouvelle proposition jusqu’à mise à jour |
| Prise en charge estimée visée | 10 minutes au plus | Estimation routière sur les axes actifs, à confronter au terrain |
| Détour ajouté à une réservation existante | min(10 minutes ; 25 % de sa durée de référence) | Rejeter une insertion qui dépasse ce plafond estimé ; ne pas augmenter son prix |
| Contact et attente au rendez-vous | 5 minutes | Après notification d’arrivée au point convenu, avant qualification d’une absence |

L’acceptation doit être reçue avant l’expiration du devis **et** dans les deux minutes de recherche. Les trente secondes d’une proposition restent incluses dans ces contraintes. Les échéances affichées sont celles du serveur ; une horloge de téléphone ou une réponse hors ligne ne les prolonge pas. Ces valeurs sont des paramètres de pilote versionnés, pas des garanties de temps réel.

## S04 — Cas A : une place, déroulement normal

Le cas suppose que les contrôles réussissent, que le chauffeur répond à temps et que la confirmation de paiement arrive pendant la retenue. Le tableau décrit le comportement à construire ; aucune course n’a réellement été effectuée.

| Point | Client | Chauffeur | Serveur WAKA | Résultat attendu |
|---|---|---|---|---|
| P01 — Départ et destination | Choisit A et D ; corrige la position détectée si nécessaire | Aucune action sur cette demande | Reçoit les points explicitement choisis ; ne remplace pas un point corrigé par le GPS | Points de rendez-vous et de descente compréhensibles |
| P02 — Quantité | Choisit partagé, une place | Aucun engagement nouveau | Contrôle la quantité et la distance routière de référence | Demande pour une place sur le parcours A–D |
| P03 — Devis | Lit 7 200 m, une place, total 600 F, validité et règles d’annulation | Aucun engagement nouveau | Calcule le devis avec les règles et versions applicables ; conserve ses paramètres | Prix total explicite, avant recherche et paiement |
| P04 — Recherche | Confirme le devis présenté et demande un chauffeur | Reste disponible selon ses engagements | Ouvre la recherche limitée à deux minutes, dans la validité du devis | Recherche en cours ; aucun chauffeur ni paiement confirmés |
| P05 — Candidats compatibles | Suit la recherche | Met sa disponibilité et sa position à jour | Vérifie S03 ; compare les temps routiers, segments et détours, sans prendre la proximité à vol d’oiseau comme preuve | Proposition uniquement à un chauffeur recevable |
| P06 — Proposition | Attend une réponse fiable | Voit montée, descente, une place, montant de réservation et contraintes ; répond sous trente secondes | Enregistre la réponse ; refus ou expiration permettent de poursuivre dans la fenêtre restante | Proposition distincte d’une réservation confirmée |
| P07 — Acceptation | Reçoit le chauffeur retenu, les informations utiles et l’échéance de paiement | Accepte ; attend la confirmation de paiement | Revérifie délais et capacité, fige le prix de 600 F, retient une place sur les segments et ouvre trois minutes distinctes | Une réservation en attente de paiement ; aucun surbooking ni second chauffeur accepté |
| P08 — Paiement demandé | Suit l’instruction Mobile Money liée à cette réservation, pour 600 F | Ne considère pas une capture client comme preuve | Identifie la demande de paiement, la réservation et son échéance ; transmet selon le futur contrat | Paiement demandé, résultat encore à établir |
| P09 — Paiement confirmé | Voit la confirmation et les informations de prise en charge | Reçoit la réservation confirmée | Vérifie l’authenticité selon le contrat, opérateur, référence, XAF, montant 600 F et statut ; confirme une seule fois pendant la retenue active | La place retenue devient une place réservée ; paiement et réservation concordants |
| P10 — Prise en charge | Suit les informations de rendez-vous ; peut contacter par le canal réellement disponible | Se rend au point convenu et notifie l’arrivée sans manipuler son téléphone en conduisant | Conserve les événements ; un détour dû à un autre passager n’augmente pas les 600 F | Rendez-vous traçable, prix inchangé |
| P11 — Embarquement | Vérifie chauffeur, plaque et réservation ; présente le code temporaire | Vérifie la réservation et le code ; signale une anomalie | Confirme l’embarquement à partir de la demande validée ; exception manuelle tracée si nécessaire | Transport commencé pour cette réservation, sans validation par GPS seul |
| P12 — Transport | Se rend à D selon le parcours convenu ; signale un problème si nécessaire | Transporte les passagers selon les engagements compatibles | Suit la réservation sans réécrire son prix ; conserve les événements utiles | La réservation d’une place peut coexister avec d’autres réservations partagées compatibles |
| P13 — Fin à destination | Voit la fin déclarée ; peut signaler une contestation | Déclare l’arrivée et la fin de cette prestation | Enregistre la fin effective, traite les contrôles et contestations ; libère la place pour les segments futurs admissibles | La réservation A–D se termine, même si le trajet chauffeur continue |
| P14 — Sommes dues | Retrouve le prix et l’historique | Voit 540 F dus avant abonnement, après contrôles | Rapproche paiement et prestation ; établit C = 60 F, bonus éligibles 10/5 F et W = 45 F | Gains dus ou disponibles selon les contrôles ; aucun versement externe présumé |
| P15 — Versements et clôture financière | Suit une éventuelle décision d’incident dans son historique | Suit son versement propre à cette réservation | Déclenche et rapproche les versements selon le contrat ; conserve référence et résultat ; réessaie seulement après identification du résultat précédent | Versement externe confirmé distinct de la fin de prestation et de l’ouverture des gains |

L’éligibilité des parrains est contrôlée à la fin de la réservation payée, dans la période de douze mois calendaires de R05. Un changement de rôle ne recrée pas cette période ; une fin exactement à l’expiration exclut le nouveau bonus. Aucun bonus n’est acquis pour une inscription, une course gratuite ou une réservation entièrement annulée. Le seuil de retrait de 1 000 F concerne les gains de parrainage, pas le versement de 540 F du chauffeur.

Le payeur voit le prix total et les règles, sans supplément destiné à financer une deuxième fois la commission. Le détail des interfaces et du montant à afficher au chauffeur sera arrêté à 03.05 à partir de ces montants et de ses besoins réels.

## S05 — Cas B : trois places dans une même réservation

Le client payeur réserve pour trois personnes, sur les mêmes points A–D. Les trois places sont payées par ce compte dans un seul engagement. Ce cas suit **P01 à P15** ; les changements sont les suivants.

| Points du cas A | Différence pour trois places | Ce qui doit être vérifié |
|---|---|---|
| P02–P03 | Quantité 3 ; 7 200 m ; p = 600 F ; prix total 1 800 F | La quantité et le prix total sont annoncés avant confirmation du devis |
| P05–P07 | La proposition puis la retenue concernent trois places sur chaque segment parcouru | Trois places disponibles partout ; aucune validation de deux places présentée comme une réservation de trois |
| P08–P09 | Une demande de paiement de 1 800 F pour cette réservation | Aucun débit de 600 F présenté comme couvrant les trois places ; mêmes contrôles prestataire et même délai |
| P10–P12 | Le chauffeur attend le groupe correspondant à cette réservation | Le nombre de personnes respecte les trois places payées et la capacité du véhicule ; ne pas transformer une quantité différente en engagement silencieux |
| P13 | La fin de l’engagement A–D libère ses trois places pour les segments futurs admissibles | Le trajet chauffeur et les autres réservations conservent leurs états propres |
| P14–P15 | D = 1 620 F ; C = 180 F ; bonus 30/15 F ; W = 135 F | Une répartition par réservation ; bonus client lié au compte payeur, sans inventer trois comptes parrainés |

Les règles de paiement incertain, d’annulation et d’interruption s’appliquent au montant de **1 800 F**. Le parcours ne crée pas de fonctionnalité de changement partiel des membres ou de réduction de quantité après paiement ; un tel besoin devra être décidé et versionné dans un point dédié avant d’être codé.

### Capacité par segment : exemple de refus

Un parcours A–D peut traverser A–B, B–C et C–D. Après prise en compte des engagements et retenues actives, les places disponibles sont celles-ci :

| Segment | Places disponibles | Demande 1 place | Demande 3 places |
|---|---:|---|---|
| A–B | 3 | Compatible | Compatible |
| B–C | 2 | Compatible | **Incompatible** |
| C–D | 4 | Compatible | Compatible |

Le chauffeur est recevable pour une place, mais pas pour trois sur A–D. Additionner les places disponibles entre segments donnerait une réponse incorrecte. La recherche de trois places continue vers un autre candidat recevable, si les délais le permettent. Le serveur ne sépare pas automatiquement le groupe entre véhicules et ne change pas la quantité ou le prix acceptés.

## S06 — Délais : exemple chronologique

Les heures suivantes illustrent des écarts relatifs sur l’horloge serveur ; ce ne sont pas des horodatages d’une opération réelle.

| Événement | Heure illustrative | Conséquence |
|---|---|---|
| Devis produit | 12:00:00 | Expire à 12:05:00 |
| Recherche ouverte | 12:03:30 | Limite de recherche à 12:05:30, sans prolonger le devis |
| Proposition chauffeur | 12:04:20 | Réponse sous trente secondes, avant 12:04:50 dans cet exemple |
| Acceptation valide reçue | 12:04:40 | Avant les deux limites ; prix figé et retenue ouverte |
| Échéance de paiement | 12:07:40 | Trois minutes depuis l’acceptation ; ne s’arrête pas à 12:05:00 |
| Confirmation serveur du paiement | 12:06:00 | Peut confirmer cette réservation si la retenue est encore active et les contrôles réussissent |

Une acceptation reçue à **12:05:10** serait refusée, bien que la recherche puisse nominalement aller jusqu’à 12:05:30 : le devis est déjà expiré. Une proposition ne peut pas promettre ses trente secondes entières au-delà d’une limite plus proche. Un paiement après expiration effective de la retenue suit E07 ci-dessous ; il ne restaure pas la capacité.

Inversement, si la recherche du même devis avait commencé à **12:00:30**, sa limite serait **12:02:30**. Une acceptation à **12:02:40** serait refusée malgré un devis encore valable jusqu’à 12:05:00 : les deux minutes de recherche sont dépassées.

La condition d’expiration de retenue et les courses d’événements à la même échéance devront être définies précisément à 03.07 et vérifiées lors de l’intégration. La présente chronologie n’invente pas un comportement prestataire à la milliseconde.

## S07 — Échecs, incertitudes et reprises

Chaque ligne s’applique aux deux quantités. « Inconnu » veut dire que le résultat n’est pas établi ; cela ne veut dire ni « payé » ni « échoué ». Le dernier état connu et son heure sont visibles quand l’actualité de l’information n’est pas garantie.

| Cas | Information et action pour les participants | Traitement attendu côté WAKA |
|---|---|---|
| E01 — Position ou calcul de route indisponible | Le client peut corriger les points ; aucune distance ni aucun prix routier n’est inventé | Relire la demande existante ; demander une correction ou une reprise lorsque le calcul fiable redevient disponible |
| E02 — Aucun candidat recevable | Afficher l’indisponibilité pour cette demande ; proposer une nouvelle recherche explicite | Respecter capacité, accès, exclusivité, GPS récent et détour ; ne pas attribuer un véhicule inéligible pour remplir la demande |
| E03 — Chauffeur refuse ou ne répond pas | La recherche peut continuer ; aucune confirmation fausse n’est affichée | Une autre proposition reste dans les délais initiaux ; aucune boucle de trente secondes ne remet le compteur à zéro |
| E04 — Devis ou recherche expirés | Afficher l’expiration ; un nouveau devis est présenté si nécessaire puis accepté | Refuser une acceptation tardive ; ne pas engager au prix d’une nouvelle grille sans accord client |
| E05 — Capacité prise entre proposition et acceptation | Le client ne reçoit pas une réservation dont les places ne sont plus disponibles | Revérifier et engager la capacité une seule fois ; poursuivre la recherche seulement si les délais permettent une autre proposition |
| E06 — Paiement refusé ou confirmé en échec | Afficher le résultat fiable ; ne pas dire que l’argent a été encaissé | Une reprise éventuelle exige le résultat identifié et le respect de la retenue ; sans succès confirmé à temps, pas de réservation définitive |
| E07 — Paiement inconnu ou tardif | Afficher « vérification en cours » avec l’état et son heure ; ne pas inviter à payer une deuxième fois | À expiration effective, libérer les places tout en suivant l’opération financière ; un succès tardif identifié déclenche résolution et remboursement suivis, sans ressusciter la réservation |
| E08 — Notifications répétées ou incohérentes | Ne pas afficher plusieurs confirmations pour la même opération | Une notification répétée ne crée ni seconde réservation ni deuxième écriture ; montant, devise ou référence incohérents restent à vérifier, sans gains disponibles supposés |
| E09 — Réseau perdu ou réponse WAKA perdue | Afficher le dernier état connu ; le téléphone ne confirme pas localement une réservation, un embarquement ou une annulation | À la reconnexion, relire l’état existant et renvoyer seulement la même demande identifiée ; aucune nouvelle réservation ou instruction de débit par simple répétition |
| E10 — Annulation avant embarquement | Client et chauffeur voient l’annulation ; le client suit le remboursement de tout encaissement confirmé | Libérer l’engagement, enregistrer l’annulation, appliquer S08 ; aucun bonus acquis ni pénalité client |
| E11 — Absence ou véhicule non conforme | Vérifier l’arrivée au point convenu et documenter les démarches ; signaler une plaque, un chauffeur ou un véhicule discordant | Cinq minutes de contact et d’attente avant qualification d’une absence ; avant embarquement, chauffeur absent ou véhicule non conforme : remboursement intégral et examen du dossier ; après embarquement, sécuriser et traiter l’incident selon E14 et S09 ; pas d’amende GPS automatique |
| E12 — Code ou téléphone indisponible | Recourir à l’assistance ; une image GPS ne remplace pas une preuve d’embarquement | Alternative manuelle tracée, déclarations des parties et décision motivée ; aucune validation hors ligne présumée |
| E13 — Destination modifiée ou détour | Un détour imposé ne majore pas le prix ; une nouvelle destination demandée exige un nouveau devis accepté | Conserver la destination contractuelle en l’absence d’un nouvel accord, ou terminer avec assistance ; pas de péage ni supplément silencieux |
| E14 — Interruption ou fin contestée | Sécuriser l’arrêt si nécessaire ; déclarer l’incident et consulter la décision motivée | Établir le service fourni avec les arrêts documentés ; appliquer S09 ; suspendre seulement le versement concerné en cas de contestation |
| E15 — Versement ou remboursement incertain | Le bénéficiaire voit une opération en suivi, pas un versement ou remboursement fictivement terminé | Vérifier le résultat précédent avant toute reprise ; conserver écritures et références, même si le chauffeur a déjà reçu ses fonds ; confirmation externe nécessaire |

En cas de danger, le parcours oriente vers un moyen d’assistance réellement tenu et les services compétents dont les coordonnées auront été qualifiées avant publication. Le document ne promet ni permanence permanente ni intervention immédiate. Les canaux de contact et leur disponibilité sont à qualifier au point 02.11 ; aucun appel masqué disponible n’est présumé.

## S08 — Annulation avant embarquement

Si l’encaissement n’est pas confirmé, ne pas annoncer un remboursement d’argent supposément encaissé : suivre d’abord le résultat. Si le paiement est confirmé et la réservation annulée avant embarquement, le remboursement porte sur tout le prix client, sans pénalité. La décision, la demande de remboursement et sa confirmation prestataire sont distinctes.

| Cas annulé | P initial | Remboursement confirmé | Prix conservé | Commission conservée | Chauffeur conservé | Bonus client | Bonus chauffeur | W conservé |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A — une place | 600 | 600 | 0 | 0 | 0 | 0 | 0 | 0 |
| B — trois places | 1 800 | 1 800 | 0 | 0 | 0 | 0 | 0 | 0 |

Ces lignes montrent le résultat après confirmation du remboursement intégral. Les frais prestataire non récupérés restent un coût WAKA. Aucune compensation chauffeur n’est prélevée sur le remboursement client ; une aide éventuelle nécessite un budget et une décision séparés. Annulations ou absences répétées : revue humaine avec contestation possible, sans débit improvisé.

## S09 — Interruption : exemple à 50 %

L’assistance a établi et les parties ont accepté une prestation de moitié sur l’itinéraire routier de référence entre les arrêts documentés. **50 % est l’hypothèse de cet exemple**, pas une règle décidée par la moitié du temps ou par le GPS seul. Le prix conservé est arrondi ; le remboursement est le prix initial moins ce prix conservé.

Après confirmation du remboursement, les parts sont recalculées depuis les montants initiaux, avec le cumul des remboursements confirmés, selon R09. Les gains contestés ne sont pas versés automatiquement. Une décision de remboursement sans confirmation externe ne prouve pas le résultat du tableau.

| Cas interrompu | P initial | Rtotal confirmé | T conservé | Cret | Dret | Bclient_ret | Bchauffeur_ret | Wret |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A — une place | 600 | 300 | 300 | 30 | 270 | 5 | 3 | 22 |
| B — trois places | 1 800 | 900 | 900 | 90 | 810 | 15 | 8 | 67 |

À une place, le bonus chauffeur de 5 F × 50 % vaut 2,50 F et devient **3 F**. WAKA conserve alors **22 F** par différence, pas la moitié arrondie de 45 F. À trois places, 15 F × 50 % devient **8 F**, puis WAKA conserve **67 F**. Chaque montant final est arrondi une seule fois, moitié vers le haut ; les soldes d’équilibrage sont des différences.

Reprises sur les parts initiales :

- Cas A : chauffeur 270 F + parrain client 5 F + parrain chauffeur 2 F + WAKA 23 F = **300 F** remboursés.
- Cas B : chauffeur 810 F + parrain client 15 F + parrain chauffeur 7 F + WAKA 68 F = **900 F** remboursés.

Si un autre remboursement est confirmé ensuite, repartir des montants initiaux avec le nouveau Rtotal. Ne pas arrondir séparément chaque remboursement successif. Un versement déjà exécuté n’efface pas une reprise : WAKA suit le remboursement client et la récupération contractuelle séparément, avec historique et revue humaine.

## S10 — Informations à retrouver lors d’une reprise

Sans arrêter ici un schéma de base de données, le parcours doit permettre de retrouver : la même demande et réservation, points et places, distance et prix accepté, versions, chauffeur et véhicule engagés, échéances serveur, paiement identifié et statut externe, événements d’embarquement et de fin, montants initiaux et décisions d’incident, remboursements confirmés cumulés et versements.

Pour une réponse perdue à P07, relire d’abord si la réservation et sa retenue existent ; ne pas chercher et engager un second chauffeur automatiquement. Pour une réponse perdue à P09, vérifier l’opération de 600 F ou 1 800 F existante ; ne pas recréer un paiement. Pour une réponse perdue à P13, retrouver la fin enregistrée avant de réémettre la même demande. Aucun téléphone ne recrée les gains en rejouant ses actions.

Les preuves, droits d’accès, durées de conservation et contrats techniques seront définis dans les points correspondants. Ce document ne contient ni localisation d’une personne réelle, ni justificatif, ni numéro de paiement, ni valeur secrète.

## S11 — Référence, vérification et suite

Les sources de ce parcours sont :

- [Règles R01–R04 : modes, capacité, prix et distance](WAKA_regles_adoptees.md).
- [Règles R05–R10 : parrains, accès, paiement, remboursements et délais](WAKA_regles_adoptees.md).
- Dossier §4 : demande, compatibilité, embarquement et annulations (reference detaillee conservee hors du depot).
- Dossier §6.6–6.7 : remboursements et confirmations (reference detaillee conservee hors du depot).
- Dossier §7.4 : réseau perdu et procédures d’incident (reference detaillee conservee hors du depot).

La revue vérifie P01–P15 pour une place, les adaptations pour trois places, les E01–E15, les délais, le refus par segment et les trois tableaux financiers. Les contrôles documentaires reproductibles et les observations indépendantes figurent dans le bilan 03.02 ; aucun test Mobile Money ou applicatif n’est annoncé.

**Coût de cette étape : 0 F.** Aucun achat, service facturable, compte prestataire, appel, SMS ou installation supplémentaire. Les frais réels d’exploitation restent ceux à qualifier dans le budget et les étapes prévues.

La revue proposée à la livraison portait sur S04, les différences S05, le paiement inconnu E07 et les arrondis S09. **La suite proposée était 03.03, parcours VIP.** Son exécution a été autorisée ensuite par le dernier « continuons » ; le suivi et le bilan de ce point indiquent son état actuel.
