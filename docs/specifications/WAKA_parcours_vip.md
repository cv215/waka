# WAKA — parcours VIP et exclusivité du véhicule

03.03 v1 — 10 octobre 2026. **Parcours accepté pour la poursuite**, par « continu » après son bilan. Son exécution avait été autorisée par le précédent « continuons », après livraison de 03.02. Ce document décrit le comportement à construire dans le vrai projet ; ses véhicules, points et montants sont des cas de préparation, sans personne réelle ni opération exécutée.

## T01 — Ce que le client réserve

Le client réserve **le véhicule entier pour un parcours exclusif**, de son départ à sa destination. Ses accompagnants font partie de la même réservation. Aucun passager provenant d’une autre réservation n’est ajouté pendant ce parcours, même si des sièges sont inoccupés. Une seule personne paye la réservation ; son parrain éventuel reçoit la part client.

Le VIP et le partagé existent dès le lancement. VIP ne désigne pas une gamme de confort ; aucune catégorie ou majoration de confort n’est créée. Taxi et particulier approuvés gardent le même prix et la même commission. L’abonnement particulier reste distinct du montant de la réservation et du net chauffeur.

Le maximum est **cinq passagers hors chauffeur**, limité par la capacité passagers autorisée du véhicule. Le nombre de passagers annoncé sert à vérifier cette capacité ; il ne multiplie ni ne divise le prix VIP. Une réservation couvre de une à cinq personnes dans cette limite réelle.

## T02 — Prix et parts : cas à p = 600 F

Le départ A et la destination D sont abstraits. La distance routière de référence est 7 200 m, dans la tranche `[7 000 ; 7 800[`, soit p = 600 F. Le véhicule du cas est approuvé pour cinq passagers hors chauffeur, sans engagement concurrent. Les deux parrains sont supposés éligibles à la fin de la prestation payée et contrôlée.

Le calcul conserve les intermédiaires exacts puis arrondit chaque montant final au franc, moitié vers le haut :

```text
P = Arrondi(5 × 2,5 × p) = Arrondi(12,5 × p)
C = Arrondi(12,5 × (p + 300) / 15)
D = P − C
Bclient = 100 F si le parrain du payeur est éligible ; sinon 0
Bchauffeur = 50 F si le parrain du chauffeur est éligible ; sinon 0
W = C − Bclient − Bchauffeur
```

| Passagers du même VIP | p | P client | C commission | D chauffeur | Bclient | Bchauffeur | W avant coûts |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 600 | 7 500 | 750 | 6 750 | 100 | 50 | 600 |
| 3 | 600 | 7 500 | 750 | 6 750 | 100 | 50 | 600 |
| 5 | 600 | 7 500 | 750 | 6 750 | 100 | 50 | 600 |

**7 500 F est le total du véhicule**, et non une somme à payer par chacun des passagers. Le coefficient de bonus reste 10, donc 100/50 F ; il ne devient ni le nombre d’occupants ni 12,5. La commission de 750 F comprend déjà ces bonus. Aucun supplément client ni deuxième prélèvement chauffeur ne les finance.

Les 6 750 F sont dus au chauffeur avant abonnement et selon les contrôles de fin. W est le reste WAKA avant ses autres coûts, pas un bénéfice. À une autre tranche de distance, le prix VIP change selon p ; 7 500 F n’est pas un forfait pour toute distance.

| Parrains éligibles à la fin | Bclient | Bchauffeur | W avant coûts |
|---|---:|---:|---:|
| Les deux | 100 | 50 | 600 |
| Payeur seul | 100 | 0 | 650 |
| Chauffeur seul | 0 | 50 | 700 |
| Aucun | 0 | 0 | 750 |

P, C et D restent identiques dans ces quatre cas. L’éligibilité est celle de R05 : période unique de douze mois calendaires depuis la première réservation du filleul payée et terminée, sans redémarrage lors d’un changement de rôle. Une fin exactement à l’expiration exclut le nouveau bonus. L’achat d’abonnement, l’inscription, l’annulation intégrale et une course gratuite n’ouvrent pas un bonus acquis.

## T03 — Capacité réelle et disponibilité

Le serveur vérifie les conditions d’admission et de disponibilité communes à S03 du parcours partagé : chauffeur et véhicule approuvés, pièces essentielles valides, accès particulier applicable, position reçue depuis au plus soixante secondes, trajet et rendez-vous recevables. Le pilote vise une prise en charge routière estimée à dix minutes au plus sur les axes actifs ; ce paramètre reste à confronter au terrain. Aucune réservation n’est attribuée à un chauffeur inéligible pour contourner une absence d’offre.

