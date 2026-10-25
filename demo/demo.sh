#!/usr/bin/env bash
#
# demo/demo.sh — démonstration de l'ensemble (Blaze + module, nginx du chart) sur ce poste.
# OUTIL DE POSTE, HORS LIVRAISON (F-037, ADR 0013) : Kubernetes reste la seule livraison.
#
#   bash demo/demo.sh monter       # construit le jar, démarre, attend, charge le jeu
#   bash demo/demo.sh arreter      # retire les objets du projet demo-blaze, et eux seuls
#   bash demo/demo.sh parametres   # liste les variables et ports que lit la démonstration
#
# Versions et empreintes d'images : versions.env (lues par compose, jamais recopiées).
# `monter` refuse AVANT toute création (port occupé, objets existants, jeu altéré, prérequis).
# Sorties : 0 succès · 2 usage · 3 prérequis absent (dont nginx/nginx.conf) ou état non établi · 5 serveur non prêt à
# temps · 6 port ou objets déjà pris · 8 jeu non conforme.
set -euo pipefail

RACINE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${RACINE}/lib/codes.sh"

# Paramètres lus (documentés par T-108 ; `parametres` les liste).
: "${DEMO_PORT:=18090}"                  # port hôte (127.0.0.1) du reverse proxy
: "${DEMO_BLAZE_XMX:=768m}"              # tas JVM de Blaze
: "${DEMO_BLAZE_MEMOIRE:=1536m}"         # plafond mémoire du conteneur Blaze
: "${DEMO_ATTENTE_SECONDES:=180}"        # délai d'attente de /fhir/metadata
: "${DEMO_JAR:=${RACINE}/build/blaze-addon.jar}"   # jar du module (build/construire-jar.sh)
: "${DEMO_EXPORT_MIO:=128}"               # taille (Mio) de la tmpfs /app/export, comptée dans la mémoire du conteneur
# Dérivé de DEMO_EXPORT_MIO, jamais réglé à part : plafond du module (VIEWDEFINITION_EXPORT_MAX_BYTES),
# 8 Mio sous la tmpfs (précision du contrôle du module : 1 Mio) pour que le module refuse avant ENOSPC.
[[ "${DEMO_EXPORT_MIO}" =~ ^[0-9]+$ ]] && [ "${DEMO_EXPORT_MIO}" -gt 8 ] \
    || { echo "DEMO_EXPORT_MIO : entier de Mio supérieur à 8 attendu" >&2; exit "${EX_USAGE}"; }
DEMO_EXPORT_MAX_BYTES=$(( (DEMO_EXPORT_MIO - 8) * 1048576 ))
export DEMO_PORT DEMO_BLAZE_XMX DEMO_BLAZE_MEMOIRE DEMO_JAR DEMO_EXPORT_MIO DEMO_EXPORT_MAX_BYTES

PROJET=demo-blaze
OBJETS_CONTENEURS=(demo-blaze-blaze demo-blaze-nginx)
OBJET_RESEAU=demo-blaze-reseau
OBJET_VOLUME=demo-blaze-donnees
compose() { docker compose -p "${PROJET}" -f "${RACINE}/demo/compose.yaml" --env-file "${RACINE}/versions.env" "$@"; }

prerequis() {
    local c
    for c in docker curl jq ss sha256sum python3; do
        command -v "$c" >/dev/null || { echo "prérequis absent : $c" >&2; exit "${EX_PREREQUIS}"; }
    done
    docker compose version >/dev/null || { echo "prérequis absent : docker compose" >&2; exit "${EX_PREREQUIS}"; }
}

liste() { # $1 = commande docker de liste ; sortie = noms, une par ligne ; un échec arrête tout
    local s
    s="$("$@")" || { echo "ERREUR : '$*' a échoué, état non établi" >&2; exit "${EX_PREREQUIS}"; }
    printf '%s\n' "${s}"
}

