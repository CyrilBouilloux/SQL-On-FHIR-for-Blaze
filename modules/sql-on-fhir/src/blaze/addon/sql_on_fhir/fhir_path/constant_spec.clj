(ns blaze.addon.sql-on-fhir.fhir-path.constant-spec
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.constant :as constant]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/fdef constant/value-collection
  :args (s/cat :k keyword? :value any?)
  :ret (s/or :collection vector? :anomaly ba/anomaly?))

(s/fdef constant/constants
  :args (s/cat :constants (s/coll-of map?))
  :ret (s/or :constants map? :anomaly ba/anomaly?))
