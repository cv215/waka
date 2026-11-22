# SmsPro : envoi des codes de connexion

Décision du fondateur du 22 novembre 2026 : les codes de connexion partent par **SmsPro** (Mediasystem SARL, Yaoundé). Un seul compte et une seule clé servent pour MTN et Orange Cameroun. Orange a annoncé l'arrêt de son API SMS Cameroun au 1er décembre 2026 ; l'adaptateur `identity/sms/orange` reste dans le code sans être branché.

## Ce que fait `SmsProSmsGateway`

- **Envoi** : `POST https://v2.smspro.cm/api/v1/messages` avec `{to, message, from}` et l'en-tête `Authorization: Bearer <clé>` (contrat lu le 22 novembre 2026 sur https://v2.smspro.cm/docs/api).
- **Code** : WAKA crée et vérifie le code lui-même (`PersistentOtpService`). SmsPro ne fait que transporter le texte « Code WAKA : 123456. Valable 5 minutes. Ne le partagez pas. », en ASCII, sur un seul SMS.
- **Réponses** :
  - **201** avec `success: true` et un `message_id` : accepté (`ACCEPTED`). Cela ne prouve pas la réception.
  - **400, 401, 402, 404, 422, 429** : refus documenté, rien n'est créé ni facturé (`REJECTED`). L'utilisateur peut redemander un code.
  - **Tout le reste** une fois la requête partie (5xx, délai dépassé, réponse illisible) : `UNKNOWN`, **jamais renvoyé automatiquement**, car SmsPro n'a pas de clé d'idempotence et un second envoi serait facturé.
- **Délais** : connexion 3 s, requête 8 s au total, réponse limitée à 8 Kio.
- **Sécurité** : seuls `https://v2.smspro.cm` ou `http://127.0.0.1:<port>` (tests) sont acceptés, sans redirection ni proxy. La clé, le numéro et le code n'apparaissent dans aucun journal.

## Limites contre les abus (déjà en place dans `OtpPolicy`)

- 3 demandes par numéro sur 15 minutes, 60 secondes entre deux codes, 5 erreurs de saisie au plus.
- Un plafond global par heure ; à zéro, aucun envoi.
- Conseillé en plus : régler un petit quota quotidien sur la clé dans le tableau de bord SmsPro.

## Clé d'essai et clé réelle

- **Clé `smspro_test_…`** : SmsPro simule l'envoi (mêmes contrôles, rien n'est envoyé ni facturé). Sur ce poste, le code est aussi écrit dans le fichier local pour pouvoir se connecter.
- **Clé réelle** : de vrais SMS facturés. Le nom d'expéditeur doit être déclaré chez SmsPro pour un usage OTP, en respectant les majuscules.

Enregistrement : `tools\save-smspro-key.ps1`. La clé reste protégée par Windows, hors de Git.

## Tests

`SmsProSmsGatewayTest` utilise un vrai serveur HTTP sur 127.0.0.1, sans compte, clé réelle ni SMS. Aucun envoi réel n'a été fait à ce jour.
