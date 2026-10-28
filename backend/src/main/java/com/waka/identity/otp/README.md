# Preparation OTP persistante 05.02.a/b

Composants reutilisables sans bean Spring, controleur, compte, session ou SMS
automatiquement active. Construire explicitement `PersistentOtpService` exige un
`SmsGateway` reellement qualifie ; aucun adaptateur de simulation dans main.
Le candidat Orange eventuellement present dans le module voisin ne constitue ni
qualification, ni accord commercial, ni autorisation de depense.

## Construction et API interne

`OtpCrypto(byte[] hmacKey, byte[] encryptionKey, SecureRandom)` exige une cle HMAC
de 32 a 128 octets et une autre cle AES de 32 octets. Copies defensives des cles,
aucun repli, chargement, generation ou stockage de cle dans cette preparation.
Le futur cablage doit obtenir ces cles d'un coffre et traiter leur rotation avant
production. La classe ne fournit pas encore destruction/rotation des cles.
Toutes les instances doivent partager les memes cles pour partager la garde et
les empreintes. Une rotation non coordonnee peut changer ces identites : elle ne
doit jamais reinitialiser des quotas encore actifs ou autoriser le renvoi d'un
etat incertain. La procedure de rotation et de retention doit etre qualifiee
avant activation ; aucun changement automatique de cle n'existe ici.

`PersistentOtpService(JdbcTemplate, PlatformTransactionManager, OtpCrypto,
OtpPolicy, SmsGateway, Clock)` utilise une horloge serveur UTC tronquee aux
microsecondes, des UUID aleatoires, des requetes liees et DATETIME(6) interprete
explicitement en UTC. L'horloge ne doit pas reculer en exploitation. Le JdbcTemplate
et le gestionnaire transactionnel doivent viser la meme DataSource MySQL/InnoDB.

- `request(clientInstanceRef, requestUUID, phoneNumber)` normalise selon 05.01 et
  retourne seulement `Challenge(id, submission, expiresAt)`.
- `verify(challengeUUID, clientInstanceRef, verifyUUID, char[])` retourne une
  `Proof(challengeId, proofId, expiresAt)` interne, ephemere et liee au contexte.
  Cette preparation ne fournit aucun consommateur de preuve ni droit de session.
- `eraseExpiredSecrets()` est un entretien explicite. Aucun ordonnanceur n'est
  installe. Avant activation, brancher cet entretien pour effacer physiquement
  code chiffre/HMAC et contact inutiles a l'expiration meme sans nouvelle requete.
  La verification refuse deja toute expiration, independamment de cet effacement.

`clientInstanceRef` doit etre une reference opaque stable de 16 a 128 caracteres
ASCII lettres/chiffres/tiret/soulignement ; aucune empreinte materielle. Ce contexte
n'est pas une authentification. Ses protections de transport et de conservation
locale restent a construire avec l'API. Les cles de commandes sont des UUID.

## Protections realisees

La politique initiale est immutable : six chiffres aleatoires SecureRandom,
validite cinq minutes, cinq erreurs par challenge, 60 secondes entre demandes,
trois demandes par destination et cinq par contexte par fenetre glissante de
15 minutes ; dix erreurs par destination sur 15 minutes, conservees a travers
les nouveaux codes. `OtpPolicy(hourlyBudget)` exige explicitement 0 a 10 000
reservations par heure glissante ; zero interdit tout nouvel envoi. Ce plafond
est un nombre d'appels, pas un prix ni une autorisation de depense.

Une ligne `otp_gate` HMAC globale, verrouillee par SQL sous transaction
READ_COMMITTED, serialise admission, quota, reservation budget et verification,
y compris entre instances du service partageant la meme cle. Choix volontairement
simple a ce stade : le debit et la contention seront a mesurer avant ouverture.
Les compteurs sont reconstruits sur les lignes durables indexees, jamais en RAM.

La reservation et `SENDING` sont commises avant l'appel fournisseur. Aucun appel
externe dans la transaction ; un appel sous transaction englobante est refuse.
ACCEPTED signifie uniquement acceptation transport ; REJECTED exige une reponse
certainement non soumise ; exception, timeout, retour null ou resultat incertain
donnent UNKNOWN. Exception transport sans cause publique ni log. Une panne avant
mise a jour laisse SENDING : aucun renvoi automatique ni remboursement de budget.
UNKNOWN/SENDING bloquent toute nouvelle demande destination jusqu'a expiration.
Une nouvelle demande admise apres le delai invalide definitivement l'ancien code
ACCEPTED, meme si son propre transport devient UNKNOWN ou REJECTED ; aucun ancien
code n'est restaure. Un code UNKNOWN peut etre prouve si le SMS a effectivement
atteint le demandeur ; aucune reception n'est presumee de cet etat.

Les HMAC-SHA256 separent destinations, contextes, commandes, codes/challenges et
garde globale avec domaines et champs prefixes par longueur. Aucun hash simple
des six chiffres. Contact chiffre AES-GCM-256, nonce aleatoire 12 octets et UUID
challenge comme AAD. Le contact chiffre est efface apres le resultat transport ;
le code HMAC apres consommation, verrouillage, remplacement ou entretien expiration.
Les buffers de code/message sont copies puis effaces ; toString expurges et
exceptions a codes constants. La normalisation existante du contact utilise une
String transitoire ; Java ne garantit pas l'effacement de cette representation.

Meme demande/contexte/cle/destination : meme challenge sans envoi ni quota ; autre
destination pour la meme cle : conflit sans contact dans l'erreur. La repetition
exacte d'une verification retrouve la meme preuve et la meme expiration, y compris
si cette expiration est maintenant passee : cela ne rajeunit ni ne cree de droit.
Autre cle ou contexte apres consommation : refus. Les tentatives incorrectes sont
aussi idempotentes et leur compteur est commis avant le refus. Les inconnus,
contextes etrangers et challenges termines ne creent pas de lignes de verification.
La preuve de reprise conserve un HMAC lie au code, jamais le code en clair.

V3 cree uniquement quatre tables vides : `otp_gate`, `otp_challenge`,
`otp_rate_event`, `otp_verification`. Aucun utilisateur, secret, contact en clair,
code ou cle d'idempotence brute. La retention des preuves, quotas et reservations
ne recoit aucune duree arbitraire : pas de purge de ces lignes dans le service.
Une future politique qualifiee doit traiter retention et rotation ensemble.

## Verification et limites de preuve

`OtpCryptoTest` verifie localement crypto, cloisonnement, copies, effacement,
codes d'erreur et limites de politique. Il ne prouve pas les transactions SQL.
`OtpMySqlVerificationTest` ne s'active qu'avec `WAKA_VERIFY_MYSQL=true` et
`WAKA_DB_URL`, `WAKA_DB_USER`, `WAKA_DB_PASSWORD` presents, sur une base de test
MySQL deja migree a V3. Il ne migre pas le schema ni ne consulte audit/bareme.
Chaque test produit son namespace de garde par cle HMAC aleatoire puis supprime
seulement ses propres lignes par cette identite ; aucune suppression globale.

Les sept tests MySQL couvrent concurrence de demandes/verifications et budget,
reprise exacte, echecs durables, fenetres, limites, expiration a la microseconde,
conflict, remplacement et UNKNOWN/SENDING. Les transports sont exclusivement des
simulateurs en src/test. Une execution avec les tests MySQL ignores ne prouve
aucun de ces comportements SQL : consulter le bilan effectivement execute.
Ni les tests unitaires ni MySQL ne prouvent un SMS reel ou une qualification
operateur. Aucun achat, credit, abonnement, contact de personne ou appel SMS reel.
