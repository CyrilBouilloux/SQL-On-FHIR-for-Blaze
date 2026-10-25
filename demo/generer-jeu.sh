#!/usr/bin/env bash
#
# demo/generer-jeu.sh — produit le jeu de démonstration : 100 patients Synthea
# synthétiques, en Bundle JSON non compressés, sous demo/donnees/ (F-037, T-106).
#
#   bash demo/generer-jeu.sh
#
# Prérequis : java (21), curl, jq, sha256sum. Le jar de Synthea (SYNTHEA_VERSION) est
# téléchargé dans .work/demo-synthea/ (ignoré par git) puis contrôlé contre
# SYNTHEA_JAR_SHA256 de versions.env AVANT tout usage. Configuration : demo/synthea.properties.
# Tout est généré dans un répertoire temporaire ; demo/donnees/ n'est remplacé qu'après les
# contrôles (100 Patient, aucun type exclu, une ligne par fichier, poids ≤ PLAFOND_OCTETS) :
# un refus n'écrit rien dans le jeu. Rien n'est envoyé à un serveur.
# Sorties : 0 succès · 3 prérequis absent · 4 configuration · 8 constat (jar d'une autre
# empreinte, contrôle du jeu en défaut).
set -euo pipefail

RACINE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${RACINE}/lib/codes.sh"

# Paramètres du jeu (consignés dans provenance.txt).
POPULATION=100
GRAINE=1
GRAINE_PRATICIENS=2
DATE_REFERENCE=20250101
PLAFOND_OCTETS=20000000

CONF="${RACINE}/demo/synthea.properties"
SORTIE="${RACINE}/demo/donnees"
TRAVAIL="${RACINE}/.work/demo-synthea"
JAR="${TRAVAIL}/synthea.jar"

version() {
    local v
    v="$(sed -n "s/^${1}=\(.*\)$/\1/p" "${RACINE}/versions.env" | tail -1)"
    [ -n "$v" ] || { echo "versions.env ne porte pas $1" >&2; exit "${EX_CONFIG}"; }
    printf '%s' "$v"
}
SYNTHEA_VERSION="$(version SYNTHEA_VERSION)"
SYNTHEA_JAR_SHA256="$(version SYNTHEA_JAR_SHA256)"

for c in java curl jq sha256sum; do
    command -v "$c" >/dev/null || { echo "prérequis absent : $c" >&2; exit "${EX_PREREQUIS}"; }
done
[ -f "${CONF}" ] || { echo "ERREUR : ${CONF} introuvable" >&2; exit "${EX_CONFIG}"; }

mkdir -p "${TRAVAIL}"
if [ ! -f "${JAR}" ]; then
    curl -fsSL -o "${JAR}.part" \
        "https://github.com/synthetichealth/synthea/releases/download/${SYNTHEA_VERSION}/synthea-with-dependencies.jar"
    mv -- "${JAR}.part" "${JAR}"
fi
EMPREINTE_JAR="$(sha256sum -- "${JAR}" | cut -d' ' -f1)"
if [ "${EMPREINTE_JAR}" != "${SYNTHEA_JAR_SHA256}" ]; then
    echo "ERREUR : jar de Synthea d'empreinte ${EMPREINTE_JAR}, attendue ${SYNTHEA_JAR_SHA256} — refus, jeu intact." >&2
    exit "${EX_ECHEC}"
fi

TMP="$(mktemp -d "${TRAVAIL}/gen.XXXXXX")"
trap 'rm -rf -- "${TMP}"' EXIT

java -jar "${JAR}" -c "${CONF}" -p "${POPULATION}" -s "${GRAINE}" -cs "${GRAINE_PRATICIENS}" \
    -r "${DATE_REFERENCE}" --exporter.baseDirectory "${TMP}/out" >"${TMP}/synthea.log" 2>&1 \
    || { tail -20 "${TMP}/synthea.log" >&2; echo "ERREUR : Synthea a échoué." >&2; exit "${EX_ECHEC}"; }

