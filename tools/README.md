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

**Clés de bac à sable Fapshi** : lancer `tools\save-fapshi-keys.ps1` et coller chaque valeur quand elle est demandée. Les clés sont protégées par Windows pour votre compte, jamais affichées et jamais versées dans Git. Au démarrage suivant avec `-LocalApp`, les paiements passent par le bac à sable Fapshi.

La clé maîtresse locale est créée une seule fois dans `%LOCALAPPDATA%\WAKA\Secrets\local-app-master.dpapi`. Il ne faut pas la supprimer : les données chiffrées de la base locale en dépendent.
