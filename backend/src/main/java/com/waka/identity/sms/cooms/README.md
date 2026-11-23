# Cooms : envoi des codes de connexion

Décision du fondateur du 23 novembre 2026 : les codes de connexion partent par **Cooms** (cooms.app), pour MTN et Orange Cameroun. L'inscription demande seulement un e-mail, alors que SmsPro exige un NIU, que le fondateur n'a pas encore. SmsPro reste prêt dans le code (`../smspro`).

## Ce que fait `CoomsSmsGateway`

- **Envoi** : `POST <passerelle>/api/v1/sms/send`, avec l'en-tête `x-api-key` et le corps `{phone, message, priority: 1}` (contrat lu le 23 novembre 2026 dans la console Cooms). La passerelle est propre au compte, par exemple `https://gw-89.cooms.app`. Seule une adresse `https://<nom>.cooms.app` est acceptée.
- **Texte** : « Code WAKA : 123456. Valable 5 minutes. Ne le partagez pas. », en ASCII, sur un seul SMS. WAKA crée et vérifie le code lui-même.
- **Réponses** :
  - **200** avec un objet JSON sans `error` et sans `success: false` : accepté. La console ne documente pas le contenu exact de cette réponse.
  - **400, 401, 402, 403, 422, 429, 503** : refus documentés, par exemple clé refusée, crédit épuisé, compte suspendu, contenu interdit, débit dépassé ou aucun téléphone passerelle connecté. L'utilisateur peut redemander un code.
  - **Tout le reste** une fois la requête partie (délai dépassé, autre erreur 5xx, réponse illisible) : inconnu, **jamais renvoyé**. Cooms conseille de basculer vers un autre fournisseur après un délai dépassé ; WAKA ne le fait pas, car le premier SMS peut encore partir et le second serait facturé.
- **Délais** : connexion 3 s, requête 8 s au plus (Cooms recommande 6 s), réponse limitée à 8 Kio, sans proxy ni redirection. La clé, le numéro et le code n'apparaissent dans aucun journal.

## Clé

Enregistrement : `tools\save-cooms-key.ps1` (clé et adresse de la passerelle, protégées par Windows, hors de Git). Une clé `coom_live_…` envoie de vrais SMS, pris sur le crédit du compte (100 SMS offerts à l'inscription).

## Tests

`CoomsSmsGatewayTest` utilise un vrai serveur HTTP sur 127.0.0.1, sans compte, clé réelle ni SMS. Aucun envoi réel n'a encore été fait.
