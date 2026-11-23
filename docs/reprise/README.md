# Reprendre WAKA : guide complet

Ce guide permet à une nouvelle personne de reprendre WAKA seule, sur un PC Windows neuf : installer, lancer, tester, comprendre les règles et savoir ce qui reste à faire.

État au 23 novembre 2026, branche `socle-waka` du dépôt `cv215/waka`.

---

## 1. Ce qu'est WAKA

Application de transport au Cameroun, lancement à Yaoundé :

- **courses en ville** : partagées ou VIP, en taxi ou avec des particuliers ;
- **voyages entre villes** ;
- **portefeuille** : recharge Mobile Money, gains des chauffeurs, retraits ;
- **parrainage**, **assistance** et **administration web** pour l'équipe WAKA (les « agents »).

| Partie | Technologie | Dossier |
|---|---|---|
| Serveur | Java 21, Spring Boot 4, MySQL 8.4 (Flyway crée les tables) | `backend/` |
| Application client et chauffeur | Flutter : Android, plus une version web pour iPhone | `apps/mobile/` |
| Administration | Flutter web | `apps/admin/` |
| Scripts | PowerShell et Python | `tools/` |
| Règles et documentation | Markdown | `docs/` |

---

## 2. Où se trouve chaque chose

| Pour savoir... | Lire |
|---|---|
| Les règles métier (prix, commissions, VIP, abonnements, annulations, argent) | [docs/specifications/WAKA_regles_adoptees.md](../specifications/WAKA_regles_adoptees.md) |
| Les écrans | [WAKA_fiches_ecrans.md](../specifications/WAKA_fiches_ecrans.md) |
| Les données et les états | [WAKA_modele_donnees.md](../specifications/WAKA_modele_donnees.md), [WAKA_etats_et_transitions.md](../specifications/WAKA_etats_et_transitions.md) |
| L'API du serveur | [WAKA_contrats_api.md](../specifications/WAKA_contrats_api.md) |
| Les droits et la conservation des données | [WAKA_acces_et_conservation.md](../specifications/WAKA_acces_et_conservation.md) |
| Les décisions du fondateur et les questions ouvertes | [docs/decisions/README.md](../decisions/README.md) |
| Tous les scripts | [tools/README.md](../../tools/README.md) |
| Un essai complet à deux | [docs/essais/README.md](../essais/README.md) |
| Le serveur en détail | [backend/README.md](../../backend/README.md) |

**Règle d'or :** ne jamais reconstruire une règle de prix ou d'argent de mémoire. Toujours lire `WAKA_regles_adoptees.md`. Calculs intermédiaires exacts, arrondi seulement à la fin.

---

## 3. Règles de travail du projet

- **Le fondateur décide.** Toute ambiguïté commerciale lui est posée avant de coder.
- **Aucune dépense sans accord** sur le coût exact : achat, crédit, abonnement, service payant.
- **Le dépôt est public.** Jamais de clé, mot de passe, vraie pièce d'identité, export de production ni donnée personnelle dans Git.
- **Les clés ne passent jamais par une conversation ou un message.** Chacun les enregistre sur son PC avec les scripts `tools\save-*.ps1`, qui les protègent par Windows.
- **Ne jamais annoncer un paiement, un test ou une validation sans preuve.** Dire ce qui a vraiment été vérifié, et ce qui ne l'a pas été.
- **Données d'essai réalistes** (« Paul Etoa »), jamais le mot « test » dans les noms.
- **Pas de tiret cadratin** (le long tiret) dans les textes de l'application et de la documentation.

---

## 4. Installer sur un PC Windows neuf

Une seule fois. Compte une à deux heures, surtout pour les téléchargements. Tout est gratuit.

### 4.1 Les outils

| Outil | Version utilisée | Où le trouver |
|---|---|---|
| Git | récente | https://git-scm.com |
| Java JDK 21 | Eclipse Temurin 21 | https://adoptium.net (installateur .msi) |
| Maven | 3.10.0 | https://maven.apache.org/download.cgi (zip à décompresser) |
| MySQL | 8.4 LTS (8.4.11 utilisé) | https://dev.mysql.com/downloads/mysql/ : choisir « Windows (x86, 64-bit), ZIP Archive ». **Ne pas l'installer** : le script s'en charge |
| Visual C++ Redistributable | x64 | https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist (exigé par MySQL) |
| Flutter | 3.47 stable | https://docs.flutter.dev/get-started/install/windows |
| Android Studio | récente | https://developer.android.com/studio (émulateur Android) |
| Python | 3.12 | https://www.python.org (cocher « Add python.exe to PATH ») |
| VS Code | récente | https://code.visualstudio.com |

