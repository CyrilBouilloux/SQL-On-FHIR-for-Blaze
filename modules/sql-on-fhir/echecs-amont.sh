#!/usr/bin/env bash
# echecs-amont.sh <sortie kaocha> : un identifiant `ns/var` par assertion en echec (FAIL/ERROR).
# Le journal d'un autre fil peut s'intercaler dans la ligne « ERROR in … » : l'identifiant est
# alors seul sur la ligne suivante, parfois precede d'une espace (`ns-test/var (fichier.clj:n)`). Les deux formes sont lues.
set -euo pipefail
sed -nE 's/^[[:space:]]*((FAIL|ERROR) in )?([A-Za-z0-9_.-]+-test\/[^ ]+) \([^ ]+\.clj:[0-9]+\)$/\3/p' "$1"
