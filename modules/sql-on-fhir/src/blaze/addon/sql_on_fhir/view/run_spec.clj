(ns blaze.addon.sql-on-fhir.view.run-spec
  (:require
   [blaze.addon.sql-on-fhir.view.run :as run]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/fdef run/run
  :args (s/cat :view map? :resources (s/or :coll coll? :reducible #(instance? clojure.lang.IReduceInit %)))
  :ret (s/or :rows (s/coll-of map? :kind vector?) :anomaly ba/anomaly?))
