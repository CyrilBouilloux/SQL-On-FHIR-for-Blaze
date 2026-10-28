(ns blaze.addon.sql-on-fhir.sql.query-spec
  (:require
   [blaze.addon.sql-on-fhir.sql.query :as query]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s]))

(s/def ::sql string?)
(s/def ::names (s/coll-of string? :kind vector?))
(s/def ::name string?)
(s/def ::type string?)
(s/def ::canonical string?)
(s/def ::tables (s/coll-of (s/keys :req-un [::name ::canonical]) :kind vector?))
(s/def ::declared (s/coll-of (s/keys :req-un [::name ::type]) :kind vector?))

(s/fdef query/named->positional
  :args (s/cat :sql string?)
  :ret (s/keys :req-un [::sql ::names]))

(s/fdef query/read-library
  :args (s/cat :library map?)
  :ret (s/or :query (s/keys :req-un [::sql ::tables ::declared]) :anomaly ba/anomaly?))

(s/fdef query/bind-values
  :args (s/cat :declared ::declared :names ::names :parameters (s/nilable map?))
  :ret (s/or :values (s/coll-of (s/keys :req-un [::type]) :kind vector?) :anomaly ba/anomaly?))
