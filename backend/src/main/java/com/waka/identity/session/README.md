# Sessions persistantes — 05.03.a/b

## Évolution 05.03.b, 17 octobre 2026

`openOrReplay` complète l'ouverture initiale. Une commande lie la preuve OTP interne, le défi, le contexte appareil, le téléphone normalisé et la clé de vérification. L'appelant doit repasser par `verifyBound` à chaque tentative : voir le [raccord HTTP](../api/README.md). Une preuve n'est jamais acceptée depuis le JSON du mobile.

`SessionRecoveryCrypto` chiffre le jeton avec AES-256-GCM et une clé dédiée, distincte de celle des comptes. L'authentification du chiffré lie aussi la commande et la session ; mauvaise clé, données modifiées ou incohérence provoquent un refus. Les clés sont injectées explicitement, jamais générées comme repli dans le serveur. `SessionRecoveryPolicy` exige une durée positive explicite. Les durées utilisées par les tests ne sont pas des valeurs de production adoptées.

`V5__create_session_response_recovery.sql` ajoute la reprise et la clé de révocation. Création du compte, session et chiffré de réponse sont atomiques. La même commande peut retrouver le même jeton, même après recréation du service, au plus jusqu'à la première échéance parmi fenêtre de reprise, preuve OTP, inactivité initiale et vie absolue. La reprise ne renouvelle aucune échéance. Une clé réutilisée avec d'autres entrées est refusée.

`revoke(sessionId, token, client, key)` contrôle la cible et persiste sa commande. La reprise exacte reste possible après révocation ; une autre clé sur une session terminée est refusée, une clé déjà utilisée pour une autre cible ne produit pas une nouvelle révocation. Le chiffré de reprise est effacé lorsque le service constate une session terminée ou un compte désactivé. `eraseExpiredRecoverySecrets` permet également l'entretien des chiffrés expirés dans la seule garde concernée. **Cet entretien doit être planifié avant activation** ; aucune tâche de production n'est prétendue active.

La suppression du chiffré conserve le marqueur de commande et d'usage de preuve. La rétention complète et la rotation des clés restent à qualifier. Les anciens constructeurs et `open` conservent leur comportement à usage unique ; ils ne proposent pas de récupération de réponse.

Vérifications ajoutées : trois tests cryptographiques et onze tests MySQL/HTTP en mémoire, tous réussis, dont six vérifications concurrentes, panne après consommation OTP, rollback d'une erreur de chiffrement, reprise du même jeton, téléphone incompatible, compte désactivé, échéances et déconnexion. Les dix anciens tests MySQL sessions ont aussi réussi sur V5. L'installation HTTP ordinaire répond désormais 503 sur les commandes valides non configurées ; le contrôle historique `/auth` en 404 ci-dessous ne s'applique plus à ces routes.

## Référence de la phase 05.03.a

Les sections suivantes expliquent le socle initial. Ses mentions « sans contrôleur » et « reprise à construire » sont historiques ; l'évolution ci-dessus et le guide HTTP font foi pour l'état actuel.

Preparation executable du serveur, sans activation d'authentification publique.
Aucun bean, controleur, filtre de connexion, profil utilisateur, role administratif
ou appel fournisseur n'est ajoute. La partie SMS reste en attente. Les tests ne
prouvent ni reception d'un SMS ni qualification d'un operateur.

## Ce qui est construit

- **SessionPolicy** impose deux durees explicites : vie absolue et inactivite.
  Aucune valeur de production par defaut ; durees positives a la microseconde,
  inactivite au plus egale a la vie absolue. Les trente/dix secondes des tests
  sont uniquement des valeurs de verification.
- **SessionCrypto** recoit une cle HMAC stable dediee a l'identite, en memoire,
  distincte des cles OTP. Les recherches de telephone, contextes et gardes ont
  des domaines separes. Les tokens utilisent 32 octets de SecureRandom, encodes
  en Base64URL canonique sans padding. Seule leur empreinte SHA-256 avec domaine
  separe est stockee ; elle ne permet pas de restituer le token.
- **SessionGrant** contient temporairement le token en char[]. Son affichage est
  expurge et close efface son buffer. token() retourne une copie : l'appelant
  doit aussi l'effacer. Ne jamais journaliser ou afficher un token.