Au pilote, la règle est stricte : **aucune réservation partagée active ni retenue concurrente ne doit engager le véhicule** pour accepter le VIP. Le serveur ne se contente pas de compter les sièges libres et ne supprime aucun engagement existant. Des engagements successifs compatibles pourront être étudiés ultérieurement ; ils ne sont pas autorisés par ce document.

Les exemples suivants supposent que la capacité totale autorisée inclut le chauffeur et que les autres conditions sont réunies. La capacité WAKA est min(5 ; capacité totale − 1).

| Cas de capacité | Capacité totale autorisée | Passagers annoncés | Maximum WAKA hors chauffeur | Recevabilité | Prix VIP si recevable |
|---|---:|---:|---:|---|---:|
| L01 | 6 | 1 | 5 | Recevable | 7 500 |
| L02 | 6 | 3 | 5 | Recevable | 7 500 |
| L03 | 6 | 5 | 5 | Recevable | 7 500 |
| L04 | 5 | 4 | 4 | Recevable | 7 500 |
| L05 | 5 | 5 | 4 | Refusé | — |
| L06 | 7 | 6 | 5 | Refusé | — |
| L07 | 6 | 0 | 5 | Refusé | — |

Une capacité inférieure ne réduit pas le coefficient 12,5 de prix ; elle réduit le nombre admissible de passagers. Une capacité totale de cinq personnes autorise ici quatre passagers avec le chauffeur, pas cinq. Une demande excessive est corrigée explicitement ou refusée ; WAKA ne transforme pas automatiquement la quantité ni ne répartit le groupe entre véhicules.

## T04 — Parcours normal, client, chauffeur et serveur

Le cas reprend A–D, p = 600 F, trois passagers autorisés et un véhicule disponible. Les rôles du prestataire et de l’assistance restent ceux de S01 du parcours partagé. Les contrôles supposés réussir ci-dessous sont des exigences ; aucune réservation réelle n’a été effectuée.

| Point | Client payeur | Chauffeur | Serveur WAKA | Résultat attendu |
|---|---|---|---|---|
| V01 — Besoin et mode | Choisit A, D, VIP et trois passagers ; corrige les points détectés si nécessaire | N’a encore aucun nouvel engagement | Contrôle points, mode et quantité dans la capacité autorisée | Une demande de véhicule exclusif, distincte d’une demande de trois places partagées |
| V02 — Devis | Lit 7 200 m, trois passagers, véhicule exclusif, total 7 500 F, validité et annulation | N’a encore aucun nouvel engagement | Calcule P/C/D selon T02 avec les versions applicables ; conserve le devis | Total véhicule explicite ; prix indépendant des occupants |
| V03 — Recherche | Confirme le devis présenté et lance la recherche | Met disponibilité et position à jour | Ouvre deux minutes de recherche dans la validité du devis ; vérifie T03 et les engagements du véhicule | Candidats admissibles à l’exclusivité entière |
| V04 — Proposition | Attend un état fiable | Voit montée, descente, trois passagers, mode VIP, prix et net associé ; accepte ou refuse sous trente secondes | Une proposition n’engage pas encore le véhicule ; refus ou expiration permettent la recherche dans le délai restant | Proposition distincte d’un verrou ou d’un paiement confirmé |
| V05 — Acceptation valide | Reçoit le chauffeur retenu et l’échéance de paiement | Accepte ; attend la confirmation financière | Revérifie délais, quantité et absence de conflit ; fige 7 500 F et retient l’exclusivité pour trois minutes distinctes | Tout le véhicule est retenu ; aucun engagement concurrent ne peut être ajouté |
| V06 — Paiement demandé | Suit l’instruction Mobile Money liée à cette réservation pour 7 500 F | Ne prend ni capture ni parole client pour preuve | Identifie une demande de paiement liée à cette réservation et à sa retenue, selon le futur contrat | Paiement demandé, résultat encore à établir |
| V07 — Paiement confirmé | Voit confirmation et rendez-vous | Reçoit le VIP confirmé | Contrôle authenticité selon contrat, opérateur, référence, XAF, montant 7 500 F et statut ; confirme une seule fois sous retenue active | La retenue devient réservation exclusive confirmée ; aucun paiement doublé |
| V08 — Rendez-vous et embarquement | Vérifie chauffeur, plaque et réservation ; présente le code temporaire pour son groupe | Notifie l’arrivée au point convenu ; vérifie la réservation et le code sans manipuler son téléphone en conduisant | Enregistre les événements ; prévoit cinq minutes de contact et d’attente avant qualification d’une absence, et une alternative manuelle tracée si nécessaire | Embarquement confirmé par l’état fiable, sans preuve GPS seule |
| V09 — Parcours exclusif | Voyage avec ses accompagnants ; signale un problème | Assure A–D sans prendre d’autre réservation, même avec des sièges libres | Maintient l’exclusivité et le prix accepté ; une nouvelle destination exige un nouveau devis accepté | Aucun détour imposé par l’ajout d’un autre client, aucun supplément silencieux |
| V10 — Fin de prestation | Voit la fin déclarée et peut signaler une contestation | Déclare la fin à D | Enregistre la fin effective et traite les contrôles ; libère l’exclusivité une seule fois sans libérer un engagement encore actif | La réservation VIP se termine ; la fin du trajet chauffeur et le versement restent distincts |
| V11 — Sommes dues | Retrouve le total et l’historique | Voit 6 750 F dus avant abonnement après contrôles | Rapproche paiement et prestation ; éligibilité des parrains à la fin ; établit commission 750 F, bonus éventuels 100/50 F et reste 600 F si tous deux éligibles | Gains dus ou disponibles selon contrôles ; paiement seul ou embarquement seul insuffisants |
| V12 — Versements | Suit une éventuelle décision d’incident | Suit le versement propre à ce VIP | Déclenche et rapproche les versements selon le contrat ; conserve références et résultat, vérifie avant reprise | Versement externe confirmé séparément ; aucun transfert fictivement réussi |

