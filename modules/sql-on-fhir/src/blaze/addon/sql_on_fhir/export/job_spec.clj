(ns blaze.addon.sql-on-fhir.export.job-spec
  (:require
   [blaze.addon.sql-on-fhir.export.job :as job]
   [blaze.addon.sql-on-fhir.export.job.spec]
   [blaze.anomaly :as ba]
   [blaze.db.spec]
   [clojure.spec.alpha :as s]))

(s/fdef job/status-check
  :args (s/cat :admin-node :blaze.db/node :id string?)
  :ret fn?)

(s/fdef job/interruptible
  :args (s/cat :check! fn? :source (partial instance? clojure.lang.IReduceInit))
  :ret (partial instance? clojure.lang.IReduceInit))

(s/fdef job/write-view!
  :args (s/cat :out (partial instance? java.io.Writer) :step pos-int? :check-space! fn? :check! fn?
               :format #{"json" "ndjson" "csv"} :schema some? :header any?
               :source (partial instance? clojure.lang.IReduceInit))
  :ret nat-int?)

(s/fdef job/run-export
  :args (s/cat :ctx (s/and (s/keys :req-un [::job/main-node ::job/db-sync-timeout ::job/max-bytes]) (comp (partial instance? java.io.File) :dir))
               :check! fn? :spec map?)
  :ret (s/or :done #{:done :paused :cancelled} :anomaly ba/anomaly?))
