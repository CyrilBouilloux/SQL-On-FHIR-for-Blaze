# sql-on-fhir

Moteur de ViewDefinitions (SQL on FHIR) pour Blaze, en module complémentaire de l'image
publiée. Plan : `docs/Architecture moteur SQL on FHIR pour Blaze.md` ; couverture :
`docs/couverture.md` ; organisation : `docs/methode-module.md` §4.

    eval "$(scripts/outils.sh env)"        # JDK, clojure, clj-kondo… du dépôt
    make -C modules/sql-on-fhir fmt lint test
    make image                             # FROM samply/blaze@sha256:… + ce module

`conformance/sql-on-fhir-js/` porte la suite de conformité partagée, copiée au commit `0821b673`
(licence et provenance à côté, ADR 0006) ; `conformance/blaze/` porte le banc (INF-02) : `make -C modules/sql-on-fhir conformance` (une ligne par fichier,
synthèse `REUSSIS n/144`), `conformance-temoin` (règle de comparaison, moteur factice), `independance` et `independance-temoin` (aucun `blaze.db`/`blaze.interaction` chargé, JVM neuve).
