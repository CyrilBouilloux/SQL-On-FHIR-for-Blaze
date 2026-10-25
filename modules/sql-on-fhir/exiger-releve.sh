#!/usr/bin/env bash
# exiger-releve.sh <releve> <nb-tests> : le releve est exactement « N tests, … 0 failures. »
# (aucun test en echec ni en erreur, nombre de tests egal a N). Sinon : message et code 1.
set -euo pipefail
releve=$1
attendu=$2
if [ "$(wc -l < "$releve")" -eq 1 ] && grep -Eq "^${attendu} tests, [0-9]+ assertions, 0 failures\.$" "$releve"; then
  echo "OK $releve : $(cat "$releve")"
else
  echo "ECHEC $releve : attendu '${attendu} tests, … 0 failures.', obtenu :" >&2
  cat "$releve" >&2
  exit 1
fi
