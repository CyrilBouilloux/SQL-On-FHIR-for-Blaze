(ns blaze.addon.sql-on-fhir.export.lifecycle.spec
  (:require
   [blaze.db.spec]
   [clojure.spec.alpha :as s]))

(s/def :blaze.addon.sql-on-fhir.export.lifecycle/job-scheduler map?)
(s/def :blaze.addon.sql-on-fhir.export.lifecycle/admin-node :blaze.db/node)
(s/def :blaze.addon.sql-on-fhir.export.lifecycle/dir (s/and string? (partial re-matches #"/.*")))
(s/def :blaze.addon.sql-on-fhir.export.lifecycle/retention-seconds pos-int?)
(s/def :blaze.addon.sql-on-fhir.export.lifecycle/period-seconds pos-int?)
(s/def :blaze.addon.sql-on-fhir.export.lifecycle/scheduler (partial instance? java.util.concurrent.ScheduledExecutorService))
(s/def :blaze.addon.sql-on-fhir.export.lifecycle/clock (partial instance? java.time.Clock))
