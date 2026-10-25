(ns blaze.addon.sql-on-fhir.view.validate-spec
  (:require
   [blaze.addon.sql-on-fhir.view.validate :as validate]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/fdef validate/validate
  :args (s/cat :view any?)
  :ret (s/nilable ba/anomaly?))
