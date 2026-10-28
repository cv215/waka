# Candidat Orange SMS Cameroun — adaptateur non active

Ce composant prepare gratuitement un raccordement possible. Il n'est ni un bean
Spring, ni un service active, ni une offre commercialement retenue. Il n'existe
aucun branchement depuis les routes de l'application ; aucune cle fournisseur
n'est lue depuis une variable, un fichier ou une valeur de repli. Construire ce
composant n'effectue pas de requete. Ne pas le brancher tant que les conditions
du fournisseur, la reception sur les reseaux vises et le cout ne sont pas qualifies.

## Chemin d'un envoi futur

`OrangeSmsGateway` implemente le port `SmsGateway` du mecanisme OTP persistant.
Le service doit reserver le budget avant d'appeler ce port hors transaction.
`SmsMessage` fournit des copies ephemeres du contact et du code ; elles sont
effacees apres l'appel. Aucun code ou message fournisseur ne devient une reponse
d'authentification. `ACCEPTED` signifie seulement creation acceptee par le
transport ; il ne signifie ni reception du SMS ni verification du proprietaire.

Le constructeur public exige les credentials serveur et le nom d'expediteur qui
devra etre reellement approuve. La fabrique explicite `usingDefaultSender`
omet `senderName` pour utiliser le nom par defaut du compte, seulement si ce mode
est effectivement autorise au portail. Elle n'invente pas un nom WAKA autorise.
Le constructeur personnalise continue de refuser un nom absent : aucun repli
silencieux. La construction des deux modes n'effectue aucun appel.
Les adresses HTTPS Orange sont fixes, sans
redirection ni proxy choisi depuis une configuration utilisateur. Le point de
test non public accepte seulement HTTP sur 127.0.0.1 avec port explicite ;
aucune propriete Spring ou saisie mobile ne peut le selectionner.

Le client emploie OAuth v3 et garde le jeton jusqu'a l'expiration publiee moins
une marge de trente secondes. Une reponse 401 invalide ce cache mais ne provoque
pas de deuxieme POST SMS. Les autres erreurs d'envoi ne declenchent pas de retry
automatique. Apres debut de POST, tout statut autre que 201, y compris les 4xx
(408/409 compris), reste prudemment `UNKNOWN` : le statut seul ne prouve pas la
non-livraison. Resultat 5xx, redirection, erreur reseau,
corps inattendu ou depassement de delai apres debut d'envoi : `UNKNOWN`. Une panne
OAuth avant toute tentative SMS peut donner `REJECTED`, puisqu'aucun POST SMS
n'a ete tente. Dans tous les cas, le service conserve sa reservation de budget.

Les reponses sont bornees a 8 KiB et le delai total par requete est huit secondes,
corps compris ; la connexion est bornee a trois secondes. OAuth puis SMS peuvent
donc demander deux requetes successives. Un SMS genere utilise seulement des
caracteres ASCII simples et tient dans 160 caracteres. Ce calcul technique ne
remplace pas la qualification des unites effectivement facturees par l'offre.

Les buffers controlables et le cache sont effaces a la fermeture ou a leur fin
d'utilisation. Java et son client HTTP peuvent produire des copies et Strings
temporaires : il n'est pas garanti que tout l'historique memoire soit effacable.
Les journaux ne recoivent jamais credentials, jeton, contact, code ou corps.

## Verification et limites

Le controle personnel [Windows](../../../../../../../../../tools/windows/README.md)
emploie `PersonalSmsCheckMain` et `PersonalSmsCheck` hors Spring, par flux prives.
Il demande une confirmation locale du titulaire et reserve durablement un seul
envoi avant tout appel fournisseur. Aucune session ou route HTTP n'est ouverte.
Le resultat de cet essai personnel ne valide pas le parcours OTP persistant ni
l'autorisation commerciale. Les tests `PersonalSmsCheckTest` ont un transport
factice, sans envoi externe ; la production utilise toujours le vrai client Orange.

`OrangeSmsGatewayTest` emploie un serveur HTTP local exclusivement dans les tests.
Il controle protocole, cache/expiration du jeton, rejections, absence de retry et
redirection, reponses malformees/trop grandes et corps trop lent. Ce serveur
n'envoie aucun SMS et ne constitue pas une preuve de livraison ou de couverture.
Les delais sont raccourcis dans le seul constructeur de test pour tester les
timeouts. La production n'a pas de mode de code fixe ni de fournisseur fictif.

Avant activation : credentials officiels au coffre, expediteur autorise, canal
OTP admis, budget accepte, controles de debit globaux/pacing selon l'offre,
destinataires d'essai autorises et moyen de reception reel. L'adaptateur ne cree
pas de contrat, n'achete pas de lot, ne declare pas une livraison et n'ouvre pas
de session. Il ne remplace pas une authentification renforcee de l'administration.

References officielles consultees le 15 octobre 2026 :

- [Demarrage et authentification](https://developer.orange.com/apis/sms/getting-started).
- [API SMS Cameroun tous operateurs](https://developer.orange.com/apis/sms-cm/api-reference).
- [Tarifs et validite des lots](https://developer.orange.com/apis/sms-cm/pricing).
- [FAQ et qualification de livraison](https://developer.orange.com/apis/sms-cm/faq).

Le swagger comporte un schema HTTP ancien ; le constructeur de production
retient exclusivement les adresses HTTPS du guide courant. L'offre Orange Only
et Messaging Pro sont distinctes et ne sont pas silencieusement utilisees ici.
