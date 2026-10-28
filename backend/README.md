# Serveur WAKA

## Évolution 05.04.a — compte courant

`GET /api/v1/me` relit le titulaire et ses échéances après validation de la session. Aucun identifiant reçu dans l'URL ne permet de lire un autre compte. Le seul droit renvoyé ici est CLIENT, dérivé du compte actif ; profil modifiable et admissions restent ouverts. Voir [contrat et explication](src/main/java/com/waka/identity/api/ME.md). Sans assemblage `MeService`, la requête valide reçoit 503 ; sans preuve, 401. Le point n'active aucun canal SMS. Les paragraphes 05.03 ci-dessous conservent leurs résultats datés ; leur ancienne absence de `/me` est historique.

## État actuel — 05.03.b/c, 17 octobre 2026

La [connexion HTTP](src/main/java/com/waka/identity/api/README.md) est codée : demande de code, vérification liée au téléphone, ouverture/reprise de session et déconnexion ciblée. La reprise restitue le même jeton pendant une fenêtre bornée, grâce à un chiffré temporaire en base ; elle ne crée pas une deuxième session et ne prolonge pas ses échéances. V5 complète V4 sans réécrire les migrations appliquées.

L'assemblage fournisseur reste désactivé : sans `LoginService` configuré, les trois commandes bien formées répondent 503. Aucun fournisseur factice dans le code applicatif. `/me`, profil et rôles restent au point 05.04. La partie SMS reste en attente à la demande du fondateur.

Vérifications de ce lot : `verify` a exécuté 759 tests réussis et ignoré les 29 opt-in MySQL sur 788 ; les 29 ont ensuite tous réussi séparément avec MySQL et TLS. Total : **788 tests distincts réussis**, zéro échec. Douze contrôles supplémentaires sur le vrai serveur local non activé ont réussi. V1–V5 appliquées en dev/test, 19 tarifs inchangés, audit et tables OTP/sessions vides après nettoyage. Aucun appel Orange, compte réel, achat ou publication. Les détails et limites sont dans les bilans du dossier de suivi.

Les paragraphes suivants conservent l'historique des étapes antérieures ; leurs anciennes absences de routes et anciens nombres de tests ne décrivent pas ce nouvel état.

## Historique 05.02 et 05.03.a

Complement 05.02 : [mecanisme OTP](src/main/java/com/waka/identity/otp/README.md)
et [adaptateur candidat Orange](src/main/java/com/waka/identity/sms/orange/README.md)
prepares sans activation. V3 cree quatre tables OTP vides ; aucune route A01/A02,
session ou cle fournisseur n'est activee. Les tests de transport restent locaux.
La passe standard du 17 octobre rapporte 770 tests, dont 752 reussis et 18 MySQL
opt-in ignores. Les dix nouveaux tests SQL des sessions ont ensuite reussi
separement en TLS ; les huit autres opt-in ne sont pas relances. Les 18 essais
HTTP Orange restent locaux. Le bilan ne declare pas une reception SMS reelle.
La [saisie privee locale](../tools/windows/README.md) prepare les acces d'essai
sans les charger dans le serveur. Le controle personnel separe attend un clic
humain avant sa premiere demande SMS. Un mode separe execute uniquement le
second essai explicitement demande par le fondateur, sans effacer le premier
verrou. Le mode destinataire-03, demande separement, exige les deux premiers
verrous et n'autorise qu'un seul essai supplementaire ; dix tests factices
couvrent le controle et ces limites.
Aucune reception reelle ni qualification commerciale n'est prouvee par ces tests.

Le fondateur a mis la partie SMS en attente. Aucun appel fournisseur n'est
effectue pendant le nouveau [socle de sessions 05.03.a](src/main/java/com/waka/identity/session/README.md).
V4 est appliquee en dev/test : trois tables vides, preuves OTP relues sous verrou,
jetons opaques, echeances et revocation persistantes. Ce composant reste sans bean
ou route de connexion active ; les profils, roles, stockage appareil et reprise
apres reponse perdue restent a construire avant l'integration publique.

Socle local réalisé aux points 04.02–04.08, Java 21, Spring Boot 4.1.1 et Maven. Le dépôt contient aussi le précontrôle de format téléphone 05.01 et le moteur de tarifs implémenté aux points 07.01–07.05. Les états réels de compilation, base et contrôles figurent dans les bilans correspondants ; ce README ne remplace pas ces preuves.

**GET /api/v1/health** retourne HTTP 200 et un petit JSON :

