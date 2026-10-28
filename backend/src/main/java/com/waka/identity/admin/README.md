# Accès administrateur (05.05)

Une session client (connexion par code SMS) **ne suffit jamais** pour l'administration. Il faut en plus :

1. une **habilitation nominative active** sur une fonction (`ADMISSION`, `ASSISTANCE`, `FINANCE`, `AUDIT` ou `HABILITATIONS`), datée, avec auteur et motif ;
2. un **code à usage unique TOTP** (RFC 6238 : 6 chiffres, 30 secondes, HMAC-SHA1), produit par une application d'authentification gratuite sur le téléphone de la personne.

La session administrative est liée à la session client qui l'a ouverte. Elle se ferme si cette session client se ferme, si l'habilitation est retirée, après une durée d'inactivité ou absolue, ou à la demande. Seule une empreinte de son jeton est stockée. Un code n'est accepté qu'une fois, et des échecs répétés bloquent temporairement. Toutes les durées et tous les seuils sont injectés (`AdminPolicy`) : aucune valeur de production n'est choisie ici.

## Routes (non assemblées par défaut : 503 sans configuration qualifiée)

| Route | Rôle |
|---|---|
| `POST /api/v1/admin/second-factor/enrollments` | Personne habilitée : crée ou recrée un enrôlement **non confirmé** et renvoie une seule fois le secret et l'URI `otpauth://` (étiquette « WAKA:administration », sans numéro de téléphone). |
| `POST /api/v1/admin/second-factor/confirmations` `{code}` | Confirme que l'application d'authentification fonctionne. |
| `POST /api/v1/admin/sessions` `{code}` | Ouvre la session administrative. **Non rejouable** (contrat K15) : une réponse perdue se résout avec un nouveau code. |
| `GET /api/v1/admin/me` | Avec `Authorization`, `X-Waka-Client-Instance` et `X-Waka-Admin-Session` : renvoie les fonctions actuelles, relues à chaque appel. |
| `POST /api/v1/admin/sessions/current/revocations` | Ferme la session administrative ; la session client reste ouverte. |

Aucune route HTTP ne crée ni ne retire d'habilitation dans ce point.

## Procédure du fondateur seul (à faire avant activation réelle)

Ces opérations de **maintenance locale** passent par `PersistentAdminService`, jamais par HTTP :

- `bootstrapFounder(accountId, fonctions)` : première attribution, possible **une seule fois**, tant qu'aucune habilitation n'a jamais existé, pour un compte actif. Elle est enregistrée avec le motif `FOUNDER_BOOTSTRAP`.
- `resetSecondFactor(accountId)` : en cas de téléphone perdu, retire le second facteur et ferme les sessions administratives ouvertes. Les habilitations sont conservées ; la personne s'enrôle ensuite à nouveau.

L'outil en ligne de commande qui appellera ces méthodes avec les clés injectées en mémoire **n'est pas encore assemblé**. Il le sera avec la configuration qualifiée du serveur. Aucun secret TOTP, code ou identifiant réel ne doit être écrit dans Git, dans un rapport ou dans le dossier documentaire.