### 4.2 Le code

```powershell
git clone https://github.com/cv215/waka.git
cd waka
git checkout socle-waka
```

L'historique a été réécrit le 23 novembre 2026. Une copie plus ancienne ne correspond plus : il faut recloner.

### 4.3 Le dossier privé

Tout ce qui ne doit pas aller dans Git vit dans `%USERPROFILE%\WAKA-local` : la base de données, les outils, les clés protégées par Windows et les fichiers de codes de connexion. Il est créé automatiquement par les scripts.

### 4.4 La base de données

Dans PowerShell, à la racine du dépôt (adapter le chemin du zip téléchargé) :

```powershell
powershell -ExecutionPolicy Bypass -File tools\mysql\install-mysql.ps1 -MysqlZip "$env:USERPROFILE\Downloads\mysql-8.4.11-winx64.zip"
```

Le script :
- décompresse MySQL dans le dossier privé ;
- le configure pour n'écouter que ce PC (`127.0.0.1:3306`), avec des connexions chiffrées obligatoires ;
- crée les bases `waka_local` (application) et `waka_test` (tests) ;
- crée les comptes limités `waka_dev` et `waka_test_runner`, avec des mots de passe aléatoires protégés par Windows et jamais affichés.

Ensuite, à chaque session de travail :

```powershell
powershell -ExecutionPolicy Bypass -File tools\mysql\start-mysql.ps1
```

Et pour arrêter :

```powershell
powershell -ExecutionPolicy Bypass -File tools\mysql\stop-mysql.ps1
```

### 4.5 Compiler et vérifier le serveur

Le serveur doit être arrêté pendant la compilation (Windows verrouille le fichier).

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-backend.ps1 -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot" -MavenHome "C:\outils\apache-maven-3.10.0"
```

Résultat attendu : `Tests run: 998, Failures: 0, Errors: 0, Skipped: 83`, puis `BUILD SUCCESS`. Les 83 tests ignorés sont ceux qui ont besoin de la vraie base. Pour les lancer aussi, la base étant démarrée :

```powershell
powershell -ExecutionPolicy Bypass -File tools\mysql\run-mysql-tests.ps1 -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot" -MavenHome "C:\outils\apache-maven-3.10.0"
```

Résultat attendu : `Tests run: 998, Failures: 0, Errors: 0, Skipped: 0`. Ce résultat a été obtenu le 23 novembre 2026 sur une base installée de zéro avec ces scripts.

### 4.6 Lancer WAKA

```powershell
powershell -ExecutionPolicy Bypass -File tools\start-dev-backend.ps1 -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot" -LocalApp -CodesInFile
```

- Au premier démarrage, le serveur crée toutes les tables. Il est prêt quand `APPLICATION_READY` s'affiche.
- `-CodesInFile` : aucun SMS n'est envoyé. Les codes de connexion sont écrits dans `%USERPROFILE%\WAKA-local\backend-local\app\codes-connexion-local.txt`.
- Sans clés enregistrées, les paiements sont **simulés** (tout réussit) et les distances sont estimées à vol d'oiseau multiplié par 1,3. C'est parfait pour développer.

Ensuite : l'application Android sur l'émulateur, la version web pour iPhone et l'administration web, comme décrit dans [tools/README.md](../../tools/README.md) et [docs/essais/README.md](../essais/README.md).

**Devenir le premier agent de sa base locale :** se connecter une fois dans l'application, relever son `accountId` (route `/api/v1/me`), puis relancer le serveur avec `-FounderAccount <accountId>`. Cela ne marche qu'une fois par base.

### 4.7 Vérifier l'application

```powershell
cd apps\mobile
flutter analyze
flutter test
```

Résultat attendu : aucun problème et 160 tests réussis. Même chose dans `apps\admin`.

---

## 5. Les services externes

Chaque clé s'enregistre sur **son propre PC** avec le script indiqué. Elle est protégée par Windows pour ce compte uniquement : une clé copiée d'un autre PC ne marche pas.

| Service | Rôle | État au 23 novembre 2026 | Script | Compte |
|---|---|---|---|---|
| **MoneyFusion** | Paiements et retraits Mobile Money (MTN, Orange), 3 % de frais | **Retenu.** Branché et testé avec un faux serveur. **Aucun mode d'essai : tout prend du vrai argent.** L'adresse IP du serveur doit être déclarée dans l'application MoneyFusion | `tools\save-moneyfusion-keys.ps1` | au nom du fondateur, en création |
| CamPay | Paiements (démo seulement) | En réserve : le réel exige une entreprise (RCCM) | `tools\save-campay-keys.ps1` | fondateur |
| Fapshi | Paiements (bac à sable) | En réserve : compte réel non validé | `tools\save-fapshi-keys.ps1` | fondateur |
| Notch Pay | Paiements | En réserve, jamais essayé en vrai | (clés dans l'environnement) | aucun |
| **Cooms** | SMS des codes de connexion | **Retenu.** Branché, **vrai envoi pas encore essayé** | `tools\save-cooms-key.ps1` | fondateur |
| SmsPro | SMS | En réserve : exige un NIU | `tools\save-smspro-key.ps1` | fondateur |
| Google Maps | Carte, adresses, distances par la route | Branché | `tools\save-google-maps-keys.ps1` | fondateur |
| cloudflared | Lien temporaire `https://…trycloudflare.com` pour un essai hors Wi-Fi | Gratuit, sans compte | `tools\start-test-link.ps1` | aucun |

