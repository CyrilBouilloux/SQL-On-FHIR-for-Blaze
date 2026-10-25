(ns blaze.addon.sql-on-fhir.export.lifecycle-spec
  (:require
   [blaze.addon.sql-on-fhir.export.lifecycle :as lifecycle]
   [blaze.addon.sql-on-fhir.export.lifecycle.spec]
   [clojure.spec.alpha :as s])
  (:import
   [java.io File]
   [java.time Instant]))

(def ^:private task? (comp #{:fhir/Task} :fhir/type))

(s/def ::lifecycle/ctx
  (s/and (s/keys :req-un [::lifecycle/admin-node ::lifecycle/clock ::lifecycle/retention-seconds])
         (comp (partial instance? File) :dir)))

(s/fdef lifecycle/expired?
  :args (s/cat :task task? :now (partial instance? Instant) :retention-seconds pos-int?)
  :ret boolean?)

(s/fdef lifecycle/recovery-action
  :args (s/cat :task task?)
  :ret #{:resume :fail nil})

(s/fdef lifecycle/designated-tokens
  :args (s/cat :tasks (s/coll-of task?))
  :ret set?)

(s/fdef lifecycle/orphan-names
  :args (s/cat :names (s/coll-of string?) :tokens set?)
  :ret (s/coll-of string?))

(s/fdef lifecycle/recover!
  :args (s/cat :ctx (s/and (s/keys :req-un [::lifecycle/admin-node ::lifecycle/job-scheduler])
                           (comp (partial instance? File) :dir)))
  :ret (s/map-of #{:resumed :failed} nat-int?))

(s/fdef lifecycle/sweep!
  :args (s/cat :ctx ::lifecycle/ctx)
  :ret (s/map-of #{:expired :orphans} nat-int?))