- **PersistentSessionService** fournit uniquement open, authenticate et revoke.
  Un sujet authentifie n'est pas une habilitation chauffeur, finance ou admin.
- **PersistedOtpProofVerifier**, dans identity/otp, est le pont etroit vers les
  preuves OTP existantes. Aucun helper HMAC generique n'est rendu public.
- **V4__create_persistent_sessions.sql** cree trois tables vides : garde
  identity_session_gate, compte technique identity_account, session identity_session.
  Aucun compte ou token n'est insere par la migration.

## Comment une session est creee

Le code appelant fournit la preuve OTP interne, le meme clientInstanceRef opaque
et le telephone a normaliser. Ce dernier n'est pas cru sur parole.

Le service prend la garde InnoDB de session, puis verrouille le challenge OTP.
Il relit en base challengeId, proof_id, contexte, destination, expiration et
lifecycle=CONSUMED. L'etat de soumission doit etre ACCEPTED ou UNKNOWN ; ce dernier
n'est pas une preuve de livraison, mais un code effectivement verifie peut avoir
ete recu malgre un retour transport incertain. SENDING/REJECTED sont refuses,
meme si une ligne incoherente porte CONSUMED.

Le contexte et le telephone normalise doivent correspondre a leurs HMAC OTP.
L'expiration du DTO Proof est ignoree : la date en base et l'horloge serveur
decident. Connaitre un UUID, fabriquer un objet Proof ou choisir un autre
telephone ne suffit donc pas a obtenir une session.

Dans **la meme transaction courte**, le service retrouve ou cree le compte par
HMAC de contact stable, puis insere la session. Les contraintes UNIQUE sur le
challenge et la preuve constituent le marqueur durable d'usage unique. La FK
composite gate_hash/account_id empeche de rattacher une session a un compte
d'une autre garde. Une erreur SQL annule ensemble creation du compte et session.

Le verify OTP existant conserve sa transaction propre et ses compteurs durables.
Il ne forme pas une transaction unique avec open : la preuve qu'il a deja creee
peut etre consommee tant qu'elle reste valide. Un echec de open ne doit ni relancer
un SMS ni inventer un OTP. Une nouvelle tentative interne peut utiliser la meme
preuve si aucune session n'a ete commise et si la preuve n'a pas expire.

Les comptes ne stockent ici **aucun telephone complet**, seulement un HMAC de
recherche. ACTIVE signifie compte technique utilisable, sans admission ou role.
La verification du telephone ne prouve ni identite civile ni unicite d'une
personne physique. La restitution/protection d'un contact dans le futur profil
reste a construire : un HMAC ne permet pas d'envoyer un message a lui seul.

## Controle, delais et deconnexion

