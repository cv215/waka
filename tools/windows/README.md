# Acces local pour un futur essai SMS personnel

`Initialize-WakaSmsTestAccess.ps1` ouvre une fenetre de saisie locale. Il ne
contacte pas Orange, ne verifie pas les identifiants et n'envoie aucun SMS.
Il n'est ni un ecran client de WAKA, ni une configuration de production.

Depuis la racine du depot, dans le terminal PowerShell de VS Code :

```powershell
& "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -STA -File .\tools\windows\Initialize-WakaSmsTestAccess.ps1
```

Saisir les identifiants depuis la console officielle Orange et le numero de test
au format international +237 puis neuf chiffres commencant par 6. Ne jamais
placer les valeurs dans le chat, des commandes, captures, variables d'environnement
ou fichiers du depot. Les trois champs sont masques ; seuls leurs formats sont
controles. Le bouton enregistre ces valeurs sur ce PC, sans envoi. La case confirme
l'accord du titulaire pour preparer l'essai, sans autoriser un envoi automatique.

Le fichier `access.dpapi` se trouve sous le dossier LocalApplicationData de
l'utilisateur, dans WAKA/Secrets/sms-test, hors du depot. DPAPI CurrentUser chiffre
les donnees ; le dossier a des droits limites au compte courant, SYSTEM et aux
Administrateurs. Les liens de fichiers sont refuses. Seul le chiffre est ecrit,
via un fichier temporaire prive puis un deplacement qui refuse l'ecrasement.
Les erreurs n'affichent aucun champ ; un fichier existant est conserve.

Limites : cette preparation est liee au profil Windows. Elle ne remplace pas le
coffre operationnel ni sa sauvegarde independante, encore a organiser avant la
production. Les credentials de test doivent rester recuperables ou revocables
depuis le compte Orange. Aucun effacement instantane des copies memoire .NET
n'est garanti. Aucun test sous un autre compte Windows n'est pretendu.

Les valeurs existantes ne peuvent pas encore etre corrigees par cette interface.
En cas d'erreur apres enregistrement, conserver le fichier chiffre et traiter sa
rotation explicitement avant usage ; ne pas le remplacer ou le supprimer a l'aveugle.
Le lecteur de l'essai personnel ci-dessous refuse une preparation vieille de plus
de sept jours. Aucun service HTTP WAKA ne charge ces donnees. Le fichier chiffre
reste sur ce PC ; expiration et fermeture ne l'effacent pas. Une rotation/suppression
explicite et la sauvegarde independante restent a organiser avant la production.

Controle sans fenetre et avec donnees techniques uniquement :

```powershell
& "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -File .\tools\windows\Initialize-WakaSmsTestAccess.ps1 -SelfTest
```

Il verifie aller-retour DPAPI, absence de clair sur disque, refus d'ecrasement,
rejet d'un chiffre altere, formats et droits, puis nettoie son seul dossier
temporaire. Il n'utilise ni vrai contact, ni vrai credential, ni reseau. Dans un
bac a sable AppContainer, les droits prives peuvent empecher le test lui-meme ;
le controle doit alors etre realise localement avec l'autorisation appropriee,
sans elargir les droits du dossier pour contourner la protection.

## Recevoir puis verifier un seul SMS personnel

Apres compilation du serveur, lancer depuis la racine du depot :

```powershell
& "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -STA -File .\tools\windows\Start-WakaPersonalSmsCheck.ps1
```

La fenetre affiche localement le numero enregistre : verifier que c'est son propre
numero. Cocher la demande, puis cliquer sur `Envoyer 1 SMS de test`. Ce clic peut
consommer une unite du lot deja achete ; aucun achat ni recharge automatique.
Saisir les six chiffres recus dans cette meme fenetre puis `Verifier le code recu`.
Ne jamais partager le code, une capture de cette fenetre ou les acces dans le chat.

Le worker Java est un processus prive sans serveur HTTP : ses commandes voyagent
par des flux rediriges, sans secrets en arguments ou variables d'environnement.
Le nom par defaut est utilise, sans nom WAKA personnalise invente. Un verrou
durable `personal-check.lock` sous le meme dossier prive reserve l'essai avant
toute requete fournisseur. Aucun second envoi, y compris apres erreur, fermeture
ou redemarrage. Ne pas supprimer ce verrou pour recommencer a l'aveugle.

Le code est aleatoire, protege par HMAC en memoire, valable cinq minutes et limite
a cinq erreurs ; un succes ne se reutilise pas. Un retour Orange incertain peut
encore permettre de verifier un code recu si le worker reste ouvert. Une fermeture
perd cette verification. Le resultat generique est conserve dans
`personal-check-result.json`, sans numero, code, acces ni reponse Orange brute.

