# Build de KamoofLite -> plugins/KamoofLite.jar
# Lancer depuis n'importe ou : powershell -ExecutionPolicy Bypass -File dev\src\build.ps1
# Necessite JDK 25 (l'API Paper 26.1.2 est en class version 69 ; le JDK 23 du PATH est trop vieux).

$ErrorActionPreference = 'Stop'

# --- Chemins ---
$SrcDir     = $PSScriptRoot                              # ...\dev\src
$Javac      = 'C:\Users\valas\jdk25\jdk-25.0.3+9\bin\javac.exe'
$Jar        = 'C:\Users\valas\jdk25\jdk-25.0.3+9\bin\jar.exe'

# Racine du serveur Paper : on remonte jusqu'a trouver le paper-*.jar
$ServerRoot = $SrcDir
while ($ServerRoot -and -not (Get-ChildItem -Path $ServerRoot -Filter 'paper-*.jar' -File -ErrorAction SilentlyContinue)) {
    $parent = Split-Path -Parent $ServerRoot
    if ($parent -eq $ServerRoot) { $ServerRoot = $null; break }
    $ServerRoot = $parent
}
if (-not $ServerRoot) { throw "paper-*.jar introuvable en remontant depuis $SrcDir" }

$PaperJar   = (Get-ChildItem -Path $ServerRoot -Filter 'paper-*.jar' -File | Select-Object -First 1).FullName
$LibDir     = Join-Path $ServerRoot 'libraries'
$PluginsDir = Join-Path $ServerRoot 'plugins'
$OutJar     = Join-Path $PluginsDir 'KamoofLite.jar'
$BuildDir   = Join-Path $SrcDir 'build'

Write-Host "Racine serveur : $ServerRoot"
Write-Host "Paper jar      : $PaperJar"

if (-not (Test-Path $Javac)) { throw "javac introuvable : $Javac" }

# --- Classpath = paper + tous les jars de libraries/ ---
$cp = @($PaperJar)
if (Test-Path $LibDir) {
    $cp += (Get-ChildItem -Path $LibDir -Recurse -Filter *.jar | ForEach-Object { $_.FullName })
}
$ClassPath = $cp -join ';'

# --- Compilation ---
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

Write-Host "Compilation (JDK 25)..."
$Sources = Get-ChildItem -Path (Join-Path $SrcDir 'kamoof') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $Javac -encoding UTF-8 -cp $ClassPath -d $BuildDir $Sources
if ($LASTEXITCODE -ne 0) { throw "Echec compilation (code $LASTEXITCODE)" }

# --- plugin.yml + ressources (structures NBT du rituel) a la racine du jar ---
Copy-Item (Join-Path $SrcDir 'plugin.yml') (Join-Path $BuildDir 'plugin.yml') -Force
Copy-Item (Join-Path $SrcDir 'ritual.nbt') (Join-Path $BuildDir 'ritual.nbt') -Force
Copy-Item (Join-Path $SrcDir 'ritualbase.nbt') (Join-Path $BuildDir 'ritualbase.nbt') -Force
Copy-Item (Join-Path $SrcDir 'portal.nbt') (Join-Path $BuildDir 'portal.nbt') -Force

# --- Backup de l'ancien jar ---
if (Test-Path $OutJar) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    Copy-Item $OutJar "$OutJar.$stamp.bak" -Force
    Write-Host "Backup -> KamoofLite.jar.$stamp.bak"
}

# --- Packaging ---
Write-Host "Packaging du jar..."
& $Jar cf $OutJar -C $BuildDir plugin.yml -C $BuildDir ritual.nbt -C $BuildDir ritualbase.nbt -C $BuildDir portal.nbt -C $BuildDir kamoof
if ($LASTEXITCODE -ne 0) { throw "Echec jar (code $LASTEXITCODE)" }

Write-Host "OK -> $OutJar"
Write-Host "Tape 'restart' dans la console du serveur pour recharger."
