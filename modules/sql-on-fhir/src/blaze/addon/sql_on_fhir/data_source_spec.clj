(ns blaze.addon.sql-on-fhir.data-source-spec
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/def ::ds/patient string?)
(s/def ::ds/group string?)
(s/def ::ds/since string?)
(s/def ::ds/limit pos-int?)
(s/def ::ds/opts (s/keys :opt-un [::ds/patient ::ds/group ::ds/since ::ds/limit]))

(s/fdef ds/resources
  :args (s/cat :db some? :type string? :opts ::ds/opts)
  :ret (s/or :resources (partial instance? clojure.lang.IReduceInit) :anomaly ba/anomaly?))

(s/fdef ds/rows
  :args (s/cat :view map? :db some? :opts ::ds/opts)
  :ret (s/or :rows vector? :anomaly ba/anomaly?))

(s/fdef ds/row-source
  :args (s/cat :view map? :db some? :opts ::ds/opts)
  :ret (s/or :source (partial instance? clojure.lang.IReduceInit) :anomaly ba/anomaly?))

(s/fdef ds/resource-rows
  :args (s/cat :view map? :resources (partial instance? clojure.lang.IReduceInit))
  :ret (partial instance? clojure.lang.IReduceInit))