Cet essai personnel ne cree ni compte ni session WAKA. Il ne remplace pas le
parcours OTP persistant, la qualification commerciale ou les portes de lancement.
Le controle `-SelfTest` du meme script utilise uniquement des donnees techniques,
initialise puis ferme le worker sans commande d'envoi et sans lire l'acces reel.

Correction du 16 octobre : le remplacement du resultat generique utilise un
NullString .NET explicite ; le SelfTest couvre creation puis remplacement.
Une erreur de journal local reste affichee dans la fenetre sans rouvrir l'envoi.

Diagnostic sans envoi, depuis la meme racine :

```powershell
& "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -File .\tools\windows\Start-WakaPersonalSmsCheck.ps1 -DiagnoseProvider
```

Il charge les acces prives en memoire, appelle uniquement OAuth puis les deux
lectures officielles contrats/statistiques CMR. Delai huit secondes par requete,
reponses bornees a 8 KiB, sans redirection/proxy, aucun point d'envoi ou achat.
Seuls codes HTTP, nombres agreges et etat generique sortent ; aucun token, numero,
identifiant ou corps fournisseur. Ce diagnostic n'altere pas le verrou d'essai.
Un solde diminue ou un usage comptabilise ne constitue pas une preuve de reception.

Etat constate apres le premier essai humain : absence de reception signalee,
99 unites et usage 1 retournes par Orange. L'ancien resultat READY est perime
a cause du defaut d'ecriture corrige ; il ne prouve pas l'absence d'une tentative.
Le verrou reste conserve et aucun second envoi n'est autorise automatiquement.

## Second essai demande le 17 octobre

Le fondateur a ensuite explicitement demande de reessayer l'envoi du SMS.
Le mode `-AuthorizedRetry` execute cette seule demande au demarrage de la fenetre,
sans nouveau clic de confirmation. Il ne doit pas etre utilise comme SelfTest.
Le worker accepte seulement `--authorized-retry-02`, exige le verrou initial
comme fichier regulier non lien et reserve `personal-check-retry-02.lock` avant
le fournisseur. Le premier verrou/resultat est conserve. Aucun mode 03 ou
chemin configurable ; un nouveau lancement ne renvoie pas le SMS deja reserve.
Le second resultat est `personal-check-retry-02-result.json` dans le meme dossier
prive. UNKNOWN est ecrit avant soumission : une interruption ne laisse pas READY
comme faux indice d'absence de tentative ; si l'ecriture echoue, aucun envoi.

Contenu exact : `Code WAKA : [six chiffres aleatoires]. Valable 5 minutes. Ne le partagez pas.`
Les chiffres reels ne sont pas dans les journaux. Le nom d'expediteur reste celui
par defaut d'Orange, et non un nom personnalise WAKA suppose approuve. La fenetre
sert a verifier le nouveau code sans le partager dans le chat. ACCEPTED signifie
acceptation fournisseur seulement ; seul un code recu et verifie prouve ce test.

## Essai unique vers une autre personne

Le fondateur a ensuite demande un essai vers une autre personne et fourni son
numero. Le mode `-NewRecipientTest` exige les deux premiers verrous et reserve
`personal-check-recipient-03.lock`. Aucun mode 04, suppression ou reset automatique.
Le resultat distinct est `personal-check-recipient-03-result.json`.

La fenetre de saisie locale est le chemin interactif normal : accord du titulaire,
format Cameroun et bouton demandant un seul SMS. Pour le numero volontairement
fourni dans le chat, `-RecipientFromPrivateStdin` accompagne ce mode : il recoit
le numero uniquement par flux prive ou lecture console sans echo, jamais en
argument, environnement ou fichier. Il remplace le destinataire uniquement en
memoire pour cette demande. Le fichier d'acces et son ancien numero sont conserves.
L'autorisation d'utiliser ce mode ne doit pas etre deduite du seul drapeau technique.

Le premier lancement par le runner Node a echoue avant toute reservation : aucun
SMS de ce lancement. La reprise par console privee hors AppContainer a ete
controlee d'abord avec un numero fictif, sans echo ni reseau, puis utilisee pour
le seul envoi demande. Le numero reel n'apparait pas dans les journaux.
`-PrivateInputProbe` verifie uniquement le format d'une entree technique, sans
acces DPAPI ni requete ; ne pas l'utiliser pour recopier un vrai numero dans une
commande ou un fichier. Les erreurs exposent seulement type et ligne, sans cause
brute, valeur de champ ou reponse fournisseur.
