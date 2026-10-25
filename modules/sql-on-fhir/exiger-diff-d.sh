#!/usr/bin/env bash
# exiger-diff-d.sh <releve-jar> <releve-module> <A> (F-004 (d)) : ensemble en echec du module = ensemble du jar seul ∪ A,
# disjoint ; diff des releves limite a la synthese et a une ligne « FAIL in <test> » par test de A, aucune autre.
set -uo pipefail
jar=$1 mod=$2 a=$3
for f in "$jar" "$mod" "$a"; do [ -s "$f" ] || { echo "INDETERMINE : $f absent ou vide" >&2; exit 2; }; done
attendu=$(sed 's/^/> FAIL in /' "$a" | sort)
obtenu=$(diff <(tail -n +2 "$jar") <(tail -n +2 "$mod") | /usr/bin/grep -E '^[<>]' | sort || [ $? -eq 1 ])
if [ "$attendu" = "$obtenu" ]; then
  echo "OK (d) : synthese a part, le diff est exactement {$(tr '\n' ' ' < "$a")} : $(printf '%s' "$obtenu" | tr '\n' ';')"
else
  echo "ECHEC (d) : diff hors synthese attendu :" >&2; printf '%s\n' "$attendu" >&2
  echo "obtenu :" >&2; printf '%s\n' "$obtenu" >&2
  exit 1
fi
