#!/usr/bin/env bash
#
# lib/codes.sh — table UNIQUE des codes de sortie (F-066, T-133, ADR 0028
# decision 1).
#
# Neuf codes, un seul sens chacun. C'est la SEULE table qui fasse foi : tout
# point d'entree du depot la SOURCE au lieu de declarer la sienne. L'aide de
# chaque commande, le manuel et les tests en DERIVENT — cabler cette derivation
# est le perimetre de l'ADR 0028 decision 5 (T-141), pas de ce fichier, qui ne
# fournit que les valeurs et leur libelle.
#
# Les codes `0` a `6` sont ceux qui existaient deja, un par un, dans les
# points d'entree du depot avant cette tache (dont l'ancien point d'entree
# jetable, supprime au palier P3, T-138) : leur VALEUR n'a pas change, seul
# l'endroit ou elle est ECRITE change. `7` et `8` sont
# nouveaux (ADR 0028) : refus de propriete, et ce que smoke.sh/test.sh
# rendaient sous le code `1` (collision desormais levee).
#
# Sourcee UNE FOIS par le point d'entree qui l'emploie, jamais executee seule.

readonly EX_SUCCES=0
readonly EX_AUTRE=1
readonly EX_USAGE=2
readonly EX_PREREQUIS=3
readonly EX_CONFIG=4
readonly EX_DEMARRAGE=5
readonly EX_PRIS=6
readonly EX_PROPRIETE=7
readonly EX_ECHEC=8

# Ordre d'enumeration stable — pour tout point d'entree qui veut lister les
# codes sans dependre de l'ordre d'iteration d'un tableau associatif.
readonly -a EX_CODES_ORDRE=(0 1 2 3 4 5 6 7 8)

# Libelle de chaque code, dans le meme ordre — UN seul endroit (ADR 0028,
# decision 1). Cle = code numerique en base 10, sans zero de tete.
declare -A EX_LIBELLES=(
    [0]="succes"
    [1]="erreur interne inattendue"
    [2]="usage"
    [3]="prerequis absent"
    [4]="configuration"
    [5]="demarrage ou disponibilite"
    [6]="conflit de nom ou de ressource"
    [7]="refus de propriete"
    [8]="au moins une verification en echec"
)
readonly EX_LIBELLES
