# KamoofGuard

Anticheat maison pour **Paper 26.1.2**, écrit uniquement avec l'API Bukkit/Paper (aucune
dépendance : ni ProtocolLib, ni PacketEvents, ni NMS).

---

## 1. Analyse des anticheats existants

| Anticheat | Licence | Approche | Points forts | Limites |
|---|---|---|---|---|
| **Grim** | GPLv3, gratuit | **Prédiction** : rejoue la physique du client côté serveur et compare | Le meilleur gratuit ; reach et anti-KB excellents ; multi-thread ; Folia ; Geyser | Configuration fine nécessaire pour le killaura |
| **Vulcan** | payant (~45 $) | Analyse **paquets** (PacketEvents) | Le catalogue de checks le plus large (voir plus bas), GUI, Discord | Payant, closed-source |
| **Matrix** | payant (~25 $) | Paquets via ProtocolLib | Bon marché, très bon en 1.8 | Killaura plus faible, versions ≤ 1.20 |
| **Spartan** | payant | Mixte | Compatibilité 1.7 → récent | Réputé bavard (faux positifs) |
| **NoCheatPlus** | GPL, gratuit | **Règles côté serveur** (API Bukkit) | La référence historique du check API-only ; c'est le modèle le plus proche de KamoofGuard | Combat/killaura faibles, projet peu actif |
| **AAC** | abandonné | — | — | Ne plus utiliser |
| **Polar / Intave / Verus** | payant / SaaS | ML ou paquets | — | Hors sujet ici (cloud ou closed) |

Catalogue de checks de Vulcan, qui sert de plan de référence :

- **Combat** : Aim, AutoBlock, AutoClicker, Criticals, Hitbox, KillAura, Reach, Velocity
- **Mouvement** : BoatFly, EntitySpeed, Elytra, FastClimb, Flight, Jesus, Jump, Motion, NoSlow,
  Speed, Sprint, Step, Strafe, WallClimb
- **Joueur** : BadPackets, Baritone, Crash, FastBreak, FastPlace, FastUse, GroundSpoof,
  HackedClient, Improbable, Invalid, Inventory, PingSpoof, Scaffold

NoCheatPlus, lui, couvre : Fastbreak, Fastplace, Direction, Reach, Frequency, Improbable, Angle,
Morepackets, Criticals, Fastclick, Nofall, Fastconsume, Fastheal, Creativefly, Survivalfly.

**Conclusion de l'analyse.** Tout ce qui relève de la lecture fine des paquets (BadPackets,
PingSpoof, GroundSpoof exact, Aim au 1/20e de tick, Timer précis) et tout ce qui relève de la
prédiction physique complète (le modèle de Grim) est **hors de portée** d'un plugin API-only.
KamoofGuard reprend donc le périmètre réaliste : celui de NoCheatPlus, modernisé pour 26.1.2 et
complété par ce que l'API Paper expose aujourd'hui (`getPing`, `getCurrentInput`, `isHandRaised`,
`PrePlayerAttackEntityEvent`, attributs de portée).

---

## 2. Checks implémentés

### Mouvement (`PlayerMoveEvent`)
| Check | Détecte | Méthode |
|---|---|---|
| `fly` | Fly, hover, jetpack | Montée prolongée ou immobilité verticale sans support |
| `gravity` | Glide / slow-fall client | Chute comparée à `(dy - 0.08) × 0.98` |
| `speed` | Speed, bhop | Ratio vitesse réelle / vitesse vanilla (attribut + effets + sprint + saut + glace), moyenné sur 1 s |
| `nofall` | NoFall, GroundSpoof | Le client annonce « au sol » alors qu'aucun bloc ne le porte ; la distance de chute est **restaurée** |
| `step` | Step / spider | Montée > 0,68 bloc de sol à sol |
| `jesus` | Water walk | Déplacement à surface d'eau, pieds hors du liquide |
| `timer` | Timer / bunny | Plus de 25 paquets de position par seconde |
| `phase` | NoClip / phase | Position dans un bloc plein, retour à la dernière position saine |
| `fastclimb` | Ladder speed | > 0,24 bloc/tick sur une échelle |
| `noslow` | NoSlow / AutoBlock | Vitesse pleine pendant l'utilisation d'un objet |

