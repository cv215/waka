# WAKA — documentation du depot correct

Le depot de travail est cv215/waka. Correction locale du 15 octobre 2026 : historique recu preserve ; aucune publication effectuee. Le README original est conserve et complete.

Lire les [regles](specifications/WAKA_regles_adoptees.md), les trois parcours, [ecrans](specifications/WAKA_fiches_ecrans.md), [donnees](specifications/WAKA_modele_donnees.md), [etats](specifications/WAKA_etats_et_transitions.md), [API](specifications/WAKA_contrats_api.md), [droits](specifications/WAKA_acces_et_conservation.md), puis [architecture](architecture/WAKA_architecture_minimale.md).

Les sources documentaires completes, bilans, budget et Word restent hors de ce depot public. Les copies relatives conservent les regles et limites. Pendant leur revue, corriger les originaux puis reporter ici pour eviter deux references divergentes. Aucun original confidentiel ou export de production ici.

Le fondateur autorise les travaux gratuits sans pause obligatoire entre etapes ; les bilans et explications restent fournis. Toute depense nouvelle exige accord sur le cout concret. Une verification technique ne prouve ni validation humaine ni autorisation externe.

État du 18 octobre : serveur Spring Boot/MySQL, migrations V1–V5, moteur de tarifs et application Flutter Android préparés localement. Le lot 05.03.b–d ajoute reprise des sessions, contrats HTTP de connexion/déconnexion et stockage protégé sur Android. Le point 05.04.a ajoute la lecture du compte courant et la revalidation serveur des sessions restaurées ; profil modifiable et habilitations restent à construire. Les essais natifs Android de ce lot ne sont pas exécutés. Voir [serveur](../backend/README.md) et [mobile](../apps/mobile/README.md). Le fournisseur SMS reste désactivé et en attente à la demande du fondateur ; les commandes de connexion valides répondent 503 sans configuration. Profils, rôles et autres fonctions commerciales restent ouverts. [Décisions ouvertes](decisions/README.md). Les états « à relire » sont des statuts de livraison, sans pause obligatoire entre travaux gratuits autorisés.

L'[equipe nommee](equipe/README.md) est preparee : Atlas, Kora, Naya, Sika, Vigie et Scribe. Definitions TOML locales au projet ; deux specialistes actifs par defaut. Aucun achat ou telechargement de modele supplementaire.

Dans le terminal du depot : git status --short ; git diff --check ; ouvrir chaque nouveau fichier dans VS Code. Les fichiers non suivis ne figurent pas dans git diff. Aucun commit ou push effectue.