```json
{"status":"UP"}
```

Cette reponse signifie uniquement que le processus HTTP repond. Le controle distinct **GET /api/v1/health/database** execute SELECT 1 : HTTP 200 avec status=UP si la connexion fonctionne, HTTP 503 avec status=DOWN sinon, sans cause interne exposee et sans cache. Le serveur possede desormais une route de format telephone et une configuration de tarifs ; elles ne creent ni compte utilisateur, ni reservation, ni paiement et n'activent aucun prestataire.

## Fichiers et chemin d'une requete

1. **pom.xml** decrit le projet Maven. Le parent officiel spring-boot-starter-parent fixe la version 4.1.1 et gere les versions des dependances/plugins. java.version=21 fixe la cible Java. Les starters webmvc, jdbc et flyway fournissent serveur, JdbcTemplate/pool de connexions et migrations automatiques. flyway-mysql apporte le support MySQL ; mysql-connector-j est le pilote JDBC. Ces deux derniers modules sont en scope runtime et leurs versions ne sont pas surchargees. Le plugin spring-boot-maven-plugin transforme le paquet jar en fichier executable ; son execution repackage est configuree par ce parent.
2. **src/main/java/com/waka/WakaApplication.java** est le point de depart. SpringApplication demarre le serveur ; @SpringBootApplication active sa configuration et recherche les composants sous com.waka. Le parametre args transmet les options de lancement, dont une surcharge de port.
3. **src/main/java/com/waka/operations/api/HealthController.java** recoit la requete technique. @RestController indique une reponse HTTP ; @GetMapping associe le chemin et la methode GET. MediaType annonce du JSON. La methode health renvoie une Map immutable contenant seulement la chaine status=UP, que Spring convertit en JSON. Aucun appel externe n'est effectue.
4. **src/main/resources/application.properties** contient les protections communes non secretes. server.address=127.0.0.1 limite l'ecoute a cet ordinateur. Le port est 8080, surchargeable par WAKA_SERVER_PORT ou une option de lancement. Les proprietes spring.web.error.* masquent message interne, details de validation, exception, trace et chemin dans les erreurs generiques. Les fichiers application-dev.properties/application-test.properties exigent les trois variables DB ; aucun identifiant ni URL de repli n'est versionne.
5. **operations/application/DatabaseHealthService.java** recoit JdbcTemplate par constructeur. queryForObject execute SELECT 1 ; DataAccessException devient seulement un resultat indisponible pour ce controle HTTP. Le service ne capture pas les erreurs de migration ou de demarrage. **operations/api/DatabaseHealthController.java** transforme ce resultat en HTTP 200/503 avec le petit JSON ; aucun detail de connexion ne passe dans la reponse.
6. **src/main/resources/db/migration/V1__create_audit_event.sql** cree uniquement une table d'infrastructure vide. V1 est la version ; le double underscore separe la description. Flyway enregistre l'execution et son empreinte dans sa propre table d'historique. Une migration deja appliquee ne doit pas etre reecrite pour cacher un changement : les evolutions passeront par une nouvelle version.

Les modules metier ne sont plus tous vides. [Identity](src/main/java/com/waka/identity/README.md) documente 05.01 : une validation syntaxique limitee au plan mobile camerounais, sans creation de compte, stockage, SMS, session ni route A01. [Pricing](src/main/java/com/waka/pricing/README.md) documente 07.01–07.05 : lecture et calcul a partir d'une grille versionnee, sans API publique de devis ou d'authentification. La migration V2 prepare la version `launch-v1` et ses 19 tranches en XAF ; ces lignes sont de la configuration tarifaire, pas des comptes, courses ou transactions, et aucune date de mise en vente n'est fixee. Les autres modules restent a developper selon leurs points. Les secrets et configurations locales sensibles restent hors Git.

## Connexion et migration locales

Le script `tools/start-dev-backend.ps1` appelle le helper prive prepare sur ce poste. Celui-ci fournit **WAKA_DB_URL, WAKA_DB_USER et WAKA_DB_PASSWORD uniquement au processus Java**. Les valeurs et le chemin local du truststore restent hors du depot, des captures et des commandes partagees. La cible dev est `waka_local` avec le compte limite `waka_dev` ; aucun compte administrateur n'est requis par le code applicatif. La cible test est la base distincte `waka_test` avec `waka_test_runner`. Les deux profils utilisent TLS `VERIFY_CA` et un truststore local valide. Le fichier versionne ne contient ni URL privee ni chemin personnel.

