# KamoofLite — source

Plugin de déguisement pour le serveur Paper **HORUS** (`\\HORUS\appdata\minecraft`).
Le projet vit dans le monorepo `\\HORUS\appdata\minecraft\Minecraft-Plugins-Dev\` (sous-dossier
`KamoofLite/`) ; le runtime du serveur (jar Paper, `libraries/`, `plugins/`) reste à la racine du
serveur. Build : `build.ps1` / `build.sh` à la racine du monorepo.

## Ce que ça fait

- Tu lâches ta tête (avec ton skin) en mourant.
- Clic-droit sur une tête de joueur → tu te déguises en lui.
- `/undisguise` → tu reprends ton apparence. La mort réinitialise aussi le déguisement.
- Pas de persistance : un déguisement ne survit pas à une reconnexion.

## Version

**v2.3** — base own-UUID (skin sur ton perso + pas de doublon TAB) **+ couche NMS additive**
pour corriger le **nom flottant au-dessus de la tête** (qui restait le vrai pseudo en v2.2) :
on remplace le `GameProfile.name` NMS par le pseudo du déguisement (textures copiées), puis on
renvoie le joueur aux autres clients (`hide`/`show`) pour qu'ils relisent ce nom.

Tout le NMS est en `try/catch` : si les mappings de cette version ne collent pas, le **skin
fonctionne quand même** (repli sur le comportement v2.2).

⚠️ **À finaliser en test live** : le rafraîchissement de **ta propre vue (F5)** nécessite un
paquet *respawn* dont la signature dépend de la version. La v2.3 logge un **diagnostic** dans la
console (`[diag] ...`) au premier déguisement. Build, déguise-toi une fois, récupère ces lignes
`[diag]` et donne-les à Claude → il finalise le respawn self-view (le `TODO(live)` dans
`resendToSelf`). Pour les **autres** joueurs, le nom est déjà corrigé.

## Arborescence

```
<racine serveur>/
  paper-26.1.2-72.jar        runtime serveur (NE PAS deplacer)
  libraries/                 deps Paper (classpath)
  plugins/KamoofLite.jar     sortie du build
  dev/                       <-- tout le projet de dev
    .vscode/settings.json      classpath IntelliSense (Paper + libraries via ../)
    .vscode/extensions.json    extensions Java recommandees (VS Code les propose)
    CLAUDE.md                  contexte serveur + plugin
    src/
      kamoof/KamoofLite.java   code du plugin
      plugin.yml               metadata (va à la racine du jar)
      build.ps1                build Windows (javac + jar local)  <-- methode principale
      build.sh                 build Linux / WSL (equivalent)
      README.md                ce fichier
```

## Build

> ❌ **Pas de devcontainer** : le projet est sur un partage réseau (`\\HORUS\...`) et Docker
> Desktop refuse de bind-monter un chemin réseau (UNC comme lettre mappée). On build donc en
> local — ce qui ne demande rien de plus puisque le JDK 25 est déjà installé.

Le classpath de compilation = `paper-26.1.2-72.jar` + tous les jars de `libraries/`
(à la racine du serveur, un niveau au-dessus de `dev/`). Les scripts retrouvent cette racine
tout seuls en remontant jusqu'au `paper-*.jar`. Il faut **JDK 25** (l'API Paper 26.1.2 est en
class version 69 ; un JDK plus ancien échoue).

### Windows (méthode principale)

JDK 25 attendu à `C:\Users\valas\jdk25\jdk-25.0.3+9\` :

```powershell
powershell -ExecutionPolicy Bypass -File dev\src\build.ps1
```

### Linux / WSL (équivalent)

```bash
bash dev/src/build.sh
```

### Dev dans VS Code (sans conteneur)

Ouvre le dossier `dev/` : VS Code propose les extensions Java (via `extensions.json`) et
`settings.json` fournit le classpath, donc l'auto-complétion sur les imports Bukkit/NMS marche
directement, sans Maven ni Docker.

Les deux scripts sauvegardent l'ancien `plugins/KamoofLite.jar` en `.bak` puis produisent
le nouveau jar. Ensuite, taper `restart` dans la console du serveur pour recharger.