Le seuil de retrait des gains de parrainage est de 1 000 F ; il n’est pas une condition de paiement des 6 750 F chauffeur. Une personne ayant légitimement parrainé payeur et chauffeur peut recevoir les deux parts ; les contrôles de fraude et la possibilité de contestation de R05 restent applicables.

## T05 — Conflits à refuser ou conditions à revérifier

« Disponible » signifie ici que toutes les conditions T03 et les délais sont aussi satisfaits. Les situations sont des cas de revue, pas des tests de concurrence déjà exécutés.

| Cas | Situation du véhicule | Décision attendue |
|---|---|---|
| K01 | Aucun engagement actif ni retenue, capacité suffisante | Peut être proposé ; disponibilité revérifiée avant acceptation |
| K02 | Réservation partagée confirmée, pas encore embarquée | Refuser ce véhicule pour le VIP ; ne pas annuler ou déplacer le partagé pour vendre le VIP |
| K03 | Réservation partagée embarquée | Refuser le VIP, même s’il reste quatre sièges libres |
| K04 | Une seule place partagée retenue pendant son paiement | Refuser le VIP tant que cette retenue active engage le véhicule |
| K05 | Autre VIP en retenue de paiement | Refuser une nouvelle retenue VIP ou partagée sur ce véhicule |
| K06 | VIP confirmé ou en cours avec un seul passager | Refuser tout autre engagement concurrent ; les sièges inutilisés restent exclusifs |
| K07 | Deux demandes VIP concurrentes sur un véhicule initialement libre | Au plus une acceptation peut retenir l’exclusivité ; l’autre ne peut engager ce véhicule et poursuit seulement si les délais permettent un autre candidat |
| K08 | Acceptations partagée et VIP concurrentes sur un véhicule initialement libre | Ne jamais créer les deux engagements ; revérification et décision côté serveur, sans effacer celui déjà engagé |
| K09 | Retenue expirée et libérée, paiement de l’ancien VIP encore inconnu | Peut redevenir recevable seulement après vérification de la libération et de l’absence de tout autre engagement ; l’ancien paiement reste suivi et ne restaure pas l’exclusivité |
| K10 | Ancien VIP réellement terminé ou annulé, exclusivité libérée | Revérifier la disponibilité actuelle ; aucune nouvelle retenue avant la libération fiable ni suppression du suivi financier de l’ancien VIP |

Le contrôle vaut à la proposition puis à l’acceptation. Il protège dans les deux sens : une retenue VIP interdit la vente d’une place partagée, et une retenue partagée interdit le VIP au pilote. Le téléphone ne décide pas de la libération. L’ordre technique des opérations et leur protection en base seront réalisés à l’objectif 10 ; aucune priorité commerciale entre demandes simultanées n’est inventée ici.

