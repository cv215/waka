# Connexion WAKA — lecture du code dans VS Code

## Évolution 05.04.a — 18 octobre 2026

`LoginClient.me` transmet seulement le jeton dans l'en-tête Authorization et son contexte au `GET /api/v1/me`, sans corps, query ni clé de commande. `LoginAccount.parse` exige les sept champs convenus, le même compte et la même session, le seul rôle CLIENT et des dates cohérentes avec l'heure serveur. Il refuse un autre compte, un rôle supplémentaire, un jeton renvoyé, une date impossible ou une durée absolue modifiée.

`LoginFlow.initialize` reprend en priorité une déconnexion en attente ; sinon une session restaurée appelle `/me` avant confirmation. `checkAccount` permet une reprise explicite et retire d'abord toute confirmation ancienne. Les échéances actualisées sont écrites dans le stockage protégé avant d'afficher le compte. La réponse et le droit CLIENT ne sont pas enregistrés comme autorisation permanente.

L'expiration après lecture utilise l'heure du serveur et un chronomètre monotone local, avec le temps de trajet compté prudemment. L'horloge murale du téléphone ne peut pas allonger une session confirmée ; une expiration locale ancienne n'empêche pas de demander l'état au serveur. Chaque future opération protégée devra malgré tout contrôler sa session côté serveur.

Un refus 401/403 retire l'accès et tente d'effacer la session locale. Si l'écriture protégée échoue, `cleanupPending` bloque son utilisation et l'écran propose de terminer l'effacement. Au redémarrage, aucune donnée restaurée ne redonne un droit sans revalidation. Une panne réseau/503 conserve la session uniquement pour réessayer, sans confirmation ni rôle affiché. Après A02, la connexion peut être confirmée, mais l'état du compte et le droit client apparaissent seulement après `/me`.

Le parcours continue à fonctionner uniquement contre un assemblage serveur qualifié ; le serveur ordinaire reste non configuré. Les tests ajoutés dans `test/me_client_test.dart` et `test/login_flow_test.dart` vérifient refus, réseau, horloge décalée, autres comptes, absence de privilèges, pannes d'écriture et ordre avec déconnexion. Les résultats réellement exécutés figurent au bilan 05.04.a. Les sections suivantes décrivent la base 05.03 ; leur mention de `/me` encore absent est historique.

05.03.d, 17 octobre 2026. Le parcours mobile appelle les vrais contrats HTTP ; les réponses simulées se trouvent exclusivement dans les tests. L'installation serveur ordinaire n'a pas encore de canal qualifié configuré et répond 503. Aucun SMS, compte réel ni rôle commercial n'est créé par l'existence de ces écrans.

## De l'écran au serveur

- `presentation/sign_in_page.dart` affiche le numéro, le code, l'état de connexion et la déconnexion. Il bloque les doubles clics pendant une demande, masque le code, vide son champ après connexion et indique une réception incertaine sans annoncer « SMS reçu ». Une session restaurée ou expirée n'est pas présentée comme actuellement confirmée par le serveur.
- `application/login_flow.dart` ordonne les actions. Il écrit la commande dans le stockage protégé avant le premier appel réseau. Une panne conserve son identifiant pour la reprise, y compris après fermeture du processus. Il ne renvoie jamais automatiquement un SMS.
- `data/login_client.dart` implémente A01–A03 avec `HttpClient`. Connexion 3 s, total 8 s, réponse au plus 8 Kio ; aucune redirection. JSON et réponse no-store sont requis. Les requêtes de reprise gardent la même clé. Le client ne contient pas de clé prestataire.
- `data/login_models.dart` valide les réponses et normalise les numéros. Le modèle `LoginAttempt` garde une graine aléatoire protégée : HMAC-SHA256 de cette graine, du défi et du code produit une clé de tentative UUID. Ressaisir le même code retrouve la même commande ; un code différent a une autre clé. Le code SMS n'est jamais écrit sur disque. Cette clé de reprise n'est pas une preuve d'identité : seul le serveur vérifie le code.
- `data/login_store.dart` conserve un enregistrement chiffré unique contenant contexte, demande en attente OU session, et éventuelle commande de déconnexion. Android uniquement, sans repli vers des préférences en clair. Les erreurs de lecture/chiffrement bloquent la poursuite : aucun effacement automatique ni remplacement silencieux du contexte.

