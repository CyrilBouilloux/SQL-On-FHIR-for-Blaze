(ns blaze.addon.sql-on-fhir.view.compile-spec
  (:require
   [blaze.addon.sql-on-fhir.view.compile :as compile]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/fdef compile/compile
  :args (s/cat :view any?)
  :ret (s/or :compiled map? :anomaly ba/anomaly?))
