#!/usr/bin/env bash
# exiger-tests.sh <releve> <nb-tests> : la premiere ligne du releve est « N tests, … » avec N = nb-tests
# (les echecs ne sont pas juges ici : (d) les compare par diff). Sinon : message et code 1.
set -euo pipefail
releve=$1
attendu=$2
if head -n1 "$releve" | grep -Eq "^${attendu} tests, [0-9]+ assertions, "; then
  echo "OK $releve : $(head -n1 "$releve")"
else
  echo "ECHEC $releve : attendu '${attendu} tests, …', obtenu : $(head -n1 "$releve")" >&2
  exit 1
fi
