# Administration web WAKA

Application Flutter web distincte, réservée aux agents habilités. Première fonction réalisée : **l'examen des candidatures chauffeur** (fonction `ADMISSION`).

## Ce qu'elle fait

1. **Connexion de l'agent** : numéro du compte WAKA, puis code reçu par SMS (session client), puis code à 6 chiffres de l'application d'authentification (session d'administration).
   - À la première connexion, la clé de l'application d'authentification est affichée une seule fois. Le premier code la confirme ; le code suivant ouvre l'administration.
   - Sans habilitation active, l'accès est refusé. Le premier accès du fondateur se crée par maintenance locale (voir `backend/.../identity/admin/README.md`).
2. **File d'attente** : dossiers envoyés à l'examen, jamais le dossier de l'agent lui-même.
3. **Examen d'un dossier** : véhicule déclaré, six photos et pièces applicables.
   - Chaque image ne s'affiche **qu'à la demande** (« Afficher ») : chaque lecture est inscrite au journal d'audit du serveur.
   - Chaque photo ou pièce peut être jugée « acceptable » ou « à corriger » (avec un code de motif).
   - **Décision** : demander une correction (seuls les éléments « à corriger » le seront), refuser avec motif, ou approuver. L'approbation est bloquée à l'écran et par le serveur tant qu'une exigence du dossier est « à confirmer par WAKA ».
   - Si la réponse se perd, la même décision est renvoyée avec la même clé : elle n'est jamais appliquée deux fois.

## Sécurité

- **Rien n'est conservé dans le navigateur** : ni jeton, ni code, ni image. Recharger la page impose de se reconnecter.
- Les preuves (session client, session d'administration) passent dans des en-têtes, jamais dans l'adresse ni dans des cookies.
- L'adresse du serveur est fixée à la construction : `http` seulement pour `localhost`, `https` sinon.
- Toutes les autorisations sont décidées par le serveur ; l'écran ne fait qu'aider l'agent.
- Côté serveur, l'origine de cette application doit être autorisée explicitement par `WAKA_ADMIN_WEB_ORIGINS`. Aucune origine n'est autorisée par défaut ; le joker `*` et les cookies sont refusés.

## Lancer en local (développement)

Serveur : démarrer le JAR avec, en plus de la configuration habituelle, `WAKA_ADMIN_WEB_ORIGINS=http://localhost:7357`.

Application :

```bash
flutter run -d chrome --web-port 7357 --dart-define=WAKA_API_BASE_URL=http://localhost:8080
```

## Limites actuelles

- La connexion passe par le code SMS. Tant que l'envoi de SMS n'est pas activé (en attente, à la demande du fondateur), un agent ne peut pas se connecter en conditions réelles.
- Les services serveur (connexion, administration, dossiers) ne sont pas assemblés dans l'application ordinaire : elle répond « indisponible » sans configuration qualifiée.
- Fonctions assistance, finance, audit et gestion des habilitations : à construire.
- Pas d'hébergement HTTPS en ligne : à décider avec le fondateur (service payant).
