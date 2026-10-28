# WAKA — accès, confidentialité et conservation

03.09 v1 — 11 octobre 2026. **Réalisé — à relire dans le lot autorisé.** Exigences pour les fonctions futures ; aucune protection applicative ni conformité légale déclarée installée.

## A01 — Principe et fonctions

Le serveur refuse par défaut et vérifie chaque opération, y compris un fichier ou une liste filtrée. Le droit dépend de la personne, de la fonction, de l'objet, de sa relation à la réservation et de son état. Un identifiant deviné, un lien conservé ou un bouton masqué ne donnent aucun droit. Ce choix suit les recommandations [OWASP sur l'autorisation](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html), consultées le 11 octobre 2026.

Fonctions : titulaire client ; candidat/chauffeur de son propre dossier et trajet ; vérification ; assistance ; finance ; gestion des habilitations ; exploitation technique. Le fondateur peut cumuler les fonctions mais utilise un compte nominatif et conserve la fonction exercée à chaque décision. Exploitation technique ne donne pas de pouvoir métier ordinaire ; accès exceptionnel à des données sensibles avec justification, portée, échéance et audit.

Administration : authentification renforcée, session révocable, réauthentification pour opération financière sensible, contrôle serveur du périmètre. Gestion des habilitations ne permet pas de s'accorder librement tous les droits ; attribution/retrait datés et autorisés, récupération séparée. La procédure du fondateur seul doit être documentée avant activation ; aucun deuxième agent fictif n'est imposé comme déjà présent.

## A02 — Matrice des droits

« Propre » signifie propriétaire vérifié ; « engagé » désigne les réservations réellement liées au chauffeur et la période utile. Tout droit absent est refusé. Une lecture n'autorise pas une mutation. Les API 03.08 appliquent cette matrice par objet, y compris dans les listes et téléchargements.

| Ressource/action | Titulaire client | Candidat/chauffeur | Vérification | Assistance | Finance | Habilitations/exploitation |
|---|---|---|---|---|---|---|
| Profil/contact | Lire/corriger propre profil autorisé | Même droit propre | Identité utile au dossier | Contact nécessaire au dossier d'assistance | Destination financière nécessaire seulement | Pas de lecture générale ; exceptionnel motivé |
| Rôles/habilitations | Consulter propres rôles ; pas d'attribution | Demander catégorie ; pas d'approbation | Décider admission, pas administrateur | Pas d'attribution de rôle | Pas d'attribution de rôle | Gestion habilitations autorisée, pas auto-escalade |
| Dossier/pièces/photos originales | Pas d'accès aux pièces du chauffeur | Déposer/lire propres pièces | Lire/examiner version dans périmètre | Statut et extrait nécessaire ; original seulement habilitation spécifique motivée | Statut utile, pas stock global de pièces | Technique : accès d'incident exceptionnel journalisé |
| Fiche reconnaissance chauffeur/véhicule | Vue utile après engagement : identité reconnue/plaque/photos sélectionnées | Propre fiche | Vérifier concordance | Vue nécessaire au service | Pas de justificatifs via cette fiche | Aucune publication du dossier original |
| Devis/recherche/réservation | Créer/lire/annuler propres objets selon état | Proposition destinée et réservations engagées seulement | Pas de lecture générale | Lire/traiter objet d'assistance affecté | Lire détail financier nécessaire | Diagnostic masqué, pas modification de prix |
| Trajet/disponibilité | Informations nécessaires à sa réservation | Créer/modifier son trajet compatible ; pas autre chauffeur | Statut admission utile | Traiter engagements avec motif | Référence utile au rapprochement | Pas de commande de course ordinaire |
| Position précise | Chauffeur lié pendant service utile | Points de rendez-vous de ses réservations ; pas carte nominative globale | Pas de suivi géographique courant | Incident/service autorisé uniquement | Pas de coordonnées GPS | Pas de collecte générale de positions |
| Code embarquement | Présenter pour sa réservation | Vérifier pour réservation engagée ; ne pas lire secret stocké | Aucun | Alternative manuelle motivée | Aucun | Code masqué dans journaux |
| Paiement/remboursement | Consulter état/montant propre, demander selon règles | Voir état utile et propres gains, pas instrument du payeur | Aucun pouvoir financier | Décider service/motif, pas confirmer virement externe | Rapprocher/instruire selon contrat et droit, réauthentification | Pas de statut confirmé par gestionnaire de droits |
| Gains/versement chauffeur | Pas de solde d'un tiers | Propre historique et dettes/versements | Aucun | Portée contestée nécessaire | Traitement et rapprochement autorisés | Vue technique masquée |
| Parrainage/retrait bonus | Propre rattachement et gains ; retrait ≥1 000 F disponibles | Même droit personnel | Pas de réseaux nominaux inutiles | Correction exceptionnelle motivée | Traitement propre aux bonus, frais WAKA | Pas de liste commerciale publique de filleuls |
| Achat/période abonnement | Lecture propre si rôle particulier | Achat volontaire et dates propres | Statut d'accès utile à admission | Décision motivée panne/exception | Confirmation/refund selon contrat | Pas de période gratuite ajoutée par support technique |
| Incident/preuves | Créer/suivre propre incident, extraits autorisés | Même droit sur objet concerné | Extraits admission nécessaires | Examiner dossier affecté, décision/motif | Partie financière nécessaire | Pas de preuves exposées dans tableau global |
| Journal des décisions | Résultat/motif qui le concerne | Résultat/motif qui le concerne | Actions admission de son périmètre | Actions assistance de son périmètre | Actions finance de son périmètre | Audit habilité, distinct de pouvoir d'effacement |
| Droits des données/marketing | Demander accès/suppression ; choix marketing propre | Même droit personnel | Pas de publication de demandes | Fonction habilitée privée, pas assistance course générale | Justifier exceptions financières nécessaires | Exécuter tâches autorisées et conserver preuve minimale |
| Exports/sauvegardes/secrets | Aucun accès administratif | Aucun accès administratif | Export ciblé uniquement si autorisé | Export ciblé autorisé, pas backup global | Export financier protégé nécessaire | Secrets/coffre et restauration selon habilitation spécifique |