monter() {
    prerequis
    [[ "${DEMO_PORT}" =~ ^[0-9]+$ ]] || { echo "DEMO_PORT : entier attendu" >&2; exit "${EX_USAGE}"; }
    # 1. Refus avant toute écriture.
    local mem_mio
    case "${DEMO_BLAZE_MEMOIRE}" in
        *[!0-9mg]*|"") echo "DEMO_BLAZE_MEMOIRE : entier suivi de m ou g attendu" >&2; exit "${EX_USAGE}" ;;
        *m) mem_mio="${DEMO_BLAZE_MEMOIRE%m}" ;;
        *g) mem_mio=$(( ${DEMO_BLAZE_MEMOIRE%g} * 1024 )) ;;
        *) echo "DEMO_BLAZE_MEMOIRE : entier suivi de m ou g attendu" >&2; exit "${EX_USAGE}" ;;
    esac
    [ "${DEMO_EXPORT_MIO}" -le "${mem_mio}" ] \
        || { echo "ERREUR : DEMO_EXPORT_MIO (${DEMO_EXPORT_MIO}) dépasse DEMO_BLAZE_MEMOIRE (${DEMO_BLAZE_MEMOIRE}) — refus, rien créé." >&2; exit "${EX_USAGE}"; }
    [ -f "${RACINE}/nginx/nginx.conf" ] \
        || { echo "ERREUR : nginx/nginx.conf absent (configuration montée dans nginx) — refus, rien créé." >&2; exit "${EX_PREREQUIS}"; }
    local ecoute; ecoute="$(liste ss -ltnH)"; ecoute="$(printf '%s\n' "${ecoute}" | awk '{print $4}')"
    if printf '%s\n' "${ecoute}" | /usr/bin/grep -qE ":${DEMO_PORT}\$"; then
        echo "ERREUR : le port ${DEMO_PORT} est déjà à l'écoute — refus, rien créé." >&2; exit "${EX_PRIS}"
    fi
    local noms o
    noms="$(liste docker ps -a --format '{{.Names}}')"
    for o in "${OBJETS_CONTENEURS[@]}"; do
        if printf '%s\n' "${noms}" | /usr/bin/grep -qFx "${o}"; then
            echo "ERREUR : le conteneur ${o} existe déjà (bash demo/demo.sh arreter) — refus." >&2; exit "${EX_PRIS}"
        fi
    done
    local reseaux volumes
    reseaux="$(liste docker network ls --format '{{.Name}}')"
    if printf '%s\n' "${reseaux}" | /usr/bin/grep -qFx "${OBJET_RESEAU}"; then
        echo "ERREUR : le réseau ${OBJET_RESEAU} existe déjà — refus." >&2; exit "${EX_PRIS}"
    fi
    volumes="$(liste docker volume ls --format '{{.Name}}')"
    if printf '%s\n' "${volumes}" | /usr/bin/grep -qFx "${OBJET_VOLUME}"; then
        echo "ERREUR : le volume ${OBJET_VOLUME} existe déjà — refus." >&2; exit "${EX_PRIS}"
    fi
    bash "${RACINE}/demo/charger-jeu.sh" --verifier
    # 2. Jar du module, puis démarrage.
    echo "== jar du module"
    bash "${RACINE}/build/construire-jar.sh"
    [ -f "${DEMO_JAR}" ] || { echo "ERREUR : jar ${DEMO_JAR} absent" >&2; exit "${EX_PREREQUIS}"; }
    echo "== démarrage"
    compose up -d
    local base="http://127.0.0.1:${DEMO_PORT}/fhir" debut="${SECONDS}" code=000
    while [ "${code}" != 200 ]; do
        if [ $((SECONDS - debut)) -ge "${DEMO_ATTENTE_SECONDES}" ]; then
            echo "ERREUR : ${base}/metadata ne répond pas 200 en ${DEMO_ATTENTE_SECONDES} s (dernier code ${code}) ; bash demo/demo.sh arreter" >&2
            exit "${EX_DEMARRAGE}"
        fi
        code="$(curl -s -o /dev/null -w '%{http_code}' "${base}/metadata" || true)"  # sondage : un échec de connexion = pas encore prêt, il est borné par le délai
        [ "${code}" = 200 ] || sleep 2
    done
    echo "== metadata 200 après $((SECONDS - debut)) s"
    echo "== chargement du jeu"
    bash "${RACINE}/demo/charger-jeu.sh" "${base}"
    echo "Prêt : ${base}  (arrêt : bash demo/demo.sh arreter)"
}

arreter() {
    prerequis
    compose down --volumes --timeout 30
}

parametres() {
    echo "# variables lues (compose.yaml, hors versions.env) et par demo.sh"
    { sed 's/\$\$//g' "${RACINE}/demo/compose.yaml" | /usr/bin/grep -oE '\$\{[A-Z][A-Z0-9_]*' | sed 's/^\${//'
      /usr/bin/grep -ohE '^: "\$\{[A-Z][A-Z0-9_]*' "${RACINE}/demo/demo.sh" | sed 's/^: "\${//'; } | LC_ALL=C sort -u
    echo "# ports publiés (compose.yaml)"
    /usr/bin/grep -E '^\s+- [0-9.]*:?\$\{DEMO_PORT\}:[0-9]+' "${RACINE}/demo/compose.yaml" | sed 's/^\s*- //'
}

case "${1:-}" in
    monter) monter ;;
    arreter) arreter ;;
    parametres) parametres ;;
    *) echo "usage : demo.sh monter|arreter|parametres" >&2; exit "${EX_USAGE}" ;;
esac
