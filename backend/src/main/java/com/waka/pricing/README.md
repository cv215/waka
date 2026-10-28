# Calcul des prix WAKA — 07.01 a 07.05

Ce module traduit R03/R04 de `docs/specifications/WAKA_regles_adoptees.md`.
La migration V2 prepare les 19 tranches `launch-v1` en XAF. Elle ne modifie pas V1,
ne fixe aucune date de vente et ne cree aucun compte, course, paiement ou bonus acquis.
Chaque version doit rester immuable ; une evolution demande une nouvelle version
et une migration additive. Il n'existe aucune methode d'ecriture dans ce module.
Le compte applicatif et les controles d'administration futurs devront aussi proteger
les anciennes versions ; le schema seul n'interdit pas une modification SQL administrative.

## Lire une grille et calculer

`JdbcFareScheduleRepository.findByVersion` demande une version explicite. Le depot
JDBC n'est pas une classe `final` : Spring doit pouvoir creer sa sous-classe proxy
pour traduire les exceptions de persistance de `@Repository`. Un test de contexte
reproduit cette configuration sans connexion a une base et sans simulation.
Une seule
requete SQL avec parametre lie lit les bornes et les prix. Aucune version inconnue
n'est remplacee silencieusement par une autre. `FareSchedule` copie la liste et refuse
les trous, chevauchements, prix non positifs ou derniere borne incorrecte.
`FareBand` represente une tranche : borne basse incluse, borne haute exclue, sauf
la derniere qui inclut 17 900 m. Le schema applique les contraintes locales ; le
constructeur controle la coherence de toute la grille a chaque lecture.

`PricingEngine.shared` prend de une a cinq places payees ; verifier la capacite
legale et disponible reste une obligation du futur parcours de reservation.
`vip` ne prend aucun nombre d'occupants : son prix et sa commission utilisent 25/2,
ses bonus utilisent 10. Il n'existe aucun parametre taxi/particulier puisque ces
categories ont les memes prix. Les deux booleens d'eligibilite sont des entrees
de calcul ; ils ne prouvent ni parrain, ni periode, ni droit. Le futur serveur devra
etablir ces faits pour la reservation payee et terminee avant de creer des gains.

La distance est un `BigDecimal` en metres, donc un decimal exact. Elle est arrondie
au metre avec moitie vers le haut AVANT le choix de tranche. 999,50 devient 1 000 ;
17 900,49 reste admis ; 17 900,50 est refuse. Les valeurs negatives, absentes et
hors zone sont refusees avant toute conversion susceptible de deborder.
Les prix utilisent des fractions d'entiers : seule la division finale arrondit au
franc. A 205 F, cinq places ont une commission de 168 F ; un VIP coute 2 563 F avec
421 F de commission. A 600 F, le VIP coute 7 500 F, commission 750 F, chauffeur
6 750 F et bonus 100/50 F si eligibles ; il reste 600 F avant les autres couts.

`FareCalculation` conserve version, devise, distance arrondie, tranche/prix unitaire,
mode, coefficients, eligibilites de calcul et les six montants. La conservation
exacte des francs et l'absence de montant negatif sont controlees. C'est un resultat
immuable pour preparer un futur devis ; aucun engagement, delai d'acceptation,
historique persistant ou paiement n'est cree. Il n'y a aucune API publique qui
prendrait la distance envoyee par le telephone comme autorite. La qualification
du fournisseur routier reste necessaire. Aucun appel payant n'est effectue.

## Controles reproductibles

Dans le dossier `backend`, avec le JDK et Maven deja retenus :

```text
mvn -o -Dtest=PricingEngineTest,FareScheduleTest test
mvn -o test
```

Les CSV de `src/test/resources/pricing` viennent des oracles independants prepares
avant ce code : 456 repartitions (114 prix/commissions avec quatre eligibilites)
et 176 distances. Les attentes sont lues telles quelles, jamais regenerees par le
moteur teste. `07_oracles_bands.csv` est la projection sans recalcul des bornes et
prix du JSON d'oracle original. `07_oracles_verification.json` conserve les empreintes
et le contexte de cette preparation ; il ne prouve pas l'execution de l'application.
Ces tests unitaires ne prouvent aucune execution MySQL.

Le test `PricingMySqlVerificationTest` est saute sauf si `WAKA_VERIFY_MYSQL=true`.
Le coordinateur doit d'abord appliquer les migrations sur une vraie base locale.
Il fournit `WAKA_DB_URL`, `WAKA_DB_USER` et `WAKA_DB_PASSWORD` en memoire depuis le
coffre, sans valeur dans un fichier ou une commande archivee. Puis, depuis `backend` :

```text
mvn -o -Dtest=PricingMySqlVerificationTest test
```

Ce test utilise JDBC directement, sans demarrage Spring, sans migrations automatiques,
sans simulation et sans DDL/DML. Il compare les 19 tranches aux oracles et execute
leurs 456 repartitions avec la grille reellement lue. La CI le saute explicitement
tant qu'elle n'a pas cette base qualifiee ; un saut n'est pas une reussite MySQL.

Les tests couvrent les calculs et leurs limites (partie preparatoire de 07.07).
07.06 (figer le prix dans le vrai parcours) et 07.07 integre (acceptation, detours,
historique et droits reels) restent a construire avec leurs dependances. Aucun
remboursement, abonnement, versement ou transaction financiere n'est gere ici.
