#!/usr/bin/env bash
# rejouer-instables.sh <I> <N> <sortie-echec> <sortie-succes> : F-004 « Indexation inchangee » (c), preuve 2 de I.
# Depuis un clone neuf (aucune sortie requise), rejoue chaque test de I SEUL sur le jar seul
# (`make test-amont-jar AMONT_ARGS="--focus <test>"`) au plus N fois, jusqu'a observer 1 echec et 1 succes.
# Les deux sorties designees (*-jar.out) ne sont ecrites qu'une fois les deux observations faites (un refus n'ecrit rien).
# Trois reponses : SUCCES (0) ; ECHEC (1) : un essai en echec qui n'attribue aucun echec au test (autre test, ou
# execution sans synthese avec une synthese presente en echec) ; INDETERMINE (2) : N atteint sans les deux
# observations, ou N invalide, ou essai inexploitable (sans synthese). Jamais SUCCES par defaut.
set -uo pipefail
liste=$1 n=$2 sortie_echec=$3 sortie_succes=$4
[[ "$n" =~ ^[0-9]+$ ]] || { echo "INDETERMINE : N='$n' n'est pas un entier" >&2; exit 2; }
[ -s "$liste" ] || { echo "INDETERMINE : liste $liste absente ou vide" >&2; exit 2; }
essais=target/instables-essais
rm -rf "$essais"; mkdir -p "$essais"
statut=0
while IFS= read -r id; do
  [ -n "$id" ] || continue
  var=${id#*/}; trouve_echec="" trouve_succes="" inexploitables=0 i=0
  while [ "$i" -lt "$n" ] && { [ -z "$trouve_echec" ] || [ -z "$trouve_succes" ]; }; do
    i=$((i + 1)); pre=instables-$i
    make --no-print-directory test-amont-jar SORTIE="$pre" AMONT_ARGS="--focus $id" > "$essais/$pre.make" 2>&1
    sortie=target/$pre-jar.out
    if [ ! -s "$sortie" ] || [ "$(grep -cE '^[0-9]+ tests, ' "$sortie")" != 1 ]; then
      inexploitables=$((inexploitables + 1)); echo "essai $i/$n $id : inexploitable (sans exactement une synthese, voir $sortie)" >&2; continue
    fi
    nb=$(./echecs-amont.sh "$sortie" | grep -cxF "$id" || [ $? -eq 1 ])
    synth=$(grep -E '^[0-9]+ tests, ' "$sortie")
    joue=$(grep -cE "^  ${var}\$" "$sortie" || [ $? -eq 1 ])
    if [ "$nb" -ge 1 ]; then
      echo "essai $i/$n $id : ECHEC observe ($synth)"; [ -n "$trouve_echec" ] || { trouve_echec=$sortie; cp "$sortie" "$essais/echec-jar.out"; }
    elif ! grep -qE ' 0 failures\.$' <<< "$synth" || grep -qE ', [1-9][0-9]* errors?, ' <<< "$synth"; then
      echo "ECHEC $id : essai $i en echec sans echec attribue a $id ($synth), voir $sortie" >&2; exit 1
    elif [ "$joue" -ge 1 ]; then
      echo "essai $i/$n $id : succes observe ($synth)"; [ -n "$trouve_succes" ] || { trouve_succes=$sortie; cp "$sortie" "$essais/succes-jar.out"; }
    else
      inexploitables=$((inexploitables + 1)); echo "essai $i/$n $id : $id non joue ($synth)" >&2
    fi
  done
  if [ -z "$trouve_echec" ] || [ -z "$trouve_succes" ]; then
    echo "INDETERMINE $id : apres $i essai(s) sur N=$n ($inexploitables inexploitable(s)) : echec $([ -n "$trouve_echec" ] && echo observe || echo NON observe), succes $([ -n "$trouve_succes" ] && echo observe || echo NON observe) ; aucune sortie ecrite" >&2
    statut=2; continue
  fi
  cp "$essais/echec-jar.out" "$sortie_echec"; cp "$essais/succes-jar.out" "$sortie_succes"
  echo "SUCCES $id : 1 echec et 1 succes en $i essai(s) sur N=$n ; $sortie_echec, $sortie_succes ecrites"
done < "$liste"
exit "$statut"
