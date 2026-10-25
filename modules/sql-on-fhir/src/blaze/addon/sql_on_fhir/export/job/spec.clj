(ns blaze.addon.sql-on-fhir.export.job.spec
  (:require
   [blaze.db.spec]
   [clojure.spec.alpha :as s]))

(s/def :blaze.addon.sql-on-fhir.export.job/main-node :blaze.db/node)
(s/def :blaze.addon.sql-on-fhir.export.job/admin-node :blaze.db/node)
(s/def :blaze.addon.sql-on-fhir.export.job/dir (s/and string? (partial re-matches #"/.*")))
(s/def :blaze.addon.sql-on-fhir.export.job/db-sync-timeout pos-int?)
(s/def :blaze.addon.sql-on-fhir.export.job/executor (partial instance? java.util.concurrent.ExecutorService))
(s/def :blaze.addon.sql-on-fhir.export.job/max-bytes pos-int?)
