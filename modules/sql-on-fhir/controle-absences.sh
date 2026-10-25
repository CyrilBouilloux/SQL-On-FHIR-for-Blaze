#!/usr/bin/env bash
# controle-absences.sh <A> <L> <I> <preuves> <tests-amont> <tests-module> <attendus.edn>
# F-004 « Indexation inchangee » (c) : controle de la liste A des tests dont l'attendu est l'absence d'une construction
# que le module ajoute. A ∩ (L ∪ I) vide ; 3 preuves REJOUEES par test de A (voir en-tete de <preuves>) ; tous les tests
# sont controles avant de conclure. Trois reponses : SUCCES 0 ; ECHEC 1 (preuve fausse) ; INDETERMINE 2 (non etablissable).
set -uo pipefail
liste_a=$1 liste_l=$2 liste_i=$3 preuves=$4 tests=$5 tmod=$6 attendus=$7
ecarts=0 indeterm=0
ko() { echo "ECHEC $1 : $2" >&2; ecarts=$((ecarts + 1)); }
ind() { echo "INDETERMINE $1 : $2" >&2; indeterm=$((indeterm + 1)); }
for f in "$liste_a" "$liste_l" "$liste_i" "$preuves" "$attendus"; do
  [ -s "$f" ] || { echo "INDETERMINE : $f absent ou vide" >&2; exit 2; }
done
inter=$(/usr/bin/grep -xF -f "$liste_a" "$liste_l" "$liste_i" || [ $? -eq 1 ])
[ -z "$inter" ] || { echo "ECHEC : A ∩ (L ∪ I) non vide : $inter" >&2; exit 1; }
echo "A ∩ (L ∪ I) : vide"
champ() { # <id> <cle> : valeur du champ dans le bloc de <id>
  awk -v id="$1" -v k="$2" '/^test: /{dedans = ($0 == "test: " id)} dedans && index($0, k ": ") == 1 { print substr($0, length(k) + 3); exit }' "$preuves"
}
src_de() { printf '%s/%s.clj' "$1" "$(printf '%s' "${2%/*}" | tr '.-' '/_')"; }
sortie=${SORTIE:-controle}
compiler() { # <expression> <avec-module:0|1> <id du test> : « RESULT ANOMALIE <message> », « RESULT COMPILE <type> » ou « ERREUR <fichier d'erreur> »
  local req="" err sortie_c
  local base=${3//[^A-Za-z0-9-]/_}
  err="target/$sortie-compiler-$base-$2.err"; sortie_c="target/$sortie-compiler-$base-$2.out"
  [ "$2" = 1 ] && req="(require 'blaze.addon.sql-on-fhir.fhir-path.operator)"
  mkdir -p target
  if clojure -M:test -e "(require 'blaze.fhir-path) $req (let [r (blaze.fhir-path/compile \"$1\")] (println \"RESULT\" (if (:cognitect.anomalies/category r) (str \"ANOMALIE \" (:cognitect.anomalies/message r)) (str \"COMPILE \" (type r)))))" > "$sortie_c" 2> "$err" \
     && /usr/bin/grep -m1 '^RESULT ' "$sortie_c"; then :; else echo "ERREUR $err"; fi
}
while IFS= read -r id; do
  [ -n "$id" ] || continue
  var=${id#*/}
  expr=$(champ "$id" expression); msg=$(champ "$id" message-jar); cons=$(champ "$id" construction)
  cas=$(champ "$id" cas); tm=$(champ "$id" test-module)
  if [ -z "$expr" ] || [ -z "$msg" ] || [ -z "$cons" ] || [ -z "$cas" ] || [ -z "$tm" ]; then ind "$id" "bloc de $preuves incomplet"; continue; fi
  # preuve 1 : seul, passe sur le jar seul, echoue avec le module
  rj=$(make --no-print-directory test-amont-jar SORTIE=ctla AMONT_ARGS="--focus $id" 2>&1 | /usr/bin/grep -E '^[0-9]+ tests, ')
  rm=$(make --no-print-directory test-amont-module SORTIE=ctla AMONT_ARGS="--focus $id" 2>&1 | /usr/bin/grep -E '^[0-9]+ tests, ')
  echo "preuve 1 $id : jar seul « $rj » ; jar + module « $rm »"
  case $rj in "1 tests, "*" 0 failures.") ;; "") ind "$id" "preuve 1 : jar seul sans synthese" ;; *) ko "$id" "preuve 1 : le test ne passe pas seul sur le jar seul ($rj)" ;; esac
  case $rm in "1 tests, "*" 0 failures.") ko "$id" "preuve 1 : le test passe avec le module (la construction n'est plus ajoutee ?)" ;; "") ind "$id" "preuve 1 : module sans synthese" ;; esac
  # preuve 2 : expression citee du source au tag, anomalie jar seul, compilation module charge
  src=$(src_de "$tests" "$id")
  if [ ! -f "$src" ]; then ind "$id" "preuve 2 : source $src introuvable"
  elif ! /usr/bin/grep -qF ":expression \"$expr\"" "$src"; then ko "$id" "preuve 2 : :expression \"$expr\" absente du source $src"
  else
    echo "preuve 2 source $src : $(/usr/bin/grep -nF ":expression \"$expr\"" "$src" | head -1 | sed 's/  */ /g')"
    cj=$(compiler "$expr" 0 "$id"); cm=$(compiler "$expr" 1 "$id")
    echo "preuve 2 jar seul : $cj ; jar + module : $cm"
    case $cj in "RESULT ANOMALIE "*"$msg"*) ;; "ERREUR "*) ind "$id" "preuve 2 : jar seul, compilation en echec, erreur dans ${cj#ERREUR }" ;; *) ko "$id" "preuve 2 : jar seul ne rend pas l'anomalie « $msg » ($cj)" ;; esac
    case $cm in "RESULT COMPILE "*) ;; "ERREUR "*) ind "$id" "preuve 2 : module, compilation en echec, erreur dans ${cm#ERREUR }" ;; *) ko "$id" "preuve 2 : module charge ne compile pas « $expr » ($cm)" ;; esac
  fi
  # preuve 3 : cas de attendus.edn contenant la construction + test nomme du module
  ligne=$(/usr/bin/grep -F "{:id $cas " "$attendus" | head -1)
  if [ -z "$ligne" ]; then ko "$id" "preuve 3 : cas $cas absent de $attendus"
  elif ! printf '%s' "$ligne" | /usr/bin/grep -qF " $cons "; then ko "$id" "preuve 3 : le cas $cas ne porte pas la construction « $cons »"
  else echo "preuve 3 cas : $(printf '%s' "$ligne" | sed 's/^ *//' | cut -c1-90)"; fi
  tsrc=$(src_de "$tmod" "$tm")
  if [ ! -f "$tsrc" ]; then ind "$id" "preuve 3 : source $tsrc introuvable"
  elif /usr/bin/grep -qE "^\(deftest ${tm#*/}\$" "$tsrc"; then echo "preuve 3 test du module : $tm ($tsrc)"
  else ko "$id" "preuve 3 : deftest ${tm#*/} absent de $tsrc"; fi
done < "$liste_a"
[ "$ecarts" -eq 0 ] || { echo "ECHEC : $ecarts preuve(s) fausse(s) pour A" >&2; exit 1; }
[ "$indeterm" -eq 0 ] || { echo "INDETERMINE : $indeterm preuve(s) non etablie(s) pour A" >&2; exit 2; }
echo "SUCCES : A, 3 preuves sur 3 pour chaque test"
