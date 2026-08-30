# Minecraft-Plugins-Dev

Monorepo des plugins custom du serveur Paper **HORUS** (`\\HORUS\appdata\minecraft`, Paper 26.1.2, Java 25). Le repo vit sur le partage du serveur ; le runtime (jar Paper, `libraries/`, `plugins/`) reste à la racine du serveur, un niveau au-dessus.

## Plugins

Chaque plugin est un sous-dossier avec ses sources dans `src/` (`src/plugin.yml` + packages Java) et produit son propre jar dans `plugins/` :

- **[KamoofLite](KamoofLite/README.md)** — déguisement par tête de joueur, masse unique, rituel des têtes, portail décoré (style Kamoof SMP).
- **LanceSansFaim** — le lunge de la lance ne coûte plus de faim ni de saturation (annule l'`EntityExhaustionEvent` raison `ENCHANTMENT_EFFECT` quand le joueur tient une lance enchantée Lunge).

## Build

Compile avec le JDK 25 local (`C:\Users\valas\jdk25\...`), classpath = `paper-*.jar` + `libraries/` du serveur (trouvés en remontant l'arborescence). Sort chaque jar directement dans `plugins/` du serveur, avec backup `.bak` horodaté de l'ancien.

```powershell
# Tous les plugins
powershell -ExecutionPolicy Bypass -File '\\HORUS\appdata\minecraft\Minecraft-Plugins-Dev\build.ps1'
# Un seul
powershell -ExecutionPolicy Bypass -File '\\HORUS\appdata\minecraft\Minecraft-Plugins-Dev\build.ps1' LanceSansFaim
```

```bash
# Linux / WSL
bash build.sh [NomPlugin]
```

Puis `restart` dans la console du serveur (interface Unraid).

## Ajouter un plugin

Créer `MonPlugin/src/` avec `plugin.yml` et les packages Java — le script de build le découvre tout seul et produira `plugins/MonPlugin.jar` (le nom du jar = le nom du dossier).
