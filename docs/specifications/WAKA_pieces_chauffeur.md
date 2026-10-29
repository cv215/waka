# WAKA — pièces justificatives des chauffeurs (catalogue v1)

29 octobre 2026. Établi à la demande du fondateur : un dossier simple, adapté à chaque catégorie, sans pièce inutile ni demandée deux fois. **Ce catalogue ne déclare aucune conformité légale obtenue.** Chaque exigence porte un statut :

- **Établie** : obligation générale appuyée par une source, ou règle adoptée par WAKA (C02). Une telle exigence peut être satisfaite par une pièce acceptée par un agent habilité.
- **À confirmer** : une source existe, mais le texte officiel, la catégorie exacte, le champ d'application ou la pièce recevable restent incertains. Une exigence à confirmer **n'est jamais considérée comme satisfaite**. Tant qu'elle s'applique à un dossier, **l'approbation est impossible**. Elle reste affichée au candidat, avec la mention « en cours de confirmation par WAKA ».

Passer une exigence de « à confirmer » à « établie » demande une décision datée du fondateur, appuyée par le texte officiel ou une réponse écrite de l'administration. Cela crée une nouvelle version du catalogue. Rien n'est validé par précaution.

## Deux parcours dès le départ

| Parcours | Situation | Ce qui est évité |
|---|---|---|
| **Taxi** | Le véhicule est exploité comme taxi ; le chauffeur peut en être propriétaire ou non. | Les titres d'exploitation qui relèvent du **propriétaire ou exploitant** (licence, carte de transport public) sont demandés comme pièces **du véhicule**. Le chauffeur n'a jamais à les posséder personnellement. |
| **Voiture personnelle** | Le chauffeur utilise son propre véhicule. | Aucune preuve de statut de taximan. Les autorisations propres au transport rémunéré par un particulier sont identifiées séparément, sans supposer que l'abonnement WAKA les remplace. |

## Catalogue

Une **personne** a des pièces réutilisables d'un dossier à l'autre (identité, permis). Les pièces du **véhicule** sont liées à son immatriculation : elles sont réutilisées tant qu'il s'agit du même véhicule et qu'elles restent valides. Une même pièce peut servir à plusieurs vérifications sans être déposée de nouveau. Par exemple, la carte grise prouve l'immatriculation **et**, quand elle est au nom du chauffeur, son droit d'utiliser le véhicule.

