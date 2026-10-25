#!/usr/bin/env bash
#
# demo/charger-jeu.sh — charge le jeu de démonstration (demo/donnees/) dans un serveur FHIR (F-037).
#
#   bash demo/charger-jeu.sh <url de base, ex. http://127.0.0.1:18090/fhir>
#   bash demo/charger-jeu.sh --verifier        # contrôle l'empreinte, n'écrit rien nulle part
#
# L'empreinte du jeu est recalculée et comparée à celle de demo/donnees/provenance.txt AVANT
# toute écriture sur le serveur : un fichier altéré, ajouté ou retiré -> refus, code 8, rien chargé.
# Les bundles sont envoyés dans l'ordre du nom (0000_* d'abord : organisations, praticiens).
# Sorties : 0 succès · 2 usage · 3 prérequis absent · 8 constat (empreinte, réponse du serveur).
set -euo pipefail

RACINE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${RACINE}/lib/codes.sh"

for c in curl find sort xargs sha256sum sed; do
    command -v "$c" >/dev/null || { echo "prérequis absent : $c" >&2; exit "${EX_PREREQUIS}"; }
done
[ $# -eq 1 ] || { echo "usage : charger-jeu.sh <url de base>|--verifier" >&2; exit "${EX_USAGE}"; }

JEU="demo/donnees"
PROVENANCE="${RACINE}/${JEU}/provenance.txt"
[ -f "${PROVENANCE}" ] || { echo "ERREUR : ${JEU}/provenance.txt introuvable" >&2; exit "${EX_ECHEC}"; }
ATTENDUE="$(sed -n 's/^empreinte_du_jeu=\([0-9a-f]\{64\}\)$/\1/p' "${PROVENANCE}")"
[ -n "${ATTENDUE}" ] || { echo "ERREUR : empreinte_du_jeu absente de la provenance" >&2; exit "${EX_ECHEC}"; }

# Commande de la provenance, jouée depuis la racine (les chemins en font partie).
cd -- "${RACINE}"
CALCULEE="$(find "${JEU}" -type f -name '*.json' | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -d' ' -f1)"
if [ "${CALCULEE}" != "${ATTENDUE}" ]; then
    echo "ERREUR : empreinte du jeu ${CALCULEE}, consignée ${ATTENDUE} — refus, rien chargé." >&2
    exit "${EX_ECHEC}"
fi
[ "$1" = "--verifier" ] && { echo "jeu conforme à sa provenance : ${CALCULEE}"; exit "${EX_SUCCES}"; }

BASE="${1%/}"
n=0
for f in $(find "${JEU}" -type f -name '*.json' | LC_ALL=C sort); do
    code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/fhir+json' \
        --data-binary "@${f}" "${BASE}")" \
        || { echo "ERREUR : envoi de ${f} impossible" >&2; exit "${EX_ECHEC}"; }
    case "${code}" in
        200|201) n=$((n + 1)) ;;
        *) echo "ERREUR : ${f} -> HTTP ${code}" >&2; exit "${EX_ECHEC}" ;;
    esac
done
echo "${n} bundles chargés dans ${BASE}"