Un parrain n'a pas accès aux trajets, pièces, positions ou numéros des filleuls pour vérifier ses bonus. Il voit les éléments minimaux nécessaires à son propre gain. Le partage proche est une capacité dédiée, expirante et révocable ; le jeton de partage ne donne aucun accès au compte.

## A03 — Protéger chaque famille

| Famille | Exigence opérationnelle à construire | Preuve nécessaire avant données réelles |
|---|---|---|
| Identité/contact | Champs minimaux, validation et masquage selon destinataire ; pas CNI client par défaut | Test croisé propriétaire/autre personne et besoin de chaque champ |
| Pièces/fichiers | Stockage hors racine publique, identifiant serveur, accès contrôlé ; analyse avant mise à disposition | Formats/taille/signature/contenu contrôlés, fichier hostile rejeté ; aucun chemin utilisateur exécuté |
| Photos visibles au client | Dérivés sélectionnés minimaux, retirer métadonnées GPS inutiles ; originaux privés | Test originaux inaccessibles au client ; concordance véhicule et vue sélectionnée |
| Positions | Dernière position utile pour disponibilité ; pas historique continu de tous déplacements par défaut | Ancienne position refusée pour proposition ; fin/révocation d'accès testée |
| Paiement/finance | Références sensibles protégées, transactions et écritures compensatrices, pas PIN Mobile Money | Rapprochement, doublons et inconnus contrôlés ; droits finance testés |
| Secrets/session/codes | Coffre hors Git, stockage adapté appareil/serveur, rotation/révocation ; pas clés dans Flutter web/mobile | Scanner secrets, usages/récupération, refus code réutilisé/session révoquée |
| Logs/audit | Journal technique minimal séparé de l'audit nominatif ; accès restreint et intégrité | Pas pièces, secrets, jetons, codes ou GPS inutile ; motif et acteur d'une décision retrouvables |
| Transport/administration web | HTTPS hors local, origines contrôlées, protections de session et requêtes administratives | Accès sans session/refus inter-objets et requêtes malveillantes contrôlés |
| Sauvegardes/exports | Chiffrement vérifié, destination privée indépendante, clés récupérables séparément, droits et restauration | Restauration isolée et suppression propagée, inventaire des copies, délais approuvés |

La validation d'upload combine contrôles et stockage privé, plutôt que la seule extension du nom ; cette exigence technique s'appuie sur [OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html). Les paramètres précis seront fixés au point 06.03, avant dépôt réel. Les logs ne reçoivent ni mots de passe ni données sensibles inutiles ; leur rôle, accès et protection seront mis en œuvre à 04.07, suivant [OWASP Logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html). Sources consultées le 11 octobre 2026 ; elles ne remplacent pas les règles propres à WAKA.

## A04 — Conservation : décision préalable, pas durée inventée

Chaque famille a un registre : finalité, origine, destinataires, stockage, déclencheur de délai, durée active, archivage, exception de litige, suppression et copies de sauvegarde, décideur et preuve datée. Le modèle livré dans `WAKA_confidentiel/Registres` reste vide et ordinaire. Le remplir ne chiffre pas les fichiers.

