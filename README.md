# waka
application de convoiturage

## Etat actuel — 15 octobre 2026

Le socle Spring Boot/MySQL et Flutter Android est construit et verifie localement. La validation de format du telephone et le moteur de prix partage/VIP sont realises. Les protections OTP persistantes et un adaptateur SMS candidat sont prepares et testes sans activation de fournisseur ; aucun compte, SMS, session ou paiement reel. Voir [backend/README.md](backend/README.md) pour les procedures et leurs limites. L'application complete n'est pas encore ouverte aux utilisateurs.

Les six roles Atlas, Kora, Naya, Sika, Vigie et Scribe sont definis dans .codex/agents : [guide de l'equipe](docs/equipe/README.md). Aucun abonnement ou modele supplementaire requis pour ces profils.


## Organisation du projet

Correction locale du 15 octobre 2026 : cette copie travaille sur cv215/waka, depot partage avec le fondateur. Historique et texte initial preserves. Les fichiers prepares ne sont pas encore publies.

- backend : serveur Spring Boot modulaire, MySQL/migrations et premiers modules controles.
- apps/mobile : application Flutter Android construite, accueil et precontrole de format ; connexion et reservations encore fermees.
- apps/admin : administration Flutter web protegee, a construire.
- docs : [guide de lecture](docs/README.md), regles, parcours et specifications.
- tests et tools : controles de sources et scripts de construction/lancement sans secrets.

La correction de depot initiale n'avait cree aucun executable ; les realisations ulterieures sont celles decrites ci-dessus. Les specifications et resultats techniques restent a relire. Voir AGENTS.md pour la methode autonome et l'accord obligatoire avant depense. Aucun secret ou dossier personnel reel dans le depot.
