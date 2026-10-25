#!/usr/bin/env bash
#
# build/construire-jar.sh — construit le jar du module (F-034, T-098).
#
# Le jar est l'archive des fichiers suivis de modules/*/src et modules/*/resources,
# chemins relatifs à ces racines. Aucune compilation :
# les sources tournent non compilées. Il se pose DEVANT le jar de l'image Blaze publiée
# dans le classpath ; il ne le contient pas. Rejouable : entrées triées, horodatage fixe.
# Brique : module `zipfile` de Python 3 (aucun zip/jar à installer, déjà requis par le dépôt).
#
# Usage : build/construire-jar.sh      (depuis n'importe quel répertoire)
# Sortie : build/blaze-addon.jar ; code 0 succès, 3 prérequis ou état non établi.

set -euo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../lib/codes.sh
source "$RACINE/lib/codes.sh"
cd "$RACINE"

JAR="build/blaze-addon.jar"
N=4
die() { echo "construire-jar.sh : $*" >&2; exit "$EX_PREREQUIS"; }

echo "étape 1/$N : lire versions.env (clés BLAZE_VERSION, BLAZE_IMAGE_DIGEST)"
for cle in BLAZE_VERSION BLAZE_IMAGE_DIGEST; do
  ligne="$(/usr/bin/grep -E "^$cle=" versions.env)" || die "clé $cle absente de versions.env"
  declare "$cle=${ligne#*=}"
done
echo "  image publiée visée : samply/blaze:$BLAZE_VERSION@$BLAZE_IMAGE_DIGEST"

echo "étape 2/$N : lister les fichiers suivis de modules/*/src et modules/*/resources"
command -v python3 >/dev/null || die "python3 absent"
LISTE="$(mktemp)"; TMP="$(mktemp "$RACINE/build/.jar.XXXXXX")"
trap 'rm -f "$LISTE" "$TMP"' EXIT
if [[ "$(git rev-parse --show-toplevel 2>/dev/null || true)" == "$RACINE" ]]; then
  git ls-files -z -- ':(glob)modules/*/src/**' ':(glob)modules/*/resources/**' >"$LISTE" \
    || die "git ls-files a échoué"
else
  # copie hors de son propre dépôt (dépôt de publication, archive décompressée)
  echo "  pas un dépôt git à la racine : fichiers lus sur le disque"
  /usr/bin/find modules -type f \( -path 'modules/*/src/*' -o -path 'modules/*/resources/*' \) -print0 >"$LISTE" \
    || die "find a échoué"
fi
[[ -s "$LISTE" ]] || die "aucun fichier suivi sous modules/*/src ni modules/*/resources"
echo "  $(tr -cd '\0' <"$LISTE" | wc -c) fichiers"

echo "étape 3/$N : archiver (entrées triées, horodatage 1980-01-01, sans compilation)"
python3 -I - "$LISTE" "$TMP" <<'PY'
import re, sys, zipfile
liste, sortie = sys.argv[1:3]
fichiers = [f for f in open(liste, "rb").read().decode().split("\0") if f]
rel = lambda f: re.sub(r"^modules/[^/]+/(src|resources)/", "", f)
entrees = sorted((rel(f), f) for f in fichiers)
noms = [n for n, _ in entrees]
assert len(set(noms)) == len(noms), "deux modules fournissent la même entrée"
with zipfile.ZipFile(sortie, "w", zipfile.ZIP_DEFLATED) as z:
    for nom, chemin in entrees:
        i = zipfile.ZipInfo(nom, (1980, 1, 1, 0, 0, 0))
        i.compress_type, i.create_system, i.external_attr = zipfile.ZIP_DEFLATED, 3, 0o644 << 16
        z.writestr(i, open(chemin, "rb").read())
PY
chmod 0644 "$TMP"   # mktemp crée en 0600 : le jar doit rester lisible par l'utilisateur non-root de l'image
mv "$TMP" "$JAR"

echo "étape 4/$N : imprimer l'empreinte et le chemin du jar"
echo "  jar : $RACINE/$JAR"
echo "  sha256 : $(sha256sum "$JAR" | cut -d' ' -f1)"
