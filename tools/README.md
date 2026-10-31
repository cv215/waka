# Outils du depot

Emplacement des futurs outils reproductibles de construction et controle. Aucun script du coffre, mot de passe, configuration de production ou export personnel ici. Les outils temporaires de preparation restent hors de ce depot.

## Essayer l'application complète sur ce poste (R14, 30 octobre 2026)

Ce mode sert seulement aux essais sur ce poste (profil `dev`, adresse 127.0.0.1). Trois services payants y sont remplacés par des stand-ins :
- **SMS** : les codes de connexion sont écrits dans `%LOCALAPPDATA%\WAKA\backend-local\app\codes-connexion-local.txt` ;
- **Cartes** : les distances sont estimées en ligne droite, multipliées par 1,3 ;
- **Paiements** : ils sont simulés (tout réussit), sauf si des clés de bac à sable Fapshi ont été enregistrées.

1. Démarrer MySQL : `%LOCALAPPDATA%\WAKA\mysql-local\start-mysql.ps1`.
2. Compiler le serveur (`tools\build-backend.ps1`), puis lancer :
   `tools\start-dev-backend.ps1 -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" -LocalApp`
3. Installer l'APK de test sur l'émulateur Android. L'application appelle `http://10.0.2.2:8080`.
4. Se connecter avec un numéro de test : le code s'affiche dans le fichier des codes ci-dessus.

**Sur un iPhone (aperçu web, même Wi-Fi)** : sans Mac, l'application ne s'installe pas sur iPhone. On l'ouvre donc dans Safari.
1. Construire la version web dans `apps/mobile` : `flutter build web --debug --dart-define=WAKA_ENV=dev --dart-define=WAKA_API_BASE_URL=origin`.
2. Le serveur local étant démarré, lancer le relais `tools\serve-web-preview.py`. Il affiche l'adresse à ouvrir sur l'iPhone, par exemple `http://192.168.1.29:8090`. Windows peut demander d'autoriser Python sur le réseau privé.

Le serveur reste fermé au réseau : seul le relais est joignable. La session est gardée dans l'onglet de Safari et disparaît à sa fermeture. Données de test uniquement.

**Premier agent de la base locale** : pour approuver des chauffeurs avec l'administration web, ajouter `-FounderAccount <identifiant>` au démarrage. L'identifiant est l'`accountId` renvoyé par `/api/v1/me` pour votre compte. Cela ne marche qu'une fois par base : les agents suivants sont nommés depuis l'administration (gouvernance).

**Administration web sur ce poste** : construire `apps/admin` avec `flutter build web --release --dart-define=WAKA_API_BASE_URL=http://127.0.0.1:8091`, puis lancer `tools\serve-web-preview.py --admin`. Elle s'ouvre sur http://127.0.0.1:8091, sur ce poste seulement, jamais sur le Wi-Fi. Après le code SMS, la page affiche l'identifiant de compte à transmettre à un agent « Habilitations ».

**APK de test avec la carte** : `tools\build-android-preview.ps1` construit l'APK pour l'émulateur avec la clé « waka-carte » enregistrée sur ce poste.

**Clés Google Maps** : lancer `tools\save-google-maps-keys.ps1`, puis coller la clé « waka-serveur » et la clé « waka-carte » avec Ctrl+V. Au démarrage suivant avec `-LocalApp`, les distances et les temps d'approche sont calculés par la route (Google Routes, sans trafic en direct) au lieu du vol d'oiseau.

**Clés de bac à sable Fapshi** : lancer `tools\save-fapshi-keys.ps1` et coller chaque valeur quand elle est demandée. Les clés sont protégées par Windows pour votre compte, jamais affichées et jamais versées dans Git. Au démarrage suivant avec `-LocalApp`, les paiements passent par le bac à sable Fapshi.

La clé maîtresse locale est créée une seule fois dans `%LOCALAPPDATA%\WAKA\Secrets\local-app-master.dpapi`. Il ne faut pas la supprimer : les données chiffrées de la base locale en dépendent.
