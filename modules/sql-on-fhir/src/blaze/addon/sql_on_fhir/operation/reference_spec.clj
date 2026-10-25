(ns blaze.addon.sql-on-fhir.operation.reference-spec
  (:require
   [blaze.addon.sql-on-fhir.operation.reference :as reference]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/fdef reference/resolve-view
  :args (s/cat :db some? :reference any?)
  :ret (s/or :view map? :anomaly ba/anomaly?))

(s/fdef reference/resolve-entry
  :args (s/cat :db some? :entry map?)
  :ret (s/or :entry map? :anomaly ba/anomaly?))
