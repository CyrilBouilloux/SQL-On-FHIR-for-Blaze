#!/usr/bin/env bash
# kubernetes/chart/deriver.sh — rend le chart servable. Rejouable.
#
# Chart.yaml et values-versions.yaml sont DERIVES de versions.env (aucun numero
# ne s'ecrit dans le chart), et nginx/nginx.conf est mis en scene sous files/,
# ou Helm sait le lire (Helm ne lit rien hors du chart). Copie de travail,
# jamais versionnee ; la source reste nginx/nginx.conf.
set -euo pipefail

CHART="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RACINE="$(cd -- "${CHART}/../.." && pwd)"
source "${RACINE}/lib/codes.sh"

version() {
    local valeur
    valeur="$(sed -n "s/^${1}=\(.*\)$/\1/p" "${RACINE}/versions.env" | tail -1)"
    [ -n "$valeur" ] || { printf 'versions.env ne porte pas %s\n' "$1" >&2; exit "$EX_CONFIG"; }
    printf '%s' "$valeur"
}

CHART_VERSION="$(version CHART_VERSION)"
BLAZE="$(version BLAZE_VERSION)"
NGINX="$(version NGINX_VERSION)"
NGINX_DIGEST="$(version NGINX_IMAGE_DIGEST)"
MINEURE="$(version K8S_MINOR)"

sed -e "s|@CHART_VERSION@|${CHART_VERSION}|g" -e "s|@BLAZE_VERSION@|${BLAZE}|g" \
    "${CHART}/Chart.yaml.in" > "${CHART}/Chart.yaml"

cat > "${CHART}/values-versions.yaml" <<EOT
# DERIVE de versions.env par deriver.sh — ne pas editer.
versions:
  blaze: "${BLAZE}"
  nginx: "${NGINX}"
  nginxDigest: "${NGINX_DIGEST}"
  k8sMinor: "${MINEURE}"
EOT

rm -rf "${CHART}/files"
mkdir -p "${CHART}/files"
cp "${RACINE}/nginx/nginx.conf" "${CHART}/files/nginx.conf"
cmp -s "${RACINE}/nginx/nginx.conf" "${CHART}/files/nginx.conf" || {
    printf 'mise en scene differente de la source : nginx/nginx.conf\n' >&2; exit "$EX_ECHEC"; }

printf 'chart derive — version %s, Blaze %s, nginx %s, PSA v%s\n' \
    "$CHART_VERSION" "$BLAZE" "$NGINX" "$MINEURE"
