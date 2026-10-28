(ns blaze.addon.sql-on-fhir.operation.sqlquery-spec
  (:require
   [blaze.addon.sql-on-fhir.sql.engine :as engine]
   [clojure.spec.alpha :as s]))

(s/def :blaze.addon.sql-on-fhir.operation.sqlquery/max-rows pos-int?)
(s/def :blaze.addon.sql-on-fhir.operation.sqlquery/timeout-seconds pos-int?)
(s/def :blaze.addon.sql-on-fhir.operation.sqlquery/max-memory engine/memory-size?)
(s/def :blaze.addon.sql-on-fhir.operation.sqlquery/max-concurrent pos-int?)
