(ns blaze.addon.sql-on-fhir.operation.run-spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def :blaze.addon.sql-on-fhir.operation.run/max-rows pos-int?)
