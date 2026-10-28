# Lecture du compte — 05.04.a

`GET /api/v1/me` relit une session courante. La requête porte exactement un
`Authorization: Bearer` et un `X-Waka-Client-Instance` correspondant au contexte
de connexion. Elle ne porte ni corps ni paramètre d'URL. Les en-têtes doublés
sont refusés. La réponse 200 contient seulement `accountId`, `sessionId`,
`absoluteExpiresAt`, `idleExpiresAt`, `accountState: "ACTIVE"`,
`roles: ["CLIENT"]` et `serverTime`, avec
`Cache-Control: no-store`. Le serveur ne retourne ni téléphone, ni jeton, ni
preuve OTP. CLIENT est le droit de base d'un compte technique `ACTIVE`, pas une
identité civile vérifiée, une attribution D02, une candidature approuvée ou un
droit administratif.

La lecture valide le jeton et son contexte auprès de `PersistentSessionService`.
Elle vérifie compte, révocation et échéances dans la transaction protégée par la
garde de sessions, puis renouvelle seulement l'échéance d'inactivité dans la
limite de la vie absolue. Les informations de réponse sont produites à partir
de ce même résultat. La prélecture indexée d'un hash de jeton inconnu évite le
verrou global ; elle n'accorde jamais l'accès, qui reste décidé sous la garde.
Une révocation concurrente et une lecture sont ordonnées par ce verrou ; toute
lecture démarrée après la révocation commise échoue. La prélecture n'est pas un
contrôle de débit : limites de charge et comportement sous trafic hostile restent
à qualifier avant exposition publique.

Absence de preuve, session révoquée/expirée ou compte désactivé : 401.
Service non assemblé ou panne de validation : 503. Entrée structurellement
invalide : 400 ; encodage de contenu autre qu'identity : 415. Les erreurs sont génériques et sans données privées. Aucun
`MeService` n'est enregistré automatiquement : le serveur local ordinaire
refuse les requêtes bien formées avec 503 tant que l'assemblage qualifié manque.
Le fournisseur SMS reste en attente. Aucune migration ou donnée personnelle
nouvelle n'est créée ici ; A05 et les attributions explicites restent au
sous-point suivant.

`MeControllerTest` vérifie le contrat HTTP en mémoire ; les tests MySQL opt-in
dans `LoginHttpMySqlVerificationTest` contrôlent session restaurée, isolation de
deux comptes, révocation, expiration, désactivation et concurrence avec un
transport SMS de capture réservé aux tests. Leur exécution et leurs limites
sont consignées dans le bilan du point.
