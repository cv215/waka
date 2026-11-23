# Decisions WAKA

15 octobre 2026 : cv215/waka est la cible correcte. Les travaux precedents sont conserves hors de ce depot. Le fondateur demande autonomie des travaux gratuits et sollicitation avant depense ; explications/bilans conserves.

Cas ouverts avant fonctions dependantes : achat avant premiere prestation et coordination essai (12.04-12.05) ; premiere prestation interrompue et declencheurs essai/fenetre (12.02/15.02) ; prolongation/remboursement combines (12.07). Aucune nouvelle regle commerciale inventee.

Qualifications transport/assurances, prestataires, canal Android, conservation/hebergement et sauvegarde hors PC restent a etablir avant leurs usages reels. Le depot public ne contient ni secret ni dossier personnel reel.

Architecture : serveur Spring Boot modulaire, Flutter mobile et administration Flutter web, MySQL et fichiers prives ; garde au niveau vehicule, projection des engagements sur nouvelles versions et reprises sans double effet.

29 octobre 2026, décision du fondateur sur les pièces des chauffeurs, catalogue v2 (`specifications/WAKA_pieces_chauffeur.md`) :
- **Lancement** : taxis et voitures personnelles ensemble.
- **Taxi** : visite technique, licence de transport de première catégorie et carte bleue exigées ; assurance couvrant le transport de passagers.
- **Voiture personnelle** : ni visite technique, ni autorisation de transport demandée au chauffeur ; assurance ordinaire du véhicule acceptée. L'autorisation applicable (licence S10 ou autre) est une **obligation de WAKA en tant que plateforme**, à obtenir hors du dossier chauffeur.
- **Dérogation** : un agent peut approuver un dossier incomplet, pièce par pièce, avec un motif inscrit au journal. Jamais pour l'identité ni le permis.
- **Enregistrement communal** des taxis à Yaoundé : non demandé pour l'instant.
- **Sources** : celles déjà citées [S4][S5][S6][S8]. Aucun texte officiel n'a été consulté. Une confirmation écrite du ministère des Transports et d'un assureur reste recommandée avant le lancement réel.

29 octobre 2026, décisions du fondateur sur le lancement (règle R11 dans `specifications/WAKA_regles_adoptees.md`) :
- **Lancement** : Yaoundé, dans un cercle de 20 km choisi à sa demande ; grille **launch-v1** en vente.
- **Voyages entre villes** :
  - publiés par tout chauffeur approuvé, sans abonnement ;
  - prix par paire d'arrêts fixé par le chauffeur, au plus 10 000 F la place ; commission WAKA de 10 % ;
  - argent versé au chauffeur à l'embarquement ;
  - annulation par le chauffeur : sans frais ;
  - annulation par le client : gratuite jusqu'à 12 h avant le départ, puis 15 % de frais, et 30 % à moins de 3 h ; les frais sont partagés moitié-moitié.
- **Porte-monnaie rechargeable** par Mobile Money, détenu par un partenaire agréé ; à traiter avec les paiements.

30 octobre 2026 : le fondateur délègue les décisions restantes (« prends les décisions toi-même »). L'équipe retient chaque fois l'option la plus prudente, consignée dans la règle R12 :
- parrainage : saisie du code avant toute course terminée et payée ; parrainage mutuel interdit ; 5 codes erronés au plus sur 24 h ; pas de bonus sur les voyages ;
- voyages : un passager absent est traité comme une annulation tardive (30 %) ;
- abonnement : pas d'achat avant la première prestation ; une prestation interrompue ne déclenche ni l'essai ni la fenêtre de parrainage ; une période payée non commencée est remboursable ;
- données : une habilitation `PRIVACY` traite les demandes. Les durées de conservation restent à qualifier juridiquement.