## T06 — Délais et reprises communs au partagé

Les mêmes paramètres s’appliquent : devis cinq minutes, recherche deux minutes et réponse chauffeur trente secondes. L’acceptation doit arriver avant expiration du devis et dans la fenêtre de recherche ; elle ouvre ensuite **trois minutes distinctes de paiement**, sans consommer le temps restant du devis. Les échéances viennent du serveur. La chronologie et ses deux contre-exemples sont conservés dans S06 du parcours partagé, sans leur ajouter un délai spécial VIP.

| Incident VIP | Information pour les participants | Traitement et exclusivité |
|---|---|---|
| J01 — Refus ou absence de réponse chauffeur | Recherche en cours puis indisponibilité ou expiration si aucun candidat | Poursuivre seulement dans les fenêtres restantes ; une proposition non acceptée n’est pas une retenue à supprimer |
| J02 — Devis, recherche ou retenue expirés | Afficher l’état fiable ; nouveau devis présenté si nécessaire | Refuser l’acceptation tardive ; expiration effective d’une retenue : libérer une seule fois, sans supprimer un autre engagement |
| J03 — Paiement inconnu ou tardif | Vérification en cours, dernier état connu et heure ; aucune invitation à payer une deuxième fois | Suivre l’opération même après libération ; succès tardif identifié : résolution et remboursement suivis, aucun ancien VIP recréé |
| J04 — Confirmation répétée ou incohérente | Pas de deuxième réservation ni de gains supposés | Vérifier référence, montant, devise, opérateur et statut ; notification répétée sans nouvelle écriture ; écart financier à rapprocher |
| J05 — Coupure à l’acceptation ou au paiement | Afficher le dernier état connu ; ne pas annoncer une nouvelle attribution ou un paiement localement confirmé | Relire la même demande, le verrou et l’opération ; renvoyer seulement la même demande identifiée, sans seconde exclusivité ni second débit |
| J06 — Annulation, absence ou véhicule non conforme avant embarquement | Annulation et remboursement de tout encaissement confirmé suivis séparément | Libérer correctement ; remboursement intégral selon T07, sans pénalité ni bonus acquis ; cinq minutes de contact avant qualification d’absence, revue humaine pour répétitions |
| J07 — Code ou téléphone indisponible, problème après embarquement | Assistance et décision motivée ; sécuriser l’arrêt si nécessaire | Alternative manuelle tracée ; incident après départ selon T08, sans validation ni remboursement automatique fondés sur le GPS seul |
| J08 — Fin, libération, versement ou remboursement dont la réponse est perdue | Dernier état fiable ; opération financière encore en suivi si résultat inconnu | Relire la fin et le verrou avant répétition ; vérifier le résultat financier précédent avant reprise ; ne pas libérer un VIP encore en cours ni effacer ses écritures |

Les autres issues E01–E15 du parcours partagé restent applicables à leur moment : route indisponible, pièce invalide, destination modifiée, contact, sécurité et incident. Un chauffeur ou véhicule non conforme découvert **après embarquement** passe par la sécurisation et l’assistance, pas par une promesse générale de remboursement intégral.

Si le groupe réel ne correspond pas à la quantité annoncée ou dépasse la capacité, le chauffeur ne doit pas dépasser les places autorisées. Le prix reste indépendant des occupants présents ; toute correction de l’engagement et toute exception seront tracées et traitées explicitement, sans créer ici une fonction d’ajout de membres après paiement.

Les moyens de contact, l’assistance et les coordonnées des services compétents restent à qualifier avant publication. Aucun appel masqué disponible, permanence continue ou intervention immédiate n’est présumé.

## T07 — Annulation avant embarquement

Le remboursement couvre tout le prix client si l’encaissement est confirmé, sans pénalité. Si le paiement est inconnu, le suivre d’abord ; ne pas annoncer de remboursement d’un encaissement supposé. Les lignes suivantes montrent le résultat **après confirmation externe** du remboursement. Les deux parrains sont ceux du cas initial ; aucun bonus ne reste acquis.

| p initial | P initial | Rtotal confirmé | T conservé | Cret | Dret | Bclient_ret | Bchauffeur_ret | Wret |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 600 | 7 500 | 7 500 | 0 | 0 | 0 | 0 | 0 | 0 |
| 205 | 2 563 | 2 563 | 0 | 0 | 0 | 0 | 0 | 0 |

