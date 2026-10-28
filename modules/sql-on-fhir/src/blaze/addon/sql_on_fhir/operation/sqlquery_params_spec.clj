(ns blaze.addon.sql-on-fhir.operation.sqlquery-params-spec
  (:require
   [blaze.addon.sql-on-fhir.operation.sqlquery-params :as params]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/def ::format #{"json" "ndjson" "csv"})
(s/def ::header boolean?)

(s/fdef params/read-params
  :args (s/cat :body (s/nilable map?) :query-format (s/nilable string?) :accept-format (s/nilable string?))
  :ret (s/or :params (s/keys :req-un [::format ::header]) :anomaly ba/anomaly?))
