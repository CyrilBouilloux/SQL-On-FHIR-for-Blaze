(ns blaze.addon.sql-on-fhir.serializer.file-spec
  (:require
   [blaze.addon.sql-on-fhir.serializer.file :as file]
   [clojure.spec.alpha :as s])
  (:import
   [java.io Writer]))

(s/fdef file/write-rows!
  :args (s/cat :format #{"json" "ndjson" "csv"} :schema (s/coll-of map?)
               :header? boolean? :rows (partial instance? clojure.lang.IReduceInit)
               :out (partial instance? Writer))
  :ret nat-int?)
