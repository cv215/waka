# Equipe WAKA

Definitions Codex locales au projet dans `.codex/agents`, creees le 15 octobre 2026. Les profils choisissent un modele et un effort par sous-tache, dans les options deja disponibles ; ils ne changent pas le modele de la conversation actuelle. Aucun modele, cle API, achat ou abonnement n'est active par cette configuration. Les agents effectuent des requetes distantes sous les quotas de la session, sans charger six modeles dans la memoire du PC. Aucun pourcentage d'economie ni niveau de perfection n'est garanti.

| Nom | Mission et profil par defaut |
|---|---|
| Atlas | Coordination, integration et explications ; modele et effort herites de la session. |
| Kora | Serveur Java/Spring et MySQL ; `gpt-6.1-sol`, effort `medium`. |
| Naya | Flutter mobile et administration web ; `gpt-6.1-sol`, effort `medium`. |
| Sika | Relecture financiere et arrondis ; `gpt-6.1-sol`, effort `high`. |
| Vigie | Qualite, securite, droits et reprises ; `gpt-6.1-sol`, effort `high`. |
| Scribe | Produit et documentation ; `gpt-6-luna`, effort `low`. |

Le choix se fait selon la sous-tache : Luna convient aux petits bilans, liens et lectures bornees ; Sol avec effort moyen aux changements de code courants et tests utiles ; Sol avec effort eleve aux finances, droits, idempotence et revues a risque. Si un probleme difficile a fort impact reste incertain ou echoue, le coordinateur peut choisir `gpt-6-astra` si cette option est deja disponible dans l'acces existant. Aucun achat ou abonnement ne sera force et aucune escalade recursive n'est permise.

Pour economiser le contexte et les requetes, reutiliser les resultats deja obtenus, transmettre des contextes courts et cibles, et utiliser `fork_turns=none` sauf besoin de l'historique. Deux specialistes actifs au maximum par defaut. Chaque fichier a un proprietaire unique ; eviter les doubles revues cosmetiques et ne garder que les tests qui verifient un comportement utile. Les agents ne sont pas six modeles charges dans la RAM.

Les profils restent des definitions reutilisables ; leur rechargement automatique dans la session actuelle n'est pas presume. Le coordinateur choisit explicitement profil, modele et effort lors des prochains forks ou spawns. Les agents restent limites aux outils, quotas et droits disponibles. Une pause ou un quota atteint ne declenche aucun achat automatique.

Exemple : « Kora realise le serveur minimal, Vigie relit les droits et reprises, Scribe documente les fichiers modifies ». Ollama/Qwen reste une option separee non installee ; aucune inference locale n'est necessaire pour ces roles.

Source du format : [documentation officielle Codex sur la configuration des sous-agents](https://learn.chatgpt.com/docs/agent-configuration/subagents), consultee le 15 octobre 2026.
