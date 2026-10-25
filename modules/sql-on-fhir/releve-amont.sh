#!/usr/bin/env bash
# Releve d'une execution kaocha des suites amont : ligne de synthese, puis tests en echec.
# Exactement UNE ligne de synthese : zero (JVM tombee, config cassee) ou plusieurs (sortie melee : deux
# executions ont ecrit le meme fichier) = refus, jamais un releve ; aucun dedoublonnage de la synthese.
# Tests en echec dedoublonnes (le journal d'un autre fil s'intercale dans les lignes d'echec : le nombre de lignes par assertion n'est pas stable, la synthese porte les comptes).
set -euo pipefail
sortie=$1
[ -s "$sortie" ] || { echo "INDETERMINE $sortie : absente ou vide" >&2; exit 2; }
nb=$(/usr/bin/grep -cE '^[0-9]+ tests, ' "$sortie" || true)
if [ "$nb" != 1 ]; then
  echo "ECHEC $sortie : $nb lignes de synthese (1 attendue) : sortie vide ou melee, aucun releve" >&2
  exit 1
fi
/usr/bin/grep -E '^[0-9]+ tests, ' "$sortie"
./echecs-amont.sh "$sortie" | sed "s/^/FAIL in /" | sort -u
