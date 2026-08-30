#!/usr/bin/env bash
# Build des plugins du monorepo -> plugins/<NomDuPlugin>.jar
# Usage : bash build.sh [NomPlugin ...]   (sans argument : tous les plugins)
# Necessite JDK 25 (l'API Paper 26.1.2 est en class version 69).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Racine du serveur Paper : on remonte jusqu'a trouver le paper-*.jar
SERVER_ROOT="$REPO_ROOT"
while [ -n "$SERVER_ROOT" ] && ! ls "$SERVER_ROOT"/paper-*.jar >/dev/null 2>&1; do
    parent="$(dirname "$SERVER_ROOT")"
    [ "$parent" = "$SERVER_ROOT" ] && { echo "paper-*.jar introuvable" >&2; exit 1; }
    SERVER_ROOT="$parent"
done

PAPER_JAR="$(ls "$SERVER_ROOT"/paper-*.jar | head -n1)"
LIB_DIR="$SERVER_ROOT/libraries"
PLUGINS_DIR="$SERVER_ROOT/plugins"

echo "Racine serveur : $SERVER_ROOT"
echo "Paper jar      : $PAPER_JAR"

# --- Classpath = paper + tous les jars de libraries/ ---
CP="$PAPER_JAR"
if [ -d "$LIB_DIR" ]; then
    while IFS= read -r j; do CP="$CP:$j"; done < <(find "$LIB_DIR" -name '*.jar')
fi

# --- Decouverte des plugins : sous-dossier avec src/plugin.yml ---
if [ $# -gt 0 ]; then
    plugins=("$@")
else
    plugins=()
    for d in "$REPO_ROOT"/*/; do
        [ -f "$d/src/plugin.yml" ] && plugins+=("$(basename "$d")")
    done
fi
[ ${#plugins[@]} -gt 0 ] || { echo "Aucun plugin trouve" >&2; exit 1; }

for name in "${plugins[@]}"; do
    src_dir="$REPO_ROOT/$name/src"
    build_dir="$REPO_ROOT/$name/build"
    out_jar="$PLUGINS_DIR/$name.jar"
    [ -f "$src_dir/plugin.yml" ] || { echo "Plugin introuvable : $name" >&2; exit 1; }

    echo ""
    echo "=== $name ==="

    rm -rf "$build_dir"
    mkdir -p "$build_dir"

    echo "Compilation (JDK 25)..."
    find "$src_dir" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -cp "$CP" -d "$build_dir"

    cp "$src_dir/plugin.yml" "$build_dir/"
    find "$src_dir" -maxdepth 1 -name '*.nbt' -exec cp {} "$build_dir/" \;

    if [ -f "$out_jar" ]; then
        stamp="$(date +%Y%m%d-%H%M%S)"
        cp "$out_jar" "$out_jar.$stamp.bak"
        echo "Backup -> $name.jar.$stamp.bak"
    fi

    echo "Packaging du jar..."
    jar cf "$out_jar" -C "$build_dir" .

    echo "OK -> $out_jar"
done

echo ""
echo "Tape 'restart' dans la console du serveur pour recharger."
