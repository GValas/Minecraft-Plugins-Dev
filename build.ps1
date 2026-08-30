# Build des plugins du monorepo -> plugins/<NomDuPlugin>.jar
# Lancer : powershell -ExecutionPolicy Bypass -File '\\HORUS\appdata\minecraft\Minecraft-Plugins-Dev\build.ps1' [NomPlugin ...]
# Sans argument : construit TOUS les plugins (chaque sous-dossier contenant src\plugin.yml).
# Necessite JDK 25 (l'API Paper 26.1.2 est en class version 69).

param([string[]]$Plugins)

$ErrorActionPreference = 'Stop'

# --- Chemins ---
$RepoRoot = $PSScriptRoot
$Javac    = 'C:\Users\valas\jdk25\jdk-25.0.3+9\bin\javac.exe'
$Jar      = 'C:\Users\valas\jdk25\jdk-25.0.3+9\bin\jar.exe'

# Racine du serveur Paper : on remonte jusqu'a trouver le paper-*.jar
$ServerRoot = $RepoRoot
while ($ServerRoot -and -not (Get-ChildItem -Path $ServerRoot -Filter 'paper-*.jar' -File -ErrorAction SilentlyContinue)) {
    $parent = Split-Path -Parent $ServerRoot
    if ($parent -eq $ServerRoot) { $ServerRoot = $null; break }
    $ServerRoot = $parent
}
if (-not $ServerRoot) { throw "paper-*.jar introuvable en remontant depuis $RepoRoot" }

$PaperJar   = (Get-ChildItem -Path $ServerRoot -Filter 'paper-*.jar' -File | Select-Object -First 1).FullName
$LibDir     = Join-Path $ServerRoot 'libraries'
$PluginsDir = Join-Path $ServerRoot 'plugins'

Write-Host "Racine serveur : $ServerRoot"
Write-Host "Paper jar      : $PaperJar"

if (-not (Test-Path $Javac)) { throw "javac introuvable : $Javac" }

# --- Classpath = paper + tous les jars de libraries/ ---
$cp = @($PaperJar)
if (Test-Path $LibDir) {
    $cp += (Get-ChildItem -Path $LibDir -Recurse -Filter *.jar | ForEach-Object { $_.FullName })
}
$ClassPath = $cp -join ';'

# --- Decouverte des plugins : sous-dossier avec src\plugin.yml ---
$AllPlugins = Get-ChildItem -Path $RepoRoot -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'src\plugin.yml') }
if ($Plugins) {
    $AllPlugins = $AllPlugins | Where-Object { $Plugins -contains $_.Name }
    $absents = $Plugins | Where-Object { $n = $_; -not ($AllPlugins | Where-Object Name -eq $n) }
    if ($absents) { throw "Plugin(s) introuvable(s) : $($absents -join ', ')" }
}
if (-not $AllPlugins) { throw "Aucun plugin trouve (sous-dossier avec src\plugin.yml)" }

foreach ($p in $AllPlugins) {
    $Name     = $p.Name
    $SrcDir   = Join-Path $p.FullName 'src'
    $BuildDir = Join-Path $p.FullName 'build'
    $OutJar   = Join-Path $PluginsDir "$Name.jar"

    Write-Host ''
    Write-Host "=== $Name ==="

    if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

    Write-Host "Compilation (JDK 25)..."
    $Sources = Get-ChildItem -Path $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    & $Javac -encoding UTF-8 -cp $ClassPath -d $BuildDir $Sources
    if ($LASTEXITCODE -ne 0) { throw "Echec compilation $Name (code $LASTEXITCODE)" }

    # plugin.yml + ressources (structures NBT) a la racine du jar
    Copy-Item (Join-Path $SrcDir 'plugin.yml') $BuildDir -Force
    Get-ChildItem -Path $SrcDir -Filter '*.nbt' -File | Copy-Item -Destination $BuildDir -Force

    # Backup de l'ancien jar
    if (Test-Path $OutJar) {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        Copy-Item $OutJar "$OutJar.$stamp.bak" -Force
        Write-Host "Backup -> $Name.jar.$stamp.bak"
    }

    Write-Host "Packaging du jar..."
    & $Jar cf $OutJar -C $BuildDir .
    if ($LASTEXITCODE -ne 0) { throw "Echec jar $Name (code $LASTEXITCODE)" }

    Write-Host "OK -> $OutJar"
}

Write-Host ''
Write-Host "Tape 'restart' dans la console du serveur pour recharger."
