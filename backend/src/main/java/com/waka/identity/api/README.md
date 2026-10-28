# Connexion : frontière HTTP

05.03.c, 17 octobre 2026. Les contrôleurs relient les requêtes aux composants OTP et sessions. `LoginService` est construit explicitement ; aucune configuration Spring ne l'active et aucun fournisseur de test n'existe dans le code applicatif. Une commande bien formée reçoit donc **503 SERVICE_UNAVAILABLE** dans l'installation ordinaire. Les essais SQL construisent ce service seulement dans `src/test`.

## Chemin de la connexion

1. `LoginRequestReader` limite le corps réel à 1 024 octets, impose JSON UTF-8 avec champs exacts et chaînes, refuse doublons, racines supplémentaires, paramètres URL et en-têtes de commande ambigus. Les UUID sont canoniques minuscules. Le contexte utilise 16 à 128 caractères ASCII autorisés.
2. `LoginController` transforme une demande valide en appel au composant métier. Le contrôleur ne connaît aucune clé SMS. Les erreurs sont les problèmes génériques de WAKA, sans copie des valeurs reçues. Une acceptation du fournisseur ne signifie pas que le téléphone a reçu le message.
3. `LoginService` appelle `verifyBound` : le téléphone répété doit être celui du défi avant toute consommation du code. L'empreinte de la commande lie code, défi, contexte et destination normalisée. Il transmet ensuite la preuve interne au composant de sessions. Une preuve ne vient jamais du JSON reçu.
4. La réponse de session est écrite directement en JSON. Les copies contrôlées du code et du jeton sont effacées même si l'écriture échoue ; cela ne garantit pas l'effacement de toute copie intermédiaire dans Java/HTTP.
5. Pour déconnecter, le service vérifie ensemble jeton, contexte, session ciblée et commande persistante. Il ne fait pas d'authentification préalable qui empêcherait de reprendre une déconnexion déjà enregistrée.

## Contrat partiel A01–A03

Toutes les commandes demandent `Idempotency-Key` (UUID). Pas de secret dans une URL, un cookie ou un journal. Réponses et erreurs : `Cache-Control: no-store`. Le transport actuellement disponible est limité au développement local ; HTTPS et environnement public restent à qualifier avant accès réel.

| Commande | Corps et en-têtes | Réponse |
|---|---|---|
| POST `/api/v1/auth/phone-challenges` | JSON : `phoneNumber`, `purpose` exactement `login`, `clientInstanceRef` | 202 : `challengeId`, `submission` (SENDING/ACCEPTED/REJECTED/UNKNOWN), `expiresAt`, `destinationHint` masquant tout sauf deux chiffres |
| POST `/api/v1/auth/phone-challenges/{challengeId}/verifications` | JSON : `phoneNumber`, `code` six chiffres ASCII, `clientInstanceRef` | 200 : `sessionId`, `accountId`, `tokenType` Bearer, `token`, `absoluteExpiresAt`, `idleExpiresAt` |
| POST `/api/v1/auth/sessions/{sessionId}/revocations` | Corps vide ; `Authorization` Bearer, `X-Waka-Client-Instance`, `Idempotency-Key` | 204 après effet ou reprise exacte autorisée |

Codes : 400 format invalide ; 401 preuve/session refusée ; 409 clé réutilisée avec une commande différente ; 413 corps trop grand ; 415 type non pris en charge ; 429 limites d'essai/envoi ou budget ; 503 service absent/indisponible. Ne pas convertir une erreur réseau ou 503 en confirmation de réception ou de connexion.

Le téléphone dans A02 et le contexte dans A01 précisent les champs du contrat initial. Une saisie de téléphone incompatible avec le défi est refusée avant consommation ; elle peut être corrigée. Après enregistrement d'une vérification recevable, changer ses entrées impose une nouvelle clé. Les différences de présentation qui normalisent vers le même numéro ne changent pas la commande. Changer un code après une erreur exige une nouvelle clé ; répéter exactement la même tentative utilise la même clé.

La reprise d'une connexion dure au plus la plus courte des échéances de reprise, preuve, inactivité initiale et session absolue. Elle conserve le même jeton et ne prolonge pas la session. Au-delà, une nouvelle vérification est nécessaire. Une session révoquée/expirée ou un compte désactivé ne retrouve aucun accès.

**Limites assumées :** A02 renvoie pour l'instant seulement la session, pas le profil prévu par le contrat complet. `/me` reste absent : profil et rôles dépendent de 05.04. Aucune inscription de chauffeur, permission d'administration, émission SMS réelle, qualification prestataire ou ouverture publique ajoutée. Un assemblage futur devra fournir secrets protégés, politiques explicites, entretien du chiffré temporaire et canal qualifié ; l'existence d'un contrôleur ne suffit pas.

**Évolution 05.04.a :** `/me` est désormais décrit dans [ME.md](ME.md). La phrase historique ci-dessus sur son absence ne décrit plus l'état courant. Le profil modifiable, les attributions D02 et l'admission chauffeur restent à construire.

## Vérification

`LoginControllerTest` contrôle les entrées/refus et l'absence du service sans base ni réseau. `LoginHttpMySqlVerificationTest` traverse contrôleur → OTP → session → MySQL avec transport de capture réservé aux tests : reprise, concurrence, destination, code erroné, déconnexion ciblée et échéances. MVC y est exécuté en mémoire, sans socket HTTP ; le bilan donne séparément les contrôles sur serveur local et leurs résultats réels.

Références consultées : [OWASP sessions](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) pour expiration/invalidation serveur et [OWASP API REST](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html) pour contrôles d'accès et erreurs limitées. Ces références ne constituent pas une certification de WAKA.
