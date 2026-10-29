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
- **Lancement** : Yaoundé, dans un cercle de 20 km choisi par Claude à sa demande ; grille **launch-v1** en vente.
- **Voyages entre villes** :
  - publiés par tout chauffeur approuvé, sans abonnement ;
  - prix par paire d'arrêts fixé par le chauffeur, au plus 10 000 F la place ; commission WAKA de 10 % ;
  - argent versé au chauffeur à l'embarquement ;
  - annulation par le chauffeur : sans frais ;
  - annulation par le client : gratuite jusqu'à 12 h avant le départ, puis 15 % de frais, et 30 % à moins de 3 h ; les frais sont partagés moitié-moitié.
- **Porte-monnaie rechargeable** par Mobile Money, détenu par un partenaire agréé ; à traiter avec les paiements.

30 octobre 2026 : le fondateur délègue les décisions restantes (« prends les décisions toi-même »). Claude retient chaque fois l'option la plus prudente, consignée dans la règle R12 :
- parrainage : saisie du code avant toute course terminée et payée ; parrainage mutuel interdit ; 5 codes erronés au plus sur 24 h ; pas de bonus sur les voyages ;
- voyages : un passager absent est traité comme une annulation tardive (30 %) ;
- abonnement : pas d'achat avant la première prestation ; une prestation interrompue ne déclenche ni l'essai ni la fenêtre de parrainage ; une période payée non commencée est remboursable ;
- données : une habilitation `PRIVACY` traite les demandes. Les durées de conservation restent à qualifier juridiquement.