# Noms déterministes : Synthea horodate ses bundles d'information.
for prefixe in hospitalInformation practitionerInformation; do
    trouves=("${TMP}/out/fhir/${prefixe}"*.json)
    if [ "${#trouves[@]}" -ne 1 ] || [ ! -e "${trouves[0]}" ]; then
        echo "ERREUR : ${#trouves[@]} bundle(s) ${prefixe}*.json au lieu d'un seul." >&2; exit "${EX_ECHEC}"
    fi
    mv -- "${trouves[0]}" "${TMP}/out/fhir/0000_${prefixe}.json"
done
# Synthea indente les bundles de patient malgré exporter.pretty_print = false (mesuré :
# 3 patients, option en fichier et en ligne de commande, fichiers de milliers de lignes) :
# seule la mise en ligne unique (`jq -c`, aucun changement de contenu) tient le critère.
JEU="${TMP}/compact"
mkdir "${JEU}"
for f in "${TMP}"/out/fhir/*.json; do
    # nom restreint à [A-Za-z0-9._-] : une apostrophe (O'Connor) casse `xargs` du critère d'empreinte
    nom="$(basename -- "$f" | tr -cd 'A-Za-z0-9._-')"
    [ ! -e "${JEU}/${nom}" ] || { echo "ERREUR : nom en double après assainissement : ${nom}" >&2; exit "${EX_ECHEC}"; }
    jq -c . "$f" >"${JEU}/${nom}" || { echo "ERREUR : JSON illisible : $f" >&2; exit "${EX_ECHEC}"; }
done

# Contrôles avant toute écriture dans demo/donnees/.
types="$(cat "${JEU}"/*.json | jq -r '.entry[]?.resource.resourceType')"
nb_patient="$(printf '%s\n' "${types}" | /usr/bin/grep -cx Patient || true)"
nb_exclus="$(printf '%s\n' "${types}" | /usr/bin/grep -cE '^(ExplanationOfBenefit|Claim|Provenance)$' || true)"
nb_multi="$(for f in "${JEU}"/*.json; do wc -l <"$f"; done | awk '$1>1' | wc -l)"
nb_non_bundle="$(for f in "${JEU}"/*.json; do jq -e '.resourceType=="Bundle"' "$f" >/dev/null || echo x; done | wc -l)"
poids="$(du -cb "${JEU}" | tail -1 | cut -f1)"
echo "contrôles : Patient=${nb_patient} exclus=${nb_exclus} multi-lignes=${nb_multi} non-Bundle=${nb_non_bundle} poids=${poids}"
[ "${nb_patient}" -eq "${POPULATION}" ] && [ "${nb_exclus}" -eq 0 ] && [ "${nb_multi}" -eq 0 ] \
    && [ "${nb_non_bundle}" -eq 0 ] && [ "${poids}" -le "${PLAFOND_OCTETS}" ] \
    || { echo "ERREUR : jeu hors des contrôles ci-dessus (plafond ${PLAFOND_OCTETS}) — demo/donnees/ intact." >&2; exit "${EX_ECHEC}"; }

mkdir -p "${SORTIE}"
/usr/bin/find "${SORTIE}" -maxdepth 1 -type f \( -name '*.json' -o -name provenance.txt \) -delete
mv -- "${JEU}"/*.json "${SORTIE}/"
cd "${RACINE}"
EMPREINTE_JEU="$(/usr/bin/find demo/donnees -type f -name '*.json' | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -d' ' -f1)"
cat >"${SORTIE}/provenance.txt" <<PROV
empreinte_du_jeu=${EMPREINTE_JEU}
  (/usr/bin/find demo/donnees -type f -name '*.json' | LC_ALL=C sort | xargs sha256sum | sha256sum ; commande jouée depuis la racine du dépôt : les chemins en font partie)
SYNTHEA_VERSION=${SYNTHEA_VERSION}
SYNTHEA_JAR_SHA256=${SYNTHEA_JAR_SHA256}
configuration=demo/synthea.properties
configuration_sha256=$(sha256sum -- "${CONF}" | cut -d' ' -f1)
population=${POPULATION}
graine=${GRAINE}
graine_praticiens=${GRAINE_PRATICIENS}
date_de_reference=${DATE_REFERENCE}
PROV
echo "jeu écrit : ${SORTIE} (${poids} octets, empreinte ${EMPREINTE_JEU})"