| Famille | Déclencheur / besoin proposé | Décision avant production |
|---|---|---|
| Compte/contact | Durée du compte utile ; fermeture déclenche examen et minimisation | Justification des champs, procédure de droits et exceptions validées |
| Dossier refusé/remplacé et anciennes photos | Fin d'examen/correction ; conserver seulement preuve utile | Durée active/archives et purge des originaux qualifiées |
| Pièces admises | Validité/renouvellement et besoin de preuve d'admission | Durée après expiration et accès des agents qualifiés |
| Dernière position disponibilité | Remplacement par position suivante ; supprimer usage opérationnel après disponibilité/service utile | Pas de trace continue par défaut ; durée résiduelle/purge exacte approuvée et testée |
| Preuves service/incident | Fin service ou résolution/contestation ; faits nécessaires plutôt qu'enregistrement GPS intégral | Durée, suspension de purge pour litige et contrôle de pertinence |
| Paiements/journaux/dettes | Transaction, rapprochement et exigences financières ; aucune suppression de mouvement exécuté | Archivage et minimisation/pseudonymisation après fermeture ; exigences qualifiées |
| Parrainage/abonnement | Échéances et preuves de calcul, clôture financière | Conserver règles/périodes nécessaires sans répertoire public de personnes |
| Sessions/OTP/partage | Expiration/révocation/consommation ; supprimer accès et secret inutile | TTL sécurité et conservation de preuve minimale distincts, à fixer aux fonctions |
| Idempotence/événements prestataires | Tant que reprise, rapprochement ou résultat inconnu possible | Conservation adaptée au contrat ; pas suppression qui permettrait double débit ou webhook ancien |
| Logs/audit/exports | Date événement ou fin tâche ; finalités distinctes | Volumes/plafonds/rétention et revue d'accès approuvés ; exports temporaires purgés |
| Sauvegardes | Date snapshot/cycle et reprise | Rétention finie approuvée, inventaire, restauration isolée et purge effective des anciennes copies |

Aucune durée légale de dix ans, trente jours ou autre n'est inventée ici. Les durées exactes de conservation et conditions d'hébergement/transfert sont **à qualifier avant collecte réelle/production**, conformément au dossier §3.4. Les durées commerciales adoptées (essai, abonnement, fenêtres) ne sont pas des durées de conservation. L'objectif 02 et le volet confidentialité devront apporter les décisions et leurs preuves ; absence de qualification bloque seulement les fonctions de collecte/exploitation qui en dépendent.

## A05 — Demande de suppression et restauration

1. Identifier le demandeur par procédure sûre et créer une demande privée idempotente ; aucun envoi de secret dans le plan.
2. Désactiver les accès concernés, révoquer sessions et partages, expliquer engagements/finances en cours ; traiter les réservations en sécurité.
3. Rechercher les familles réellement détenues, distinguer suppression possible et conservation nécessaire motivée. Informer du résultat et du recours selon cadre qualifié ; pas de promesse de suppression totale immédiate.
4. Effacer/minimiser les données supprimables dans base, fichiers, dérivés, caches, index, notifications et exports. Garder une preuve minimale de traitement, sous durée justifiée.
5. Les sauvegardes ne restent pas illimitées. Conserver un registre de suppressions/revocations pour réappliquer les décisions après restauration, sans ressusciter un compte, un lien ou une pièce. Restore isolé, purge/révocation avant remise en service, contrôle de cohérence.
6. Conserver écritures nécessaires sous accès limité et durée qualifiée ; anonymiser/pseudonymiser lorsque recevable, sans casser rapprochement ou droits des bénéficiaires. Un journal immutable n'autorise pas à garder à jamais toute donnée personnelle.

## A06 — Coffre, copie et ouverture

La préparation locale du coffre 01.10.a–c est vérifiée. La récupération hors PC et l'usage confidentiel opérationnel 01.10.d sont incomplets ; l'enveloppe Windows seule n'est pas une sauvegarde indépendante. Les originaux chauffeur iront au stockage applicatif privé, les secrets au coffre ; ne pas constituer un deuxième stock sur le poste ou dans Git. Aucun dossier privé réel n'est créé par ce document.

Avant ouverture : habilitations nominatives approuvées, droits inter-objets testés, fichiers et positions protégés, durée/hébergement qualifiés, contrats de sous-traitance applicables, sauvegarde/restauration testées, suppression/revocation réappliquées, réponse à incident et récupération documentées. Ce sont des preuves à obtenir, pas des contrôles déclarés réussis.

## A07 — Cas de refus à tester dans le vrai code

Client A lit réservation de B ; chauffeur hors engagement lit position ; ancien lien de partage après révocation ; finance télécharge toute pièce ; candidat se déclare taxi approuvé ; gestionnaire se donne un rôle financier ; fichier hostile ; position ancienne ; journal contenant OTP ; remboursement sans réauthentification ; restauration qui ressuscite une pièce supprimée. Chaque cas doit être refusé ou traité avec la portée et la trace prescrites.

**Sources WAKA :** [R02/R05/R07/R10](WAKA_regles_adoptees.md), dossier §3.4/§7.3, [E04/E20/E25–E29](WAKA_fiches_ecrans.md), [objets D01–D42](WAKA_modele_donnees.md), [transitions S01/S07](WAKA_etats_et_transitions.md), guide confidentiel. **Coût : 0 F.** Lecture : `code outputs/WAKA_acces_et_conservation.md` dans le dossier documentaire ; Ctrl+Maj+V. Aucun compte prestataire, chiffrement applicatif ou test d'autorisation exécuté ici.
