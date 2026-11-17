# Essai complet de WAKA avec un ami, chacun chez soi

Ce guide sert à essayer **toute** l'application à deux, sans être sur le même Wi-Fi :

- **toi (le fondateur)** : le PC qui fait tourner WAKA, l'administration, et un téléphone (iPhone ou Android) côté **client** ;
- **ton ami** : un téléphone Android, côté **chauffeur**, depuis chez lui.

Tout se passe en **version d'essai** : aucun SMS n'est envoyé, aucun vrai argent ne bouge (bac à sable Fapshi), et la base de données reste sur ton PC.

> Règles pendant l'essai
> - Utilisez des noms réalistes (par exemple « Paul Etoa »), jamais le mot « test ».
> - Pour les pièces du chauffeur, photographiez **n'importe quelle feuille**, pas une vraie carte d'identité ni un vrai permis : c'est un essai.
> - Le lien `https://…trycloudflare.com` ne se donne qu'à ton ami, par message privé. On le coupe à la fin.

---

## 0. Ce qu'il faut avant de commencer

| Qui | Quoi |
|---|---|
| Toi | Le PC allumé, avec le dossier WAKA et le dossier privé `%USERPROFILE%\WAKA-local` |
| Toi | `cloudflared.exe` 2026.10.1 dans `%USERPROFILE%\WAKA-local\tools\cloudflared-2026.10.1\` (voir `tools/README.md`) |
| Toi | Une application d'authentification gratuite sur ton téléphone (Google Authenticator ou Microsoft Authenticator), pour l'administration |
| Toi | WhatsApp ouvert avec ton ami : tu lui enverras le lien et ses codes de connexion |
| Ton ami | Un Android avec Chrome, la localisation activée, et du réseau |

Compte une heure pour le premier essai complet.

---

## 1. Démarrer WAKA sur le PC (toi)

Ouvre **quatre fenêtres PowerShell** dans le dossier du dépôt WAKA. Chaque fenêtre reste ouverte pendant tout l'essai.

**Fenêtre 1 : la base de données**
```powershell
[Console]::InputEncoding = New-Object Text.UTF8Encoding $false
& "$env:USERPROFILE\WAKA-local\mysql-local\start-mysql.ps1"
```

**Fenêtre 2 : le serveur** (attendre la ligne `APPLICATION_READY`, environ 30 secondes)
```powershell
[Console]::InputEncoding = New-Object Text.UTF8Encoding $false
.\tools\start-dev-backend.ps1 -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" -LocalApp
```

**Fenêtre 3 : l'application web**
```powershell
.\tools\start-web-preview.ps1
```

**Fenêtre 4 : le lien pour ton ami**
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\start-test-link.ps1
```
Une adresse `https://…trycloudflare.com` s'affiche. **Envoie-la à ton ami sur WhatsApp**, et ouvre-la aussi sur ton propre téléphone.

**Vérification :** l'adresse s'ouvre et montre l'écran d'accueil de WAKA. Si elle ne s'ouvre pas, relis les fenêtres 2 et 3 : une ligne rouge indique le problème.

---

## 2. Lire un code de connexion (toi, à chaque connexion)

Aucun SMS n'est envoyé. Quand quelqu'un demande un code, WAKA l'écrit dans un fichier sur ton PC. Dans une cinquième fenêtre :

```powershell
Get-Content "$env:USERPROFILE\WAKA-local\backend-local\app\codes-connexion-local.txt" -Tail 3
```

Prends la dernière ligne dont le numéro finit comme celui qui se connecte. **Pour ton ami, envoie-lui son code sur WhatsApp.** Le code expire vite : envoie-le dès qu'il l'a demandé.

---

## 3. Créer les deux comptes

| Étape | Toi (client) | Ton ami (chauffeur) |
|---|---|---|
| 3.1 | Ouvre le lien, saisis ton numéro (9 chiffres, commence par 6) | Ouvre le lien dans Chrome, saisis **un autre numéro** que le tien |
| 3.2 | Lis ton code (section 2) et saisis-le | Attend ton message WhatsApp avec son code, puis le saisit |
| 3.3 | Saisis ton nom complet | Saisit son nom complet |

Astuce pour ton ami : dans Chrome, menu ⋮ puis **Ajouter à l'écran d'accueil**, pour ouvrir WAKA comme une app.

**À vérifier :** chacun arrive sur l'accueil avec les onglets **Course**, **Voyages**, **Activité**, **Compte**.

---

## 4. Devenir agent de l'administration (toi, une seule fois par base)

L'administration sert à approuver le dossier du chauffeur. Elle s'ouvre **seulement sur ton PC**.

1. Construire l'administration (une fois), dans le dossier `apps/admin` :
   ```powershell
   flutter build web --release --dart-define=WAKA_API_BASE_URL=http://127.0.0.1:8091
   ```