Pour travailler sur ces services, deux possibilités : le fondateur donne accès à ses tableaux de bord, ou la personne qui reprend crée ses propres comptes d'essai. **Jamais d'envoi de clé par message.**

L'ordre de priorité au démarrage local est : MoneyFusion, puis CamPay, puis Fapshi, puis les paiements simulés. Pour les SMS : Cooms, puis SmsPro, puis le fichier local. Le démarrage affiche le mode choisi (« Paiements : ... », « SMS : ... »).

---

## 6. Ce qui reste à faire

Par ordre de priorité, au 23 novembre 2026 :

1. **MoneyFusion** : le fondateur finit de créer l'application, avec le nom WAKA, le logo `apps/mobile/web/icons/Icon-512.png` et l'IP du serveur, puis enregistre le lien API et la clé privée. Au **premier vrai paiement** (500 F), vérifier que le montant renvoyé (`Montant`) est bien le prix complet et non le montant après frais. Sinon, ajuster `MoneyFusionProvider.payment`. Les choix prudents à confirmer sont listés dans [docs/decisions/README.md](../decisions/README.md).
2. **Hébergement** : le choix est en attente du fondateur. Deux pistes :
   - Oracle Cloud « Always Free » : gratuit, 2 processeurs, 12 Go, IP fixe, carte bancaire demandée pour vérification ;
   - OVHcloud VPS-1 : environ 5,40 € par mois sans engagement.

   Dans les deux cas, prévoir Java, MySQL, le pare-feu, les sauvegardes et HTTPS (Cloudflare), puis déclarer l'IP chez MoneyFusion.
3. **SMS réels** : essayer Cooms avec un vrai numéro camerounais (démarrer sans `-CodesInFile`). L'API SMS d'Orange ferme le 1er décembre 2026.
4. **Messagerie dans l'application** (demande du fondateur), avec les réglages par défaut retenus :
   - entre chauffeur et client : ouverte dès l'acceptation de la course, active jusqu'à 1 h après la fin, puis en lecture seule, sans numéros de téléphone ;
   - un fil chauffeur et support WAKA ;
   - texte seulement, 500 caractères au plus, réponses rapides.
5. **Play Store** : compte développeur Google (25 $ une fois, accord du fondateur nécessaire), signature de l'application, fiche, **politique de confidentialité**.
6. **Version web iPhone** en ligne, sur Cloudflare.
7. **Loi camerounaise sur les données personnelles (2024)** : vérifier les conditions de stockage hors du Cameroun avant le lancement public.
8. Plus tard : connexion par Google ou par code e-mail (optionnelle), deuxième serveur quand le trafic le demandera.

Le détail des décisions déjà prises et des questions encore ouvertes est dans [docs/decisions/README.md](../decisions/README.md).

---

## 7. En cas de problème

| Symptôme | Cause probable | Solution |
|---|---|---|
| `Un fichier requis manque` au démarrage du serveur | Serveur pas compilé, ou base pas installée | Faire 4.4 puis 4.5 |
| `MySQL WAKA n est pas installe sur ce poste` | Base jamais installée | Faire 4.4 |
| `Un autre MySQL tourne deja` | Un autre MySQL (installé à part) occupe le port 3306 | L'arrêter dans les services Windows |
| `Arreter WAKA ... avant de reconstruire` | Le serveur tourne pendant la compilation | Ctrl+C dans sa fenêtre, puis recompiler |
| Connexion : réponse 429 | Trop de demandes de code (protection voulue) | Attendre une minute |
| Paiement MoneyFusion refusé, « IP non autorisée » | L'IP du PC ou du serveur a changé | Déclarer la nouvelle IP dans l'application MoneyFusion |
| Les scripts refusent de s'exécuter | Politique d'exécution de Windows | Toujours lancer avec `powershell -ExecutionPolicy Bypass -File ...` |