### Combat (`PrePlayerAttackEntityEvent`, `PlayerAnimationEvent`, `PlayerVelocityEvent`)
| Check | Détecte | Méthode |
|---|---|---|
| `reach` | Reach | Distance œil → boîte de collision, compensée par le ping ; coup unique + moyenne sur 8 coups |
| `killaura` | KillAura, backtrack | Angle regard/cible > 85°, ou coup à travers un bloc (ray trace) |
| `multiaura` | Aura multi-cibles | Deux entités différentes frappées en < 250 ms |
| `noswing` | KillAura sans animation | Aucun paquet de swing dans les 2 ticks suivant le coup |
| `autoclicker` | AutoClicker | CPS > 16, **ou** écart-type des intervalles < 8 ms (régularité inhumaine) |
| `criticals` | Criticals | Série de coups critiques sans saut réel |
| `velocity` | AntiKB | Déplacement réel < 30 % du recul attendu, mur exclu par ray trace |

### Blocs et objets
| Check | Détecte | Méthode |
|---|---|---|
| `fastbreak` | Nuker lent, fast break | > 15 blocs durs/s |
| `nuker` | Nuker | Deux blocs à > 2,5 blocs d'écart cassés en < 120 ms |
| `fastplace` | Fast place | > 9 poses/s |
| `scaffold` | Scaffold / bridge | Pose sous les pieds sans viser vers le bas, ou bloc posé derrière soi |
| `blockreach` | Reach sur blocs | Casse/pose à plus de 5,3 blocs — **annulée** |
| `fastuse` | Spam d'utilisation | > 15 clics droits/s |
| `xray` | X-ray | Ratio minerais précieux / blocs sous y=16 — **alerte statistique seulement** |
| `illegalitems` | Items impossibles | Piles > max, enchantements > niveau max, blocs créatif |
| `inventorymove` | InventoryMove | Déplacement avec un conteneur ouvert (**désactivé par défaut** : faux positifs Bedrock/Geyser) |
| `chatspam` | Spam | < 500 ms entre messages, ou message répété 3 fois |

---

## 3. Fonctionnement

- Chaque détection ajoute des **VL** (violation level) qui **décroissent** chaque minute.
- `alert-vl` → le staff (`kguard.alerts`) est prévenu, la ligne part dans la console et dans
  `plugins/KamoofGuard/violations.log`.
- `punish-vl` → sanction, **uniquement si `punish: true`** (interrupteur maître, `false` par défaut).
- Exemptions automatiques : `kguard.bypass`, créatif / vol, spectateur, 6 s après la connexion,
  3 s après une téléportation, 2 s après un knockback, 1,5 s après des dégâts, ping > 300,
  TPS < 18, véhicule, élytre, trident riptide.

### Commandes — `/kguard` (alias `/kg`, `/anticheat`)
`info` · `alerts` · `verbose` · `vl <joueur>` · `top` · `clear <joueur>` · `exempt <joueur>` · `reload`

### Permissions
`kguard.alerts` (op) · `kguard.command` (op) · `kguard.bypass` (personne)

---

## 4. Ce que ce plugin ne fait PAS

Sans couche paquets, il est **impossible** de faire : BadPackets, PingSpoof, Timer au tick près,
analyse d'Aim (rotations intra-tick), backtrack/lag switch, détection de client (HackedClient),
Baritone, et la prédiction complète de la physique façon Grim. Un tricheur discret et bien réglé
passera. L'objectif est de détecter le tricheur **blatant** sur un petit serveur privé, sans
dépendance ni licence.

Pour aller plus loin sur un serveur public : installer **Grim** (gratuit, prédiction) et garder
KamoofGuard pour ses checks maison (xray, illegalitems, chatspam).

## 5. Réglage

Tout est dans `plugins/KamoofGuard/config.yml` (généré au premier démarrage, commenté).
Procédure conseillée : laisser tourner en **alertes seules** quelques jours, faire `/kguard verbose`
en jouant normalement pour repérer les faux positifs, ajuster les seuils, puis seulement activer
`punish: true`.