2. La lancer depuis le dossier du dépôt, dans une nouvelle fenêtre :
   ```powershell
   python -I .\tools\serve-web-preview.py --admin
   ```
3. Ouvrir http://127.0.0.1:8091 sur le PC, saisir **ton numéro de compte client**, puis le code lu dans le fichier (section 2).
4. La page affiche **« Ton identifiant de compte : … »**. Copie-le.
5. Dans la fenêtre 2, arrête le serveur avec Ctrl+C, puis relance-le avec ton identifiant :
   ```powershell
   .\tools\start-dev-backend.ps1 -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" -LocalApp -FounderAccount <ton identifiant>
   ```
6. Reconnecte-toi à l'administration. À la **première configuration**, ajoute la clé affichée dans ton application d'authentification, puis saisis le code à 6 chiffres qu'elle montre. Ne note cette clé nulle part ailleurs.

**À vérifier :** tu vois les menus **Dossiers envoyés à l'examen**, **Argent**, **Habilitations** et **Journal d'audit**.

Le lien de ton ami reste le même pendant le redémarrage du serveur ; il doit seulement patienter une minute.

---

## 5. Le dossier chauffeur (ton ami, puis toi)

**Ton ami, sur son téléphone :**
1. Onglet **Compte**, puis **Chauffeur** (écran **Mode chauffeur**). Lire « Conduire avec WAKA : ce qu'il faut savoir ».
2. Choisir **Chauffeur taxi** (sans abonnement, plus simple pour l'essai).
3. Étape 1 : le véhicule. Chaque champ affiche sa règle en gris dessous. **Essayer exprès une erreur** (par exemple l'année « 88 ») : un message rouge doit apparaître sous le champ et le dossier ne doit pas avancer. Puis corriger.
4. Étapes suivantes : photographier une feuille pour chaque pièce demandée, puis **Envoyer mon dossier**.

**Toi, dans l'administration :**
1. **Dossiers envoyés à l'examen**, ouvrir le dossier de ton ami.
2. Regarder **Véhicule déclaré** et **Photos et pièces**.
3. Premier passage : choisir **Demander une correction**, écrire ce qu'il faut corriger, **Enregistrer la décision**.
4. Ton ami voit la demande dans **Mon accès chauffeur**, corrige et renvoie.
5. Second passage : **Approuver**, puis **Enregistrer la décision**.

**À vérifier :** ton ami voit son accès chauffeur actif et le bouton **Passer en ligne**.

---

## 6. Une course en ville (vous deux en même temps)

WAKA ne propose un client qu'à un chauffeur situé à **10 minutes de route au plus**. Comme vous n'êtes pas au même endroit, **ton ami t'envoie son quartier**, et c'est là que tu places le départ.

| Étape | Ton ami (chauffeur) | Toi (client) |
|---|---|---|
| 6.1 | **Mode chauffeur**, **Passer en ligne**. Chrome demande la position : **Autoriser**. Vérifier « Position envoyée à … » | |
| 6.2 | | Onglet **Course**. **Départ** : le quartier de ton ami. **Arrivée** : un lieu à quelques kilomètres |
| 6.3 | | Choisir **Personnes** = 1, comparer **Partagé** et **VIP** (le VIP vaut 12,5 places), choisir **Partagé** et lancer la recherche |
| 6.4 | Une **Nouvelle course** sonne, avec un compte à rebours, le prix et **Ta part**. **Accepter** | |
| 6.5 | | **Payer la course** dans les **3 minutes** : Mobile Money MTN avec le numéro **670000000** (il réussit toujours en bac à sable) |
| 6.6 | **Je suis arrivé au départ** | Le chauffeur apparaît dans **Ton chauffeur** ; note **Ton code à donner au chauffeur** |
| 6.7 | **Faire monter le client** avec le code que tu lui dictes par WhatsApp | |
| 6.8 | **Terminer la course** | La course passe dans **Activité** |

**À vérifier :**
- Le prix payé par le client est le même partout (devis, paiement, Activité).
- Chez le chauffeur, **Mon argent** puis **Mes gains** a augmenté de **Ta part**.
- Dans l'administration, menu **Argent** : **Attendu chez le prestataire** tient compte du paiement.

---

## 7. Les cas qui doivent bien se passer quand ça se passe mal

Refaites une course pour chaque ligne. Notez ce que vous voyez.

| Cas | Comment le provoquer | Ce qui doit se passer |
|---|---|---|
| Chauffeur qui refuse | Ton ami touche **Refuser** | Tu restes en recherche, pas de paiement demandé |
| Chauffeur qui ne répond pas | Ton ami laisse le compte à rebours finir | La proposition disparaît chez lui ; tu restes en recherche |
| Personne de disponible | Ton ami passe **hors ligne** avant ta demande | Après 2 minutes, un message clair dit qu'aucun chauffeur n'est trouvé |
| Paiement refusé | Payer avec un numéro qui échoue dans le bac à sable Fapshi | Un message d'échec, et le bouton **Réessayer** |
| Client qui annule | **Annuler la course** après le paiement | Remboursement indiqué ; la course disparaît chez le chauffeur |
| Chauffeur qui annule | **Annuler cette course (client remboursé)** | Tu vois l'annulation et le remboursement |
| Client absent | Ton ami arrive, attend 5 minutes, puis **Client absent (après 5 min)** | La course se ferme selon la règle d'absence |
| Réseau lent | Ton ami coupe la 4G une seconde pendant une action | Une barre de chargement, puis après 3 s « Ça prend un peu de temps… », puis un message d'erreur clair si rien ne revient |
| Mauvaise valeur dans un formulaire | Une plaque d'un seul caractère, 8 places… | Message rouge sous le champ, rien n'est envoyé |

---

## 8. Une course VIP

Même déroulé que la section 6 avec **VIP** et 3 personnes. **À vérifier :** le prix VIP est 12,5 fois celui d'une place partagée, et ton ami ne reçoit pas d'autre client pendant cette course.

---

## 9. Un voyage entre villes

| Étape | Ton ami (chauffeur) | Toi (client) |
|---|---|---|
| 9.1 | Onglet **Voyages**, **Publier un voyage** : départ, **Ajouter une escale**, arrivée, heures, places, **Prix par place** (10 000 F au plus), **Publier** | |
| 9.2 | | Onglet **Voyages**, choisir le voyage, le tronçon et le nombre de **Places**, **Payer mes places** (670000000) |
| 9.3 | Voir ta réservation dans **Passagers** | Voir **Embarquement prévu …** |
| 9.4 | **Embarquer** (ou **Absent** pour essayer l'autre cas) | |
| 9.5 | | **Signaler un problème** pour essayer les incidents ; ton ami peut **Répondre** ou **Contester** |

Essayez aussi **Annuler la réservation** : elle est gratuite jusqu'à 12 h avant le départ.

---

## 10. L'argent

| Qui | Action | À vérifier |
|---|---|---|
| Toi | **Compte**, **Mon argent**, **Recharger mon crédit** (670000000) | Aucun frais ; le crédit augmente exactement du montant |
| Toi | Payer une course avec **Mon crédit WAKA** | Le crédit baisse du prix de la course |
| Ton ami | **Mon argent**, **Retirer vers mon numéro** | Les frais sont affichés avant de confirmer ; le retrait part seulement vers le numéro de son compte |
| Toi | Administration, **Argent** | **Attendu chez le prestataire** suit les paiements et retraits ; **À régler** liste ce qui reste à traiter, ou « Rien à régler. » |

---

## 11. Parrainage et données personnelles

- **Toi** : **Compte**, **Parrainage**, **Copier mon code**, l'envoyer à ton ami.
- **Ton ami** : **Parrainage**, **Mon parrain**, coller le code, **Enregistrer ce parrain**. À vérifier : il ne peut plus le changer librement.
- **Chacun** : **Compte**, **Mes données** : voir ce que WAKA garde sur lui.

---

## 12. Noter les problèmes

Pour chaque problème, envoie :
1. une **capture d'écran** ;
2. l'**heure** à la minute près (pour retrouver la ligne dans la fenêtre du serveur) ;
3. **qui** (client ou chauffeur) et **ce qui venait d'être touché**.

Tu peux copier ce tableau et le remplir :

| N° | Section | Résultat (OK / problème) | Heure | Remarque |
|---|---|---|---|---|
| 1 | 3. Comptes | | | |
| 2 | 4. Agent | | | |
| 3 | 5. Dossier chauffeur | | | |
| 4 | 6. Course partagée | | | |
| 5 | 7. Cas difficiles | | | |
| 6 | 8. Course VIP | | | |
| 7 | 9. Voyage | | | |
| 8 | 10. Argent | | | |
| 9 | 11. Parrainage, données | | | |

---

## 13. Arrêter proprement

1. Fenêtre 4 (le lien) : **Ctrl+C**. Le lien ne marche plus pour personne.
2. Fenêtres 3, 2, puis la fenêtre de l'administration : **Ctrl+C**.
3. La base de données peut rester allumée ; elle s'arrête avec le PC.

Les comptes, le dossier et les courses restent dans la base locale pour le prochain essai.

---

## Ce que cet essai ne prouve pas

- **SMS réels** : le fournisseur SMS reste en attente ; les codes viennent du fichier.
- **Vrai argent** : le bac à sable Fapshi ne débite personne.
- **Charge réelle** : deux personnes ne disent rien du comportement avec des centaines de clients.
- **Application installée** : ici, ton ami utilise la version web. La navigation dans Google Maps ou Waze et le chauffeur en ligne écran éteint se vérifient avec l'APK (`tools/install-android-phone.ps1`), téléphone branché au PC.
