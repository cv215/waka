# Application mobile WAKA

## Évolution 05.04.a — revalidation du compte

L'application relit maintenant `/me` pour une session restaurée. Elle contrôle le compte et la session de la réponse, puis affiche l'état actif et le seul accès client prévu. Une panne laisse l'accès non confirmé ; une session refusée déclenche un effacement protégé avec reprise explicite en cas d'échec. Les droits ne sont pas tirés du stockage local. Le guide de connexion ci-dessous explique le chemin complet et les limites ; aucune réservation, candidature approuvée ou fonction d'administration n'est ouverte par cette lecture.

## État actuel — 05.03.d

Le parcours numéro → code → session → déconnexion utilise maintenant les contrats HTTP WAKA et un stockage Android protégé. Les commandes interrompues gardent leur clé pour reprendre ; aucun SMS n'est envoyé automatiquement au redémarrage. Un envoi incertain, une session seulement restaurée et une panne de stockage sont explicitement distingués d'une connexion confirmée.

Lire le [guide du code de connexion](lib/features/identity/README.md), qui explique les fichiers, les erreurs, les dépendances gratuites et les commandes de contrôle. Le serveur ordinaire n'a pas de canal SMS activé : les demandes valides obtiennent 503. Profil, rôles, candidatures et fonctions commerciales restent à développer. Aucune réponse simulée n'est embarquée dans l'application ; les transports de test restent dans `test` et `integration_test`.

Les paragraphes ci-dessous sont les résultats historiques de 04.04–04.06. Le bilan daté 05.03.d du dossier de suivi précise les vérifications réellement exécutées sur cette version, y compris les limites du contrôle Android.

## Historique 04.04

04.04 — projet Flutter Android créé avec le modèle vide officiel, sans compteur ni données de démonstration. La navigation minimale présente l’accueil WAKA, l’entrée de connexion et les informations chauffeur. Connexion et dépôt des candidatures sont explicitement indisponibles : aucun compte ou rôle n’est simulé.

Le diagnostic technique est une vue séparée, initialement « Vérification non effectuée ». Aucun appel HTTP n’est effectué dans ce point. Le raccordement réel au serveur relève de 04.05 ; il ne représentera pas à lui seul la disponibilité commerciale de WAKA.

## Organisation

- `lib/main.dart` démarre `WakaApp`, sans réseau ni session.
- `lib/app.dart` configure Material, la langue française et l’accueil.
- `lib/core/presentation` contient le thème et le cadre défilant. Le texte agrandi n’est pas limité artificiellement.
- `lib/features/home`, `identity`, `drivers` et `diagnostics` contiennent les vues nécessaires. `Navigator` ouvre les pages ; le retour Android retrouve la page précédente. Choisir la page chauffeur n’accorde aucun droit.
- `test/navigation_test.dart` contrôle les parcours, l’absence de saisie prématurée, l’état non vérifié, le petit écran avec texte agrandi et des critères d’accessibilité.
- `android` provient de Flutter 3.47.6 pour Android uniquement. Le nom affiché est WAKA et l’icône locale est un W vectoriel. L’identifiant interne `com.waka.waka_mobile` ne déclare aucune propriété de domaine Internet. La signature release n’a aucun repli sur les clés debug ; la publication sera préparée séparément.

Le README initial a été conservé en mémoire et restauré pendant la création Flutter avant cette mise à jour. Ses principes sont conservés : application client/chauffeur unique, responsabilités sous `lib/core` et `lib/features`, autorité du serveur pour prix, places, paiements et rôles, aucun secret prestataire dans Dart. Voir les [fiches des écrans](../../docs/specifications/WAKA_fiches_ecrans.md), notamment E01/E03 et E18–E22, et les [contrats](../../docs/specifications/WAKA_contrats_api.md).

## Commandes

Depuis la racine du dépôt, dans le terminal de VS Code :

```powershell
Set-Location apps/mobile
flutter pub get
flutter analyze
flutter test
```

La résolution des dépendances gratuites n’active pas de fournisseur commercial. `pubspec.lock` fixe les versions résolues pour cette application. `.dart_tool`, caches, fichiers locaux Android et sorties de compilation sont exclus de Git. Les libellés français viennent du SDK `flutter_localizations`, sans police distante.

Contrôles 04.04 du 15 octobre 2026 : création et résolution réussies, formatage appliqué, analyse sans problème, cinq tests de widgets réussis et APK debug installé et affiché sur l’émulateur. Le premier passage des tests avait détecté un nettoyage de handle sémantique trop tardif ; il a été corrigé dans le test avant le passage réussi.

## Vérification locale (04.05)

Le diagnostic effectue un vrai GET local. Sur Android, l’émulateur joint le PC par `10.0.2.2`; le serveur reste lié à `127.0.0.1`. L’écran distingue la réponse du serveur, la réponse de la base, une base indisponible et un serveur inaccessible. La lecture de la base ne se fait que si le serveur répond. Aucun compte ni solde de démonstration n’est utilisé.

La cible est injectable pour le build de développement ; les paramètres sont limités à `dev`, au mode debug et aux hôtes locaux autorisés. Le client refuse les redirections, borne les délais et la taille de réponse puis ferme ses connexions. Analyse Flutter sans erreur, huit tests réussis et build debug avec configuration locale ont été vérifiés. Le contrôle confirme une réponse technique locale, pas les réservations, paiements ou la disponibilité commerciale.

## Configuration backend (04.06)

Le backend exige un profil explicite et n’autorise localement que les profils `dev` et `test` vers leurs bases distinctes en boucle locale. Le JAR a refusé un profil absent, un profil de production non préparé et une cible distante. Les protections de connexion, délais, pool et migrations sont validées avant démarrage. Le JAR actif doit être arrêté avant sa reconstruction ; le script de build le vérifie.

Le profil `test` a été accepté par les tests en mémoire, mais une vraie base `waka_test` n’a pas été créée ni testée. La configuration locale vérifie la CA TLS sans valider l’identité du nom d’hôte. Les journaux framework peuvent contenir des URL et chemins privés ; le traitement détaillé des erreurs reste au point 04.07. Le serveur local est actuellement arrêté et devra être reconstruit pour le prochain essai. Aucun serveur distant ou production n’a été créé ou testé.

Les bilans détaillés 04.05 et 04.06 se trouvent dans `outputs/WAKA_etapes` du dossier de suivi documentaire. Aucun numéro, position ou fichier personnel n’est collecté par le diagnostic. Réservations, finance, authentification et permissions caméra/localisation ne sont pas ajoutées ici.
