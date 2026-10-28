(ns blaze.addon.sql-on-fhir.sql.engine-spec
  (:require
   [blaze.addon.sql-on-fhir.sql.engine :as engine]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s])
  (:import
   [java.sql Connection]))

(s/def ::type string?)
(s/def ::connection #(instance? Connection %))

(s/def ::timeout-seconds pos-int?)
(s/def ::max-rows pos-int?)
(s/def ::columns (s/coll-of string? :kind vector?))
(s/def ::rows (s/coll-of map? :kind vector?))

(s/fdef engine/configure!
  :args (s/cat :conn ::connection :max-memory string?)
  :ret (s/nilable ba/anomaly?))

(s/fdef engine/check-select!
  :args (s/cat :conn ::connection :sql string?)
  :ret (s/nilable ba/anomaly?))

(s/fdef engine/query!
  :args (s/cat :conn ::connection :sql string?
               :params (s/coll-of (s/keys :req-un [::type]) :kind vector?)
               :opts (s/keys :req-un [::timeout-seconds ::max-rows]))
  :ret (s/or :result (s/keys :req-un [::columns ::rows]) :anomaly ba/anomaly?))

(s/fdef engine/timeout
  :args (s/cat)
  :ret ba/anomaly?)

(s/fdef engine/memory-size?
  :args (s/cat :s any?)
  :ret boolean?)
