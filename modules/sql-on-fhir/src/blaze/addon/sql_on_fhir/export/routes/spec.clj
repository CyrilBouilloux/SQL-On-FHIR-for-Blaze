(ns blaze.addon.sql-on-fhir.export.routes.spec
  (:require
   [blaze.db.spec]
   [clojure.spec.alpha :as s]))

(s/def :blaze.addon.sql-on-fhir.export.routes/admin-node :blaze.db/node)
(s/def :blaze.addon.sql-on-fhir.export.routes/dir (s/and string? (partial re-matches #"/.*")))
(s/def :blaze.addon.sql-on-fhir.export.routes/writing-context some?)
(s/def :blaze.addon.sql-on-fhir.export.routes/base-url string?)
(s/def :blaze.addon.sql-on-fhir.export.routes/context-path string?)
(s/def :blaze.addon.sql-on-fhir.export.routes/job-scheduler map?)