Les frais prestataire non récupérés restent des coûts WAKA. Une compensation commerciale éventuelle nécessite décision et budget distincts ; elle n’est pas prise sur le remboursement client. La fin du suivi financier ne conditionne pas à elle seule la fin physique du transport ou la disponibilité du véhicule.

## T08 — Interruption et montants initiaux arrondis

L’assistance établit le service fourni à partir de l’itinéraire routier de référence entre les arrêts documentés. **Une moitié de service est l’hypothèse acceptée de ces exemples**, pas une décision automatique prise sur la moitié de la durée ou du GPS. Une part contestée suspend seulement le versement concerné jusqu’à revue humaine.

Conserver P0, C0, D0, Bclient0, Bchauffeur0 et W0 ainsi que leurs versions. Pour une part servie de 50 %, T = Arrondi(P0 × 0,5) et le remboursement cible est P0 − T. Après confirmation de ce remboursement, recalculer depuis Rtotal selon R09 : Cret = Arrondi(C0 × T / P0), bonus conservés au même ratio, puis Dret = T − Cret et Wret = Cret − bonus. Aucune part WAKA arrondie séparément.

| p initial, deux parrains | P0 | C0 | D0 | Bclient0 | Bchauffeur0 | W0 |
|---|---:|---:|---:|---:|---:|---:|
| 600 | 7 500 | 750 | 6 750 | 100 | 50 | 600 |
| 205 | 2 563 | 421 | 2 142 | 100 | 50 | 271 |

| p après 50 % de service | P0 | Rtotal confirmé | T conservé | Cret | Dret | Bclient_ret | Bchauffeur_ret | Wret |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 600 | 7 500 | 3 750 | 3 750 | 375 | 3 375 | 50 | 25 | 300 |
| 205 | 2 563 | 1 281 | 1 282 | 211 | 1 071 | 50 | 25 | 136 |

À p = 205 F, P0 × 0,5 = 1 281,50 F devient **1 282 F conservés**, donc 1 281 F remboursés. La commission conservée utilise le montant initial **421 F** : Arrondi(421 × 1 282 / 2 563) = **211 F**. Réduire de moitié la commission théorique non arrondie donnerait 210 F : ce serait une autre règle, incorrecte ici.

Reprises :

- À p = 600 F : chauffeur 3 375 F + parrain client 50 F + parrain chauffeur 25 F + WAKA 300 F = **3 750 F**.
- À p = 205 F : chauffeur 1 071 F + parrain client 50 F + parrain chauffeur 25 F + WAKA 135 F = **1 281 F**.

Un remboursement supplémentaire confirmé exige de recalculer depuis les montants initiaux et le nouveau cumul, sans arrondir séparément chaque remboursement. Un retrait antérieur n’efface pas la reprise ; remboursement client et récupération contractuelle sont suivis séparément. Une décision interne n’est ni un remboursement externe confirmé ni la preuve que l’exclusivité a été libérée.

## T09 — Référence, limites et revue

Les exigences sont fondées sur :

- [R01 : modes, capacité et exclusivité](WAKA_regles_adoptees.md).
- [R03 : prix et répartition](WAKA_regles_adoptees.md).
- [R05–R10 : parrains, accès, paiement et remboursements](WAKA_regles_adoptees.md).
- [Parcours partagé S06 : chronologie et contre-exemples](WAKA_parcours_partage.md).
- Dossier §7.2 : retenue et verrou d’exclusivité (reference detaillee conservee hors du depot).
- Catalogue objectif 10 : restriction et protection des deux modes (reference detaillee conservee hors du depot).

Le bilan 03.03 conserve les contrôles des montants, des capacités et des sources, ainsi que les conclusions de relecture. Les cas de conflits sont des exigences relues ; ils ne prouvent pas que des verrouillages réels, redémarrages ou paiements prestataires ont été exécutés.

**Coût de cette étape : 0 F.** Aucun outil payant, abonnement, compte prestataire, SMS ou transaction. Les autorisations, véhicules réels, assurances, canaux de contact et contrats restent dans leurs points de qualification. Les écrans détaillés, états, données, API et code sont dans les étapes prévues. Aucun nouveau tarif ni priorité commerciale n’est adopté ici.

La revue proposée à la livraison portait sur le prix entier T02, la capacité T03, l’exclusivité V05/K02–K08, le paiement tardif J03 et les remboursements T08. **La suite proposée était 03.04, parcours chauffeur taxi et particulier.** Son exécution a été autorisée ensuite par « continu » ; le suivi et le bilan de ce point indiquent son état actuel.
