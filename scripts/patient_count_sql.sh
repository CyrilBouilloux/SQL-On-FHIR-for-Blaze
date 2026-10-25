#!/usr/bin/env bash
# patient_count_sql.sh — compte les Patient en base par une ViewDefinition SQL on FHIR
# ($viewdefinition-run, F-006) : une ligne par patient, le script compte les lignes.
# Base : FHIR_BASE_URL, sinon http://localhost:${BANC_PORT:-8088}/fhir.
set -euo pipefail

[ $# -eq 0 ] || { echo "usage : $0" >&2; exit 2; }
base="${FHIR_BASE_URL:-http://localhost:${BANC_PORT:-8088}/fhir}"
base="${base%/}"

corps='{"resourceType":"Parameters","parameter":[{"name":"viewResource","resource":{
  "resourceType":"ViewDefinition","name":"compte_patient","resource":"Patient","status":"active",
  "select":[{"column":[{"name":"id","path":"id"}]}]}}]}'

csv="$(curl -sS --fail-with-body -X POST "${base}/\$viewdefinition-run?_format=csv" \
       -H 'Content-Type: application/fhir+json' -d "$corps")"
# première ligne = en-tête ; sortie vide = réponse inattendue, pas zéro patient
[ -n "$csv" ] || { echo "réponse vide : comptage non établi" >&2; exit 1; }
echo $(( $(printf '%s\n' "$csv" | wc -l) - 1 ))
