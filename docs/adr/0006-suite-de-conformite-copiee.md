# ADR 0006 — Suite de conformité SQL on FHIR : copie épinglée dans le dépôt (`SPK-01`)

- **Statut** : acceptée — `SPK-01` décidé par l'humain le 2026-10-10
- **Date** : 2026-10-10
- **Élément de backlog** : F-005
- **Décideur** : l'humain (décision) ; architect (rédaction)

## Contexte

F-005 « Conformité » juge le moteur sur `S` = `tests/` de `https://github.com/FHIR/sql-on-fhir.js`
au commit `0821b673` (22 fichiers, 144 cas), et laisse ouvert le mode d'inclusion. La licence au
commit est ambiguë — relevé du 2026-10-10 sur un clone neuf, `git checkout 0821b673` :

- `git rev-parse HEAD` → `0821b673346bb859658dfc65f6b4808c23e028db` (2026-09-22, John Grimes) ;
- `head -1 LICENSE.md` → « Permission is hereby granted, free of charge, to any person obtaining »
  (texte MIT) ; `/usr/bin/grep -nwc "MIT\|Copyright" LICENSE.md` → `0`, code 1 : aucune ligne de
  copyright, aucun titulaire nommé ;
- `jq -r .license package.json` → `Apache-2.0` ; `jq -r .license sof-js/package.json` → `ISC` ;
- `README.md` l. 32 : « License: MIT » ; `tests/` est à la racine, sous `LICENSE.md` et `package.json`.

## Décision

1. `S` est **copié** dans le dépôt, sans modification, à
   `modules/sql-on-fhir/conformance/sql-on-fhir-js/tests/` (22 fichiers), épinglé au commit
   `0821b673346bb859658dfc65f6b4808c23e028db`.
2. `LICENSE.md` d'origine est recopié tel quel à côté (`conformance/sql-on-fhir-js/LICENSE.md`),
   avec un avis de provenance et d'attribution (`conformance/sql-on-fhir-js/PROVENANCE.md`).
3. Licence retenue : celle de `LICENSE.md` (MIT), seul texte de licence du dépôt d'origine.
4. Toute mise à jour de la suite est une nouvelle copie complète à un autre commit, par amendement
   de cette ADR ; aucun fichier de `tests/` ne se modifie à la main.

Identité de la copie, mesurée le 2026-10-10 : arbre attendu `git -C <clone> rev-parse 0821b673:tests`
→ `e627d7a9a391ec083fea9ab2f40809877c11ee57` ; arbre obtenu, avant commit,
`cd modules/sql-on-fhir/conformance/sql-on-fhir-js/tests && for f in *; do printf "100644 blob %s\t%s\n" "$(git hash-object "$f")" "$f"; done | git mktree --missing`
→ `e627d7a9a391ec083fea9ab2f40809877c11ee57` ; après commit,
`git rev-parse HEAD:modules/sql-on-fhir/conformance/sql-on-fhir-js/tests` → même valeur attendue
(*à mesurer* au commit). Témoin rouge : copie jetable, premier `"title"` de `basic.json` changé en
`"titlE"`, même commande → `b642caef4b9302507d3e539b06753836154c8349`. `LICENSE.md` :
blob `c9b44cb8aaebb163e8524d67558696aa965ad0ec` des deux côtés. Compte :
`jq '.tests|length' modules/sql-on-fhir/conformance/sql-on-fhir-js/tests/*.json | paste -sd+ | bc`
→ `144`.

## Conséquences

- **Risque résiduel de licence, accepté par l'humain** : trois déclarations discordantes (MIT,
  Apache-2.0, ISC) et un texte MIT sans titulaire. Que la redistribution sous MIT suffise est
  *décidé, non mesuré* ; aucun avis juridique n'a été pris. Si l'amont clarifie sa licence, la
  copie se met en conformité par amendement.
- `S` ne dépend plus du réseau ni de la disponibilité de l'amont ; le dépôt porte 22 fichiers
  tiers (`cat tests/*.json | wc -c` → `173015` octets) qui ne suivent pas l'amont — décalage à surveiller à chaque changement de
  version visée de l'IG (ADR 0005).
- `conformance/` est dans les `:extra-paths` de l'alias `:test` (`modules/sql-on-fhir/deps.edn`) :
  les fichiers y deviennent des ressources de classe sous `sql-on-fhir-js/` ; le jar n'a aucune
  entrée de ce préfixe (`unzip -l .work/blaze-standalone.jar | awk '{print $4}' | /usr/bin/grep -c "^sql-on-fhir-js/"`
  → `0`, 2026-10-10) ; `python3 scripts/check_module.py` → `SUCCES` M1–M6, 2026-10-10.
- Débloque T-020 (banc de conformité) et ses suivantes.

## Alternatives écartées

- **Sous-module git épinglé** : licence identique, mais un clone sans `--recurse-submodules` rend
  `S` absent, et le banc dépend du réseau. Écarté par l'humain.
- **Récupération épinglée hors dépôt** (ignorée par git, comme `amont/`) : aucune redistribution,
  donc aucun risque de licence, mais `S` absent tant qu'une commande réseau n'a pas tourné.
  Écarté par l'humain.

## Amendements
