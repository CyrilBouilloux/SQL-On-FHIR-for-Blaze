# Provenance — suite de conformité SQL on FHIR

Copie **non modifiée** du répertoire `tests/` (22 fichiers) et de `LICENSE.md` du dépôt
`https://github.com/FHIR/sql-on-fhir.js`, commit `0821b673346bb859658dfc65f6b4808c23e028db`
(2026-09-22, John Grimes), copiés le 2026-10-10 par `git archive`. Décision : ADR 0006.

**Attribution** : © les contributeurs de `FHIR/sql-on-fhir.js` (HL7 FHIR, projet SQL on FHIR) ;
auteurs de `tests/` au commit (`git log --format=%an -- tests`) : John Grimes, Aleksandr Penskoi.

**Licence** : `LICENSE.md`, ci-contre, recopié tel quel — texte MIT **sans ligne de copyright**.
Le dépôt d'origine déclare aussi `Apache-2.0` (`package.json` racine) et `ISC`
(`sof-js/package.json`) : déclarations discordantes, risque résiduel accepté par l'humain (ADR 0006).

**Ne pas modifier ces fichiers.** Contrôle d'identité, rejouable :

    git -C <clone> rev-parse 0821b673:tests                    # e627d7a9a391ec083fea9ab2f40809877c11ee57
    git rev-parse HEAD:modules/sql-on-fhir/conformance/sql-on-fhir-js/tests   # même valeur attendue
