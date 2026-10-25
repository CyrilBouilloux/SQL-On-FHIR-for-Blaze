(ns blaze.addon.sql-on-fhir.fixture.casse
  "Fixture de `conformance_test.clj` : un namespace présent mais non chargeable (dépendance absente)."
  (:require
   [blaze.addon.sql-on-fhir.fixture.absent :as absent]))

(defn f [] absent/x)