| Code | Pièce attendue (nom précis) | Phrase affichée au chauffeur | Taxi | Voiture personnelle | Statut |
|---|---|---|---|---|---|
| `IDENTITY` | Carte nationale d'identité ou passeport en cours de validité | « Ta pièce d'identité permet de vérifier que le dossier est bien le tien. » | Oui | Oui | **Établie** — règle WAKA C02 (identité vérifiée) |
| `DRIVING_LICENCE` | Permis de conduire de la catégorie du véhicule, en cours de validité | « Ton permis montre que tu peux conduire ce type de véhicule. » | Oui | Oui | **Établie** — conduire exige un permis valide de la catégorie du véhicule [S4][S5] |
| `REGISTRATION_CERTIFICATE` | Carte grise (certificat d'immatriculation) du véhicule inscrit | « La carte grise identifie le véhicule et son propriétaire. » | Oui | Oui | **Établie** — pièce exigée pour tout véhicule transportant des passagers [S4][S6] |
| `INSURANCE` | Attestation d'assurance en cours de validité couvrant le transport de passagers proposé | « L'assurance doit couvrir l'activité que tu proposes avec WAKA, pas seulement un usage personnel. » | Oui | Oui | **Établie** pour l'obligation d'assurance (C02 ; [S4][S5]). **La garantie exacte à exiger pour le transport rémunéré reste à confirmer** auprès d'un assureur ou du régulateur (code CIMA) : l'agent vérifie l'usage mentionné et ne présume jamais qu'une assurance personnelle suffit. |
| `TECHNICAL_INSPECTION` | Certificat de visite technique en cours de validité | « La visite technique montre que le véhicule a été contrôlé récemment. » | Oui | Oui | **À confirmer** — la visite technique est citée pour les taxis [S4][S5], mais aucune source trouvée ne précise la périodicité ni l'application à un véhicule personnel. |
| `USE_AUTHORIZATION` | Autorisation écrite et signée du propriétaire de laisser le chauffeur utiliser le véhicule pour WAKA | « Le véhicule n'est pas à ton nom : le propriétaire doit autoriser son utilisation. » | **Seulement** si droit déclaré = `authorized_use` | **Seulement** si droit déclaré = `authorized_use` | **Établie** — règle WAKA C02/C03. Si la carte grise est au nom du chauffeur (`holder`), **rien de plus n'est demandé**. Si l'agent constate que la carte grise n'est pas au nom du chauffeur, il demande la correction du **droit déclaré**, pas une pièce en plus. |
| `TRANSPORT_LICENCE` | Licence de transport du véhicule, au nom du propriétaire ou exploitant | « Le taxi doit être couvert par la licence de transport de son exploitant ; ce n'est pas un titre personnel du chauffeur. » | Oui | Non | **À confirmer** — titre exigé des transporteurs (décret 2004/0607/PM modifié [S1][S2]) et des propriétaires de taxis à Yaoundé (« licence de première catégorie » [S6]). Catégorie exacte d'un taxi urbain (S1, S5 ou autre [S3][S7]) non vérifiée sur texte officiel. |
| `PUBLIC_TRANSPORT_CARD` | Carte de transport public (« carte bleue ») du véhicule | « La carte bleue du taxi est délivrée pour le véhicule à son exploitant. » | Oui | Non | **À confirmer** — exigée des propriétaires de taxis à Yaoundé [S6] ; frais de délivrance pour transport public et taxi [S8]. Texte de référence pour les taxis automobiles non consulté. |
| `PAID_TRANSPORT_AUTHORIZATION` | Autorisation applicable au transport rémunéré de passagers avec un véhicule personnel | « Transporter des passagers contre paiement avec ta voiture peut demander une autorisation : WAKA vérifie laquelle. » | Non | Oui | **À confirmer** — le décret du 10 octobre 2022 aurait créé une licence S10 pour les plateformes numériques de transport et renvoyé ses modalités à un arrêté [S9][S10] ; son application aux chauffeurs eux-mêmes, l'existence de l'arrêté et une autre catégorie citée (S9 [S7]) ne sont pas confirmées. Le décret est contesté par des syndicats [S11]. **L'abonnement WAKA ne remplace aucune autorisation.** |

Ne sont **pas** demandés, faute de nécessité établie : casier judiciaire, photos d'identité, justificatif de domicile, carte professionnelle de chauffeur, enregistrement communal (QR code et numéro de portière à Yaoundé [S6][S12]). Ce dernier est un point à étudier pour un lancement à Yaoundé ; il n'est pas ajouté au dossier.

Les informations du véhicule (huit champs C03) et ses six photos PH01–PH06 restent exigées pour les deux parcours, comme déjà réalisé.

## Règles du parcours

- N'afficher que les pièces de la situation du chauffeur : catégorie et droit d'utilisation déclarés.
- Pour chaque pièce, une phrase simple et le nom précis du document, comme dans le tableau.
- Le dossier s'enregistre et se complète plus tard ; rien n'est perdu entre deux visites.
- En cas de problème, l'agent demande la correction de **la pièce concernée seulement**. Les autres pièces acceptées le restent.
- Au renouvellement, les pièces acceptées et encore valides sont conservées. Une pièce dont la date de fin est dépassée redevient à fournir.
- Une pièce reçue n'est pas une pièce acceptée. L'acceptation est faite par un agent habilité, avec motif.
- **Approbation** seulement si : toutes les exigences applicables sont **établies** ; chaque pièce applicable est **acceptée et valide** ; les huit champs et les six photos sont **jugés acceptables** dans la même décision.

## Sources consultées le 29 octobre 2026

- [S1] Décret n° 2004/0607/PM du 17 mars 2004 fixant les conditions d'accès aux professions de transporteur routier — notice de la base NATLEX (OIT) : https://www.ilo.org/dyn/natlex/natlex4.detail?p_isn=68439
- [S2] Décret n° 2016/4384 du 22 septembre 2016 modifiant le décret de 2004 — Camerlex : https://www.camerlex.com/cameroun-decret-n-20164384-22-septembre-2016-modifiant-completant-certaines-dispositions-decret-n-20040607pm-17-mars2004-fixant-conditions-dacces-aux-professio/
- [S3] Note du ministre des Transports établissant de nouvelles licences — Lebledparle : https://www.lebledparle.com/transport-routier-le-ministre-des-transports-jean-ernest-massena-ngalle-bibehe-a-signe-une-note-etablissant-de-nouvelles-licences-pour-regir-le-secteur/
- [S4] Yango Pro Cameroun, guide des documents du chauffeur (source commerciale) : https://pro.yango.com/cm-fr/yaounde/knowledge-base/taxi/documents-for-cooperation/etre-en-regle-en-tant-que-chauffeur-vtc
- [S5] TakeTako, devenir chauffeur VTC au Cameroun (source commerciale) : https://taketako.com/blog/chauffeur/devenir-chauffeur-vtc-cameroun/
- [S6] Camer.be, enregistrement des taxis à Yaoundé (pièces exigées des propriétaires) : https://www.camer.be/index.php/actualites/societe/cameroun-qr-code-obligatoire-yaounde-ne-trouve-plus-ses-taxis
- [S7] Lefisk, licences de transport au Cameroun (blog, source secondaire) : https://lefisk.cm/blog/licence-transport-cameroun-categories-prix-dossier
- [S8] Lefisk, paiements en ligne du ministère des Transports (blog, source secondaire) : https://lefisk.cm/blog/recettes-non-fiscales-mint-cameroun
- [S9] Le Jour, réglementation du transport urbain (décret 2022 et licence S10) : https://lejour.cm/lurgence-de-la-reglementation-des-activites-de-transport-urbain-et-interurbain/
- [S10] Ecomatin, autorisation de Yango par le ministère des Transports : https://ecomatin.net/le-mint-accorde-lautorisation-yango-et-ses-partenaires-sont-officiellement-autorises-a-operer-au-cameroun
- [S11] L'Urgence, contestation du décret de 2022 par les transporteurs : https://lurgence.net/cameroun-ngalle-bibehe-au-front-pour-desamorcer-la-greve-annoncee-des-transporteurs-routiers/
- [S12] Agence Ecofin, identifiant numérique des taxis de Yaoundé : https://www.agenceecofin.com/actualites-numerique/2906-139708-cameroun-yaounde-modernise-ses-taxis-avec-un-identifiant-numerique-unique

Aucune de ces pages n'a valeur de texte officiel consolidé. Avant d'activer de vrais chauffeurs, il faut obtenir le Journal officiel ou une réponse écrite du ministère des Transports (MINT) sur :
- la catégorie de licence d'un taxi urbain ;
- la carte de transport public d'un taxi automobile ;
- la périodicité et le champ de la visite technique ;
- l'autorisation éventuelle d'un particulier transportant des passagers via une plateforme ;
- et, auprès d'un assureur, la garantie exigée pour le transport rémunéré.
