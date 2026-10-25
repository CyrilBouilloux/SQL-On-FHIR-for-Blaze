#!/usr/bin/env bash
# controle-exclusion.sh <L> <sortie-complete-jar-seul> <sortie-b-module> [<I>] (F-004, « Indexation inchangee » (c))
#  1. chaque test de L, lance seul contre le jar seul, est en echec ou en erreur ;
#  2. les autres tests en echec du releve complet jar seul (victimes de cascade) sont listes ;
#     ils ne sont pas dans L (ni dans I, s'il est donne : instables, controles par controle-instables.sh) : leur nom figure dans la sortie de `test-amont-b` (0 echec exige
#     par exiger-releve.sh), donc ils y passent.
# Trois verdicts par test de L : EN-ECHEC (attendu), PASSE (L est trop large : ECHEC),
# INDETERMINE (pas de synthese, ou autre nombre de tests que 1 : ECHEC). Tous les tests sont
# controles avant de conclure ; code 1 si un seul n'est pas EN-ECHEC.
set -uo pipefail
liste=$1
complet=$2
sortie_b=$3
instables=${4:-/dev/null}
[ -s "$liste" ] || { echo "INDETERMINE : liste $liste absente ou vide" >&2; exit 2; }
[ -s "$sortie_b" ] || { echo "INDETERMINE : sortie $sortie_b absente (make test-amont-b d'abord)" >&2; exit 2; }
[ -r "$instables" ] || { echo "INDETERMINE : liste $instables illisible" >&2; exit 2; }
nb=$(/usr/bin/grep -cE '^[0-9]+ tests, ' "$complet" || true)
[ "$nb" = 1 ] || { echo "INDETERMINE : $complet a $nb lignes de synthese (1 attendue : releve incomplet ou melee)" >&2; exit 2; }
echo "releve complet jar seul : $complet ($(date -r "$complet" -Iseconds)), $(grep -E '^[0-9]+ tests, ' "$complet")"
echo "sortie de (b) : $sortie_b ($(date -r "$sortie_b" -Iseconds)), $(grep -E '^[0-9]+ tests, ' "$sortie_b" || echo 'SANS synthese')"
[ -s "$complet" ] || { echo "INDETERMINE : sortie $complet absente (make test-amont-jar d'abord)" >&2; exit 2; }
en_echec=0; ecarts=0; total=0
while IFS= read -r id; do
  total=$((total + 1))
  releve=$(make --no-print-directory test-amont-jar SORTIE=ctl AMONT_ARGS="--focus $id" 2>&1 | grep -E '^[0-9]+ tests, ')
  case $releve in
    "1 tests, "*" errors, "*|"1 tests, "*" error, "*) verdict=EN-ECHEC ;;
    "1 tests, "*" 0 failures.") verdict=PASSE ;;
    "1 tests, "*" failures.") verdict=EN-ECHEC ;;
    *) verdict=INDETERMINE ;;
  esac
  echo "$verdict $id ($releve)"
  if [ "$verdict" = EN-ECHEC ]; then en_echec=$((en_echec + 1)); else ecarts=$((ecarts + 1)); fi
done < "$liste"
echo "L : $en_echec sur $total en echec seuls sur le jar"
victimes=$(./echecs-amont.sh "$complet" | sort -u | grep -vxF -f "$liste" | grep -vxF -f "$instables" || [ $? -eq 1 ])
echo "tests en echec du releve complet hors L (a jouer verts dans test-amont-b) : $(printf '%s' "$victimes" | grep -c . || true)"
printf '%s\n' "$victimes"
absentes=0
while IFS= read -r v; do
  [ -n "$v" ] || continue
  grep -qxF "  ${v#*/}" "$sortie_b" || { echo "ABSENT de $sortie_b : $v" >&2; absentes=$((absentes + 1)); }
done <<< "$victimes"
[ "$absentes" -eq 0 ] || { echo "ECHEC : $absentes victime(s) non jouee(s) dans test-amont-b" >&2; exit 1; }
[ "$ecarts" -eq 0 ] || { echo "ECHEC : $ecarts test(s) de L non en echec seuls" >&2; exit 1; }
echo "SUCCES : $en_echec sur $total"
