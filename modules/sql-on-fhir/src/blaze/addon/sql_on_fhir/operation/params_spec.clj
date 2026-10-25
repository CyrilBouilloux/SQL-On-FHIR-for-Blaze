(ns blaze.addon.sql-on-fhir.operation.params-spec
  (:require
   [blaze.addon.sql-on-fhir.operation.params :as params]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/fdef params/read-params
  :args (s/cat :body any? :query-format (s/nilable string?) :accept-format (s/? (s/nilable string?)))
  :ret (s/or :params map? :anomaly ba/anomaly?))

(s/fdef params/read-export-params
  :args (s/cat :body any?)
  :ret (s/or :params map? :anomaly ba/anomaly?))

(s/fdef params/name-views
  :args (s/cat :views (s/coll-of map?))
  :ret (s/or :views (s/coll-of map?) :anomaly ba/anomaly?))

(s/fdef params/unprocessable
  :args (s/cat :anomaly ba/anomaly?)
  :ret ba/anomaly?)
