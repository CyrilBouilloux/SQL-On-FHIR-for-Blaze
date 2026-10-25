#!/usr/bin/env bash
# controle-instables.sh <I> <L> <dossier-tests-amont> <source-macro> <sortie-echec> <sortie-succes>
# F-004 « Indexation inchangee » (c) : controle de la liste I des tests instables.
#  - I ∩ L vide ;
#  - preuve 1, par test de I : son `deftest` au tag (dossier-tests-amont) appelle un generateur aleatoire
#    sans graine (`satisfies-prop` / `quick-check` / `gen/generate` / `gen/sample`, aucun `:seed`) ;
#    pour `satisfies-prop`, la macro (source-macro) appelle `quick-check` sans `:seed` ; citation litterale imprimee ;
#  - preuve 2, par test de I : sortie-echec (jar seul, `*-jar.out`) porte >= 1 echec du test, sortie-succes
#    (jar seul) le montre joue et sans echec.
# Trois reponses : SUCCES (code 0) ; ECHEC (preuve fausse, code 1) ; INDETERMINE (preuve non etablissable :
# fichier absent, source introuvable, sortie sans synthese, code 2). Jamais SUCCES si une preuve manque.
set -uo pipefail
liste_i=$1 liste_l=$2 tests=$3 macro=$4 sortie_echec=$5 sortie_succes=$6
ecarts=0 indeterm=0
ko() { echo "ECHEC $1 : $2" >&2; ecarts=$((ecarts + 1)); }
ind() { echo "INDETERMINE $1 : $2" >&2; indeterm=$((indeterm + 1)); }
[ -s "$liste_i" ] || { echo "INDETERMINE : liste $liste_i absente ou vide" >&2; exit 2; }
[ -f "$liste_l" ] || { echo "INDETERMINE : liste $liste_l absente" >&2; exit 2; }
inter=$(grep -xF -f "$liste_i" "$liste_l" || [ $? -eq 1 ])
[ -z "$inter" ] || { echo "ECHEC : I ∩ L non vide : $inter" >&2; exit 1; }
echo "I ∩ L : vide"
# sortie jar seul avec synthese : nom `*-jar.out`, ligne « N tests, »
sortie_ok() { # <fichier> <id> ; rend 0 si exploitable
  case $(basename "$1") in *-jar.out) ;; *) ind "$2" "$1 n'est pas une sortie du jar seul (*-jar.out)"; return 1 ;; esac
  [ -s "$1" ] || { ind "$2" "$1 absente ou vide (la regenerer : make test-amont-instables)"; return 1; }
  grep -Eq '^[0-9]+ tests, ' "$1" || { ind "$2" "$1 sans ligne de synthese"; return 1; }
}
while IFS= read -r id; do
  [ -n "$id" ] || continue
  ns=${id%/*}; var=${id#*/}
  src="$tests/$(printf '%s' "$ns" | tr '.-' '/_').clj"
  # preuve 1 : source du deftest
  if [ ! -f "$src" ]; then ind "$id" "preuve 1 : source $src introuvable"
  else
    corps=$(awk -v v="$var" '
      /^\(deftest/ { dedans = 0; n = split($0, m, /[ \t]+/); for (k = 2; k <= n; k++) if (m[k] !~ /^\^/) { if (m[k] == v) dedans = 1; break } }
      /^\(/ && !/^\(deftest/ { dedans = 0 }
      dedans { print NR ": " $0 }' "$src")
    if [ -z "$corps" ]; then ind "$id" "preuve 1 : deftest $var introuvable dans $src"
    elif printf '%s\n' "$corps" | grep -q ':seed'; then ko "$id" "preuve 1 : le deftest fixe une graine (:seed)"
    else
      gen=$(printf '%s\n' "$corps" | grep -E 'satisfies-prop|quick-check|gen/generate|gen/sample' || [ $? -eq 1 ])
      if [ -z "$gen" ]; then ko "$id" "preuve 1 manquante : le deftest $var de $src n'appelle aucun generateur aleatoire"
      else
        echo "preuve 1 $id ($src) :"; printf '%s\n' "$gen" | sed 's/^/  /'
        if printf '%s\n' "$gen" | grep -q 'satisfies-prop'; then
          if [ ! -f "$macro" ]; then ind "$id" "preuve 1 : source de la macro $macro introuvable"
          else
            defm=$(awk '/^\(defmacro satisfies-prop/ { d = 1 } d { print NR ": " $0 } d && /^$/ { exit }' "$macro")
            if [ -z "$defm" ]; then ind "$id" "preuve 1 : defmacro satisfies-prop introuvable dans $macro"
            elif printf '%s\n' "$defm" | grep -q ':seed'; then ko "$id" "preuve 1 : la macro satisfies-prop fixe une graine"
            elif ! printf '%s\n' "$defm" | grep -q 'quick-check'; then ko "$id" "preuve 1 manquante : la macro satisfies-prop n'appelle pas quick-check"
            else printf '%s\n' "$defm" | grep 'quick-check' | sed "s|^|  macro ($macro) |"; fi
          fi
        fi
      fi
    fi
  fi
  # preuve 2 : au moins 1 echec et 1 succes sur le jar seul
  if sortie_ok "$sortie_echec" "$id"; then
    n=$(./echecs-amont.sh "$sortie_echec" | grep -cxF "$id" || [ $? -eq 1 ])
    if [ "$n" -ge 1 ]; then echo "preuve 2 echec $id : $n ligne(s) FAIL|ERROR dans $sortie_echec"
    else ko "$id" "preuve 2 manquante : aucun echec de $id dans $sortie_echec"; fi
  fi
  if sortie_ok "$sortie_succes" "$id"; then
    n=$(./echecs-amont.sh "$sortie_succes" | grep -cxF "$id" || [ $? -eq 1 ])
    joue=$(grep -cE "^  ${var}\$" "$sortie_succes" || [ $? -eq 1 ])
    if [ "$n" -eq 0 ] && [ "$joue" -ge 1 ]; then echo "preuve 2 succes $id : joue, 0 ligne FAIL|ERROR dans $sortie_succes"
    elif [ "$joue" -eq 0 ] && [ "$n" -eq 0 ]; then ind "$id" "preuve 2 : $id non joue dans $sortie_succes (ni succes ni echec)"
    else ko "$id" "preuve 2 manquante : aucun succes de $id dans $sortie_succes ($n echec(s))"; fi
  fi
done < "$liste_i"
[ "$ecarts" -eq 0 ] || { echo "ECHEC : $ecarts preuve(s) fausse(s) pour I" >&2; exit 1; }
[ "$indeterm" -eq 0 ] || { echo "INDETERMINE : $indeterm preuve(s) non etablie(s) pour I" >&2; exit 2; }
echo "SUCCES : I, 2 preuves sur 2 pour chaque test"
