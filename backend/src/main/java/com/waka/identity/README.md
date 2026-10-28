# Identite — precontrole de format 05.01

Le controle pur `POST /api/v1/identity/phone-format` recoit uniquement un objet JSON
avec un champ `phoneNumber` de type chaine. Aucun compte, code SMS, session,
cookie, role ou droit n'est cree. Aucun acces MySQL, stockage, appel prestataire,
log du contact ou achat. La representation normalisee reste seulement en memoire.

La convention couvre les mobiles du Cameroun : neuf chiffres nationaux dont le
premier est 6, eventuellement precedes de +237 ou 00237. Les separateurs admis
sont espace ASCII U+0020, espace insecable U+00A0, espace fine insecable U+202F,
tiret ASCII, parentheses ouvrante et fermante. Ce sont des caracteres de
presentation supprimes, sans interpretation d'un groupe entre parentheses.
Les autres espaces, controles, lettres, chiffres Unicode, extensions, fixes
commencant par 2, prefixes doubles et autres territoires sont refuses.
La limite de 40 caracteres est appliquee avant suppression des separateurs.
Aucune inference d'operateur depuis un prefixe.

Cette convention de syntaxe s'appuie sur le [plan Cameroun publie par l'UIT](https://www.ituob.org/issues/1063-en/sectionCameroon)
et la [fiche pays UIT](https://www.itu.int/oth/T0202000024/fr), consultes pour le
point du 15 octobre 2026. Le controle ne prouve ni attribution, ni joignabilite,
ni possession du numero. Il ne constitue jamais une authentification.

Le corps est borne a 512 **octets effectivement lus**, meme sans Content-Length :
lecture de 513 octets maximum, puis 413 si depassement. Un objet avec champ
inconnu, doublon, valeur nulle, mauvais type, plusieurs racines ou JSON malforme
donne 400. Le type exige est application/json. Le parseur Jackson 3 deja present
lit les jetons strictement ; aucune nouvelle dependance.

Succes 200 : `{"formatValid":true,"ownershipVerified":false,"country":"CM"}`.
Le contact n'est jamais renvoye. Succes et erreurs portent `Cache-Control: no-store`.
Les erreurs passent par l'enveloppe sure existante 04.07 : 400 INVALID_REQUEST,
413 FILE_TOO_LARGE (code generique existant), 415 CONTENT_TYPE_UNSUPPORTED.
Les exceptions locales ne contiennent que des codes constants, sans valeur recue.

Ce precontrole est une exception explicite au besoin de cle des **commandes metier**
K04 : il n'a aucun effet rejouable, objet cree, SMS, audit metier ou donnees durables.
Il ne demande donc pas Idempotency-Key. Le contrat general K04 et ses exigences
pour A01 et les autres commandes restent applicables. Aucun stockage de cle ou
TTL n'est invente. L'emission de code A01 et la preuve de possession 05.02 restent
a construire apres qualification du canal et de ses couts, avec accord avant depense.

`application/CameroonMobilePhoneFormat.java` porte la regle sans infrastructure ;
`api/PhoneFormatController.java` borne et lit la requete puis restitue le seul
verdict. Les tests `CameroonMobilePhoneFormatTest` et `PhoneFormatControllerTest`
utilisent des chaines synthetiques construites ; aucune attribution/joignabilite
n'est presumee et aucun message n'est envoye. Les tests MockMvc restent en memoire,
y compris la simulation d'une longueur inconnue : ils ne prouvent pas un transfert
HTTP chunked reel. Les controles HTTP du vrai JAR sont consignes dans le bilan
de coordination lorsqu'ils ont effectivement ete executes.

Verification, depuis le dossier `backend` du depot : `mvn -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml verify`.
Cette commande est une instruction reproductible ; seul le bilan d'execution
confirme les tests effectivement passes.

## Preparation gratuite suivante 05.02

Le [mecanisme OTP persistant](otp/README.md) et le [client candidat Orange](sms/orange/README.md)
sont disponibles comme composants explicites, sans bean ni route d'envoi activee.
La migration V3 cree quatre tables vides. Des transports de test uniquement dans
src/test permettent de verifier transactions, limites et concurrence sans SMS.
Le formulaire 05.01 reste le seul parcours mobile realise dans ce module :
aucune session, compte ou preuve de possession n'est accordee par ces preparations.
La qualification de l'expediteur, l'acces fournisseur et l'accord sur tout cout
precedent le raccordement reel de 05.02.c.2 ; les identifiants ne vont jamais dans Git.