authenticate exige token et contexte. Le serveur recherche l'empreinte, controle
le compte ACTIVE, la fin de session et les deux echeances. Les intervalles sont
[debut, fin[ : a l'instant exact d'une echeance, le token est refuse.

Une activite valide renouvelle seulement l'echeance d'inactivite, bornee par la
date absolue initiale. La duree d'inactivite est conservee dans chaque ligne ;
changer SessionPolicy ne reprogramme pas les sessions deja creees. Les dates
sont UTC a la microseconde.

Une expiration observee est marquee EXPIRED en base avant le refus. revoke
inscrit REVOKED durablement ; sa repetition exacte reste sans nouvel effet.
Un contexte etranger ne peut pas deconnecter la session. Un compte desactive ne
peut pas etre authentifie ni recevoir une nouvelle session. Aucun outil
d'administration de compte n'est fourni ici.

La garde conserve aussi la derniere heure d'operation commise. Un recul d'horloge
provoque SERVICE_UNAVAILABLE au lieu de rajeunir une session. Cela ne remplace
pas la qualification de l'horloge serveur avant exploitation.

## Construction et limites avant activation

Construire explicitement le service exige JdbcTemplate, DataSourceTransactionManager,
PersistedOtpProofVerifier, SessionCrypto, SessionPolicy et Clock. Le service,
l'adaptateur et le gestionnaire doivent partager **la meme DataSource** et la meme
instance Clock ; une incoherence est refusee avant connexion. L'adaptateur exige
une transaction reelle sur cette DataSource. Les trois operations du service
refusent une transaction englobante et ouvrent leur propre transaction courte
READ_COMMITTED. Aucun appel externe n'est effectue.

Ordre de verrouillage : SessionGate → challenge consomme pour open → compte/session.
Il n'est pas necessaire de reprendre la garde globale OTP, puisque le challenge
consomme n'est pas modifie. Toutes les instances partageant la meme cle stable
partagent la meme garde de sessions. Cette serialisation est volontaire et
simple ; sa contention devra etre mesuree avant ouverture.

Les cles sont fournies en memoire, sans generation/configuration de cle dans main.
Leur stockage, recuperation et rotation restent a qualifier. Remplacer la cle
d'identite sans migration casserait la recherche des comptes et la garde ; ne pas
reutiliser une cle OTP susceptible de tourner comme cle durable de compte.
Les classes copient leurs cles mais ne fournissent pas encore destruction ou
rotation coordonnee. Aucun fallback aleatoire n'est installe.

**La phase a n'est pas le contrat A02 complet.** Une preuve ayant cree une session
ne restitue pas un nouveau token : PROOF_ALREADY_USED. Si le retour initial est
perdu, le hash seul ne permet pas de retrouver le secret. Reprise idempotente
sure, rotation/renouvellement, transport HTTPS et stockage appareil doivent etre
qualifies en phase b avant routes de connexion. Aucun refresh ou revokeAll
n'est ajoute par anticipation.

Les lignes de session conservent le marqueur d'usage de preuve ; aucune purge
n'est fournie. La future retention doit preserver les refus de rejeu et traiter
les FK OTP, sans retention illimitee presumee. Reclamation d'identite, changement
de numero et recuperation de compte restent aux fonctions correspondantes.

## Verification reproductible

Depuis backend, avec le JDK 21 et Maven deja prepares, utiliser les parametres
Maven publics du projet. Si le cache est complet :

```powershell
mvn -B -ntp -C -o -s .mvn/settings.xml -gs .mvn/settings.xml "-Dmaven.repo.local=$env:LOCALAPPDATA/WAKA/caches/maven" "-Dtest=SessionCoreTest" test
```

Les **cinq tests SessionCoreTest** n'ouvrent aucune connexion : politique,
format/canonicalite du token, domaines/copie de cle, buffers et expurgation,
coherence des dependances et refus de l'adaptateur hors transaction.

Les **dix tests SessionMySqlVerificationTest** sont ignores sauf si le processus
recoit WAKA_VERIFY_MYSQL=true et les variables privees WAKA_DB_URL/USER/PASSWORD.
Le coordinateur prepare ces valeurs en memoire, sans les saisir dans une
commande partagee. La base waka_test sur 127.0.0.1:3306 doit etre deja migree a V4
avec TLS VERIFY_CA ; le test ne lance aucune migration ni modification de runtime.
Dans cet environnement prive uniquement :

```powershell
mvn -B -ntp -C -o -s .mvn/settings.xml -gs .mvn/settings.xml "-Dmaven.repo.local=$env:LOCALAPPDATA/WAKA/caches/maven" "-Dtest=SessionMySqlVerificationTest" test
```

Ces tests utilisent le vrai core OTP avec un transport de capture exclusivement
dans src/test. Ils couvrent preuve reelle du core, usage concurrent unique,
contexte/contact/UUID et expiration falsifies, etats transport incoherents,
inactivite/vie absolue, recul d'horloge, revocation/recreation de service, compte
desactive et rollback SQL conjoint. Ils ne contactent jamais un prestataire.

Une cle HMAC aleatoire par test cree son namespace ; toutes les instances d'un
meme test partagent ses cles et gardes. Nettoyage filtre : sessions → comptes →
garde de session, puis verification/rate/challenge/garde OTP de ce seul test.
Aucun DELETE global, aucune modification de bareme ou audit. Un test ignore
ne prouve aucun comportement MySQL.

Kora n'a execute ni Maven ni SMS pendant cette realisation. Les resultats reels
de compilation, migration V4, tests et HTTP restent au bilan du coordinateur.
Le maintien de /auth en 404 doit etre verifie pour prouver la nonactivation.

