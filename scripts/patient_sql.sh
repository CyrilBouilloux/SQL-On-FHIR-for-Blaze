#!/usr/bin/env bash
# patient_sql.sh <nom-de-famille (exact, sensible à la casse)|id:<id>> — cherche un patient par une ViewDefinition SQL on FHIR
# ($viewdefinition-run, F-006). Base : FHIR_BASE_URL, sinon http://localhost:${BANC_PORT:-8088}/fhir.
set -euo pipefail

[ $# -eq 1 ] || { echo "usage : $0 <nom-de-famille|id:<id>>" >&2; exit 2; }
base="${FHIR_BASE_URL:-http://localhost:${BANC_PORT:-8088}/fhir}"
base="${base%/}"

case "$1" in
  id:*) filtre="id = '${1#id:}'" ;;
  *)    filtre="name.family = '$1'" ;;
esac
# une quote dans la saisie casserait l'expression FHIRPath : refus plutôt que d'échapper
case "$1" in *\'*) echo "saisie invalide : quote interdite" >&2; exit 2 ;; esac

corps="$(jq -n --arg f "$filtre" '{resourceType:"Parameters",parameter:[{name:"viewResource",resource:{
  resourceType:"ViewDefinition",name:"recherche_patient",resource:"Patient",status:"active",
  select:[{column:[{name:"id",path:"id"},{name:"nom",path:"name.first().family"},
                   {name:"prenom",path:"name.first().given.first()"},
                   {name:"genre",path:"gender"},{name:"naissance",path:"birthDate"}]}],
  where:[{path:$f}]}}]}')"

curl -sS --fail-with-body -X POST "${base}/\$viewdefinition-run?_format=csv" \
     -H 'Content-Type: application/fhir+json' -d "$corps"