Sans configuration complete et valide et sans profil explicite, le serveur doit echouer au demarrage ; ne pas contourner cet echec en desactivant Flyway ou TLS. Le profil dev exige waka_local, le profil test exige la base distincte waka_test. La session JDBC utilise UTC ; les futures ecritures occurred_at devront fournir un instant UTC.

Flyway est active au demarrage et utilise la meme connexion configuree. Il valide l'historique puis applique les migrations manquantes. clean, creation automatique de schemas et baseline automatique sont desactives ; la base doit deja exister. spring.sql.init.mode=never empeche schema.sql/data.sql de creer un second chemin d'initialisation. ddl-auto=none exclut la creation Hibernate ; aucune dependance JPA/Hibernate n'est ajoutee. Un echec de connexion, validation ou migration ne doit pas etre transforme en serveur pret.

`audit_event` utilise InnoDB, utf8mb4 et des colonnes structurees : date UTC, type d'evenement, reference opaque d'acteur et fonction, objet, motif code, resultat et correlation. Aucun JSON libre, contenu de piece ou secret n'est attendu dans ces champs. L'identifiant numerique est interne a la table. V1 ne cree aucune ligne et aucune route n'ecrit encore dans cette table ; elle ne constitue donc pas un journal metier actif ou complet. Le minimum de journalisation technique defini en 04.07 est deja en place dans `src/main/resources/logback-spring.xml` : seul le logger operationnel prevu est affiche, sans journaux de fichiers ni piles d'exception des bibliotheques. Ce filtrage reduit les informations techniques exposees ; il ne remplace pas l'audit metier. La migration ne contient aucune suppression ; une erreur DDL devra etre examinee, sans clean ou repair automatique.

## 04.06.a — Garde avant toute connexion

**operations/infrastructure/LocalEnvironmentInitializer.java** est enregistre explicitement dans WakaApplication avant run. Spring charge la configuration, puis cet initializer la controle avant le rafraichissement du contexte et la creation du DataSource. Binder lit les proprietes effectives, y compris une surcharge de priorite superieure ; verifier uniquement les variables WAKA ne suffirait pas.

- Un seul profil actif est admis : dev ou test. Aucun profil par defaut ; absent, inconnu, multiple, prod ou staging sont refuses. Le nom de base est determine par le garde, jamais par un indicateur libre du telephone ou du lanceur.
- L'adresse HTTP doit rester exactement 127.0.0.1. L'URL doit etre une URL MySQL simple vers 127.0.0.1 et la base du profil ; multi-hotes, autre protocole, identifiant dans l'autorite ou parametre user/password sont refuses. Identifiants effectifs obligatoires, non vides et utilisateur root refuse.
- Les protections driver effectives exigent sslMode=VERIFY_CA, connectTimeout=5000 et socketTimeout=5000 (millisecondes). Les doublons de parametres et valeurs contradictoires entre URL et proprietes du driver sont refuses ; aucun repli sur un TLS moins strict.
- Le pool est limite a trois connexions, dont une minimale inactive. Attente d'acquisition : cinq secondes ; validation : trois secondes ; JdbcTemplate : trois secondes de delai de requete. Ces bornes locales ne constituent pas une garantie de disponibilite de MySQL.
- Connexions alternatives Hikari/JNDI/Flyway, changement de catalogue/schema et SQL initial de connexion sont refuses. Flyway doit rester actif et valide, sans clean, baseline, creation de schema, cible limitant les migrations ni saut de leur execution. Les scripts SQL automatiques et DDL Hibernate restent desactives.
- Initialisation paresseuse et exclusions d'autoconfiguration ne peuvent pas reporter ou supprimer ces controles. Le garde ne cree aucun compte/base et ne capture pas une erreur de migration pour continuer.

Les erreurs de ce garde contiennent seulement un code fixe, sans valeur privee ni cause de conversion pouvant la reveler. Le minimum de journalisation 04.07 est effectif : `logback-spring.xml` n'active pas les journaux des bibliotheques et n'ecrit pas dans un fichier. Les journaux operationnels restent a examiner dans leur contexte local ; ne pas y copier d'URL, de chemin personnel ou de valeur de configuration dans un bilan public.

