(ns blaze.addon.sql-on-fhir.sql.tables-spec
  (:require
   [blaze.addon.sql-on-fhir.sql.tables :as tables]
   [blaze.anomaly :as ba]
   [clojure.spec.alpha :as s])
  (:import
   [java.sql Connection]))

(s/def ::connection #(instance? Connection %))

(s/fdef tables/sql-type
  :args (s/cat :type (s/nilable string?))
  :ret (s/nilable string?))

(s/fdef tables/quote-identifier
  :args (s/cat :s string?)
  :ret string?)

(s/fdef tables/open-memory-db!
  :args (s/cat)
  :ret ::connection)

(s/fdef tables/load-tables!
  :args (s/cat :conn ::connection
               :tables (s/coll-of (s/keys :req-un [::name ::view ::resources])))
  :ret (s/nilable ba/anomaly?))

(s/fdef tables/columns
  :args (s/cat :conn ::connection :table string?)
  :ret (s/coll-of (s/keys :req-un [::name ::type]) :kind vector?))