21-22 novembre 2026, décisions du fondateur sur les SMS :
- **Attente levée** : le SMS des codes de connexion est repris pour la mise en ligne.
- **Orange** : les pages développeur d'Orange annoncent l'arrêt de l'API SMS Cameroun au 1er décembre 2026. Le forfait d'essai acheté vers le 16 octobre est très probablement expiré.
- **Fournisseur retenu : SmsPro** (Mediasystem SARL, Yaoundé), un seul compte pour MTN et Orange. Prix affichés le 22 novembre : 13 F le SMS jusqu'à 1 000, puis de moins en moins cher ; achat minimum de 500 SMS (6 500 F). Camtel et Nexttel ne sont pas couverts.
- **Le SMS ne sert qu'au code de connexion.** Le reste passe par l'application.
- **Aucun achat sans accord** du fondateur sur le montant. L'adaptateur est prêt et testé sans envoi réel (`backend/src/main/java/com/waka/identity/sms/smspro`).

23 novembre 2026, décision du fondateur sur la durée des connexions (`SessionPolicy.mobile()`) :
- Un code de connexion n'est demandé que sur un nouveau téléphone, après « Se déconnecter », après une réinstallation ou l'effacement des données de l'app, ou après **90 jours sans ouvrir WAKA**. Chaque utilisation repousse ces 90 jours.
- **Pas de reconnexion annuelle obligatoire** : le fondateur la juge inutile et gênante.
- Les agents de l'administration gardent leurs limites courtes (`AdminPolicy`) et leur application d'authentification.

23 novembre 2026, demande du fondateur : **messagerie interne** (à construire après le branchement des SMS Cooms).
- **Chauffeur et client d'une course ou d'un voyage** : fil ouvert de l'acceptation à la fin de la course, sans jamais montrer les numéros de téléphone.
- **Support WAKA** : un fil entre le chauffeur et les agents « Assistance » de l'administration.
- Choix prudents de l'équipe, à confirmer par le fondateur :
  - texte seulement au lancement, 500 caractères au plus, avec des réponses rapides (« J'arrive », « Je suis là ») ;
  - fil d'une course : on peut encore écrire 1 heure après la fin (objet oublié), puis lecture seule ;
  - messages lus par un agent seulement dans un incident ou une demande de support, et chaque lecture est tracée ;
  - durée de conservation à qualifier juridiquement, comme les autres données ;
  - pas de groupe entre chauffeurs (modération trop lourde).

23 novembre 2026 : **Cooms** remplace SmsPro pour les codes de connexion. SmsPro exige un NIU que le fondateur n'a pas ; Cooms demande seulement un e-mail et offre 100 SMS. Le site de Cooms n'indique ni la société ni l'adresse : c'est un risque connu, et SmsPro reste prêt dans le code. Recharges Cooms à partir de 3 000 F, seulement avec l'accord du fondateur sur le montant.

23 novembre 2026, décision du fondateur : **CamPay** devient le prestataire de paiement, car Fapshi n'a pas validé le compte réel (seule la démo reste ouverte). Fapshi et Notch Pay restent branchés en réserve.
- Contrat lu le 23 novembre dans la documentation Postman de CamPay : `collect`, `withdraw`, `transaction/{reference}`, jeton permanent, notifications signées (JWT HS256 avec la clé de webhook) et jamais renvoyées.
- CamPay évite les doublons grâce à `external_reference` : WAKA y met son propre identifiant et, si une réponse se perd, renvoie exactement la même demande, jamais une nouvelle.
- **À confirmer avant le passage en réel** : l'adresse de production (seule `demo.campay.net` est documentée), les frais, et les documents demandés à un particulier pour passer en réel. Un guide tiers cite le RCCM et des informations fiscales.
- **Essai de la démo CamPay du 23 novembre 2026** (numéro de test 237677777777, sans vrai argent) :
  - la démo accepte au plus **100 F** par paiement ; à 101 F et au-delà, elle répond 400 sans message. Une recharge WAKA commence à 500 F, donc la démo ne peut pas être essayée de bout en bout depuis l'application ;
  - un paiement de 25 F passe de `PENDING` à `SUCCESSFUL` en moins d'une minute, mais la démo renvoie `amount: "0.00"`. WAKA n'accepte un paiement que si CamPay confirme le montant exact : un paiement sans montant confirmé est mis en vérification, jamais crédité. **À demander à CamPay** : le vrai montant est-il renvoyé en production ?