**src/test/java/com/waka/operations/infrastructure/LocalEnvironmentInitializerTest.java** verifie en memoire les profils dev/test, les refus de profils/cibles, les surcharges de priorite superieure, les protections et l'absence de valeur privee dans les erreurs. `spring-boot-starter-test` est utilise en scope test avec les versions gerees par le parent. Ces controles n'ouvrent aucune connexion et ne sont pas des tests d'integration MySQL. Le `verify` serveur execute par le coordinateur a termine avec 715 tests reussis et un test opt-in saute sur 716. Ce resultat ne prouve pas a lui seul l'application de V2 sur la base dev.

## Construire et lancer

Prerequis : le JDK 21, Maven et le cache de dependances retenus pour WAKA doivent etre accessibles. Depuis la racine du depot correct, renseigner dans le terminal les variables locales non secretes `$wakaJavaHome`, `$wakaMavenHome` et `$wakaCacheDirectory` avec les chemins des installations et du cache deja prepares. Ne pas ecrire leurs chemins personnels dans le depot. Le script de construction appelle Maven avec `backend/.mvn/settings.xml`, qui dirige les dependances publiques vers Maven Central ; il lance `verify`, utilise le cache configure et peut fonctionner sans reseau lorsque tout est deja en cache. Il refuse de reconstruire le JAR tant que ce meme JAR est actif.

```powershell
& .\tools\build-backend.ps1 -JavaHome $wakaJavaHome -MavenHome $wakaMavenHome -CacheDirectory $wakaCacheDirectory
```

Si le cache contient toutes les dependances, ajouter `-Offline` a cette commande evite une tentative d'acces reseau. Sinon Maven peut recuperer gratuitement les artefacts publics declares. `target` contient les fichiers generes et reste exclu de Git. Un echec de construction ne constitue pas un serveur demarre.

Apres une verification reussie du JAR, demarrer le serveur depuis la racine avec le lanceur local prepare. Il lit les identifiants proteges par Windows via le helper prive du coffre, les expose uniquement au processus Java et restaure l'environnement du terminal a l'arret. Il ne recoit aucun mot de passe dans ses parametres.

```powershell
& .\tools\start-dev-backend.ps1 -JavaHome $wakaJavaHome -Profile dev
```

Le profil `dev` utilise la base locale `waka_local` et le compte limite `waka_dev`. Le profil `test` selectionne la base distincte `waka_test` et `waka_test_runner` : il ne pointe pas vers la base dev. Les deux profils ont demarre avec V1 et V2 : 19 tranches comparees a l'oracle independant, audit vide et controles HTTP locaux reussis. Le script prepare un truststore local a partir du certificat public CA, utilise TLS `VERIFY_CA` et garde les valeurs secretes hors parametres, captures et Git.

Dans un second terminal, consulter les controles techniques locaux :

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/health' -Method Get
Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/health/database' -Method Get
```

La valeur attendue de status est UP pour les deux controles lorsque MySQL est disponible. Le script accepte aussi `-Profile test` ou un port different avec `-Port`. Pour changer le port, arreter d'abord le serveur avec Ctrl+C puis relancer le script. Ne pas demarrer une seconde copie sur le meme port.

```powershell
& .\tools\start-dev-backend.ps1 -JavaHome $wakaJavaHome -Profile test -Port 8081
```

La surcharge de port ne change pas l'adresse locale par defaut. Les verifications doivent controler les reponses attendues des deux routes de diagnostic, le POST de format téléphone avec des donnees synthetiques, l'absence de route publique de devis/compte, l'adresse d'ecoute et la liberation du port apres arret. HEAD sans corps et OPTIONS sont geres normalement par Spring MVC autour des routes GET ; ils ne creent aucune action metier.

## References et suite

Sources officielles consultees le 15 octobre 2026 :

- [Starters Spring Boot](https://docs.spring.io/spring-boot/4.1/reference/using/build-systems.html) : starter webmvc retenu pour cette version.
- [Archives executables](https://docs.spring.io/spring-boot/4.1/maven-plugin/packaging.html) : plugin, parent et repackage.
- [Proprietes Spring Boot](https://docs.spring.io/spring-boot/appendix/application-properties/) : adresse, port et spring.web.error.
- [Initialisation des donnees](https://docs.spring.io/spring-boot/how-to/data-initialization.html) : starter Flyway, support MySQL et migrations au demarrage.
- [Dependances gerees](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html) : versions fournies par le BOM 4.1.1.

Voir l'[architecture](../docs/architecture/WAKA_architecture_minimale.md) et les [specifications](../docs/README.md). Les essais reels de connexion/migration et leurs limites sont rapportes par le coordinateur. Ce socle local ne constitue ni une publication, ni une validation commerciale ou de securite de WAKA.