Les modèles sensibles ont un `toString` expurgé. Les chaînes Dart/HTTP peuvent rester temporairement en mémoire ; on ne prétend pas pouvoir effacer toutes leurs copies. Aucun code de connexion, jeton, téléphone ou graine dans les journaux, captures du dossier ou messages d'erreur.

## Pannes et déconnexion

Une coupure avant la réponse conserve la demande. Après redémarrage, le numéro et le défi protégés sont retrouvés ; l'utilisateur ressaisit son code. La fenêtre de reprise reste imposée par le serveur et ne s'allonge pas. Une nouvelle demande de code résulte d'une action explicite « Recommencer la connexion ».

Si le stockage de la session échoue après la réponse, l'écran indique « Enregistrement à terminer ». Il conserve le résultat en mémoire pour retenter l'écriture ; il n'annonce pas une connexion complètement enregistrée. Une fermeture à ce moment peut nécessiter une nouvelle saisie du code à partir de la demande déjà persistée.

La déconnexion marque d'abord le stockage comme en attente, puis contacte le serveur avec une clé persistante. Pendant l'attente, le jeton reste conservé uniquement pour terminer cette révocation. Seul un 204 annonce « Déconnexion confirmée ». Un refus de session 401/403 entraîne un message distinct et un effacement local ; il n'est pas présenté comme une révocation réalisée. Un échec de l'effacement local conserve l'état à reprendre.

Une session relue du stockage doit encore être validée par le serveur avant une opération protégée. `/me` et le vrai profil arrivent au point 05.04 ; aucune tâche client/chauffeur n'est ouverte sur la seule présence du jeton. Les paramètres de production, le transport HTTPS et la rotation/gestion des clés restent nécessaires à l'activation réelle.

## Bibliothèques et Android

Versions directes épinglées : [flutter_secure_storage 11.2.0](https://pub.dev/packages/flutter_secure_storage/versions/11.2.0) et [crypto 3.0.7](https://pub.dev/packages/crypto/versions/3.0.7), gratuites ; le verrou `pubspec.lock` conserve les dépendances résolues. Aucun abonnement requis. Le stockage Android emploie les options RSA OAEP/AES-GCM de la bibliothèque, espace `waka_identity`, sans reset sur erreur ou migration automatique. Toute future évolution de chiffrement doit prévoir sa migration explicitement.

Le manifeste désactive la sauvegarde. Les règles XML excluent les préférences, y compris des transferts d'appareil à appareil qui peuvent rester autorisés malgré allowBackup=false sur Android récent. Voir [documentation Android](https://developer.android.com/identity/data/autobackup). Aucun essai de transfert entre deux appareils n'est déclaré réalisé.

## Vérifications reproductibles

Depuis `apps/mobile` :

```powershell
flutter pub get
dart format --output=none --set-exit-if-changed lib test integration_test
dart analyze --fatal-infos lib test integration_test
flutter --no-version-check --suppress-analytics test --no-pub --reporter expanded
flutter --no-version-check --suppress-analytics test integration_test/login_android_test.dart -d emulator-5554 --no-pub
flutter --no-version-check --suppress-analytics build apk --debug --no-pub --dart-define=WAKA_ENV=dev --dart-define=WAKA_API_BASE_URL=http://10.0.2.2:8080
```

L'intégration Android utilise un serveur HTTP de test à l'intérieur de l'émulateur et un espace de stockage natif aléatoire réservé au test ; elle ne contacte ni le serveur réel WAKA ni Orange. Nettoyage de la seule clé créée, sans effacement global des données Android. Les tests en mémoire couvrent reprises, clés, code non stocké, stockage indisponible, expiration, doubles clics et écrans. Les essais HTTP locaux traversent réellement les sockets et refusent redirections, réponse trop grande et cache non protégé. Le bilan daté indique précisément les commandes réellement exécutées, leurs résultats et l'APK construit.
