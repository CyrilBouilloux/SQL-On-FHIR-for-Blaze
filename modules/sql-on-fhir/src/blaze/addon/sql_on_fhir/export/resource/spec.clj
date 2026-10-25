(ns blaze.addon.sql-on-fhir.export.resource.spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def :blaze.addon.sql-on-fhir.export.resource/base-url string?)
(s/def :blaze.addon.sql-on-fhir.export.resource/context-path string?)
(s/def :blaze.addon.sql-on-fhir.export.resource/id string?)
(s/def :blaze.addon.sql-on-fhir.export.resource/status #{"accepted" "in-progress"})
(s/def :blaze.addon.sql-on-fhir.export.resource/status-token string?)
(s/def :blaze.addon.sql-on-fhir.export.resource/client-tracking-id (s/nilable string?))
(s/def :blaze.addon.sql-on-fhir.export.resource/name string?)
(s/def :blaze.addon.sql-on-fhir.export.resource/token string?)
(s/def :blaze.addon.sql-on-fhir.export.resource/output
  (s/keys :req-un [:blaze.addon.sql-on-fhir.export.resource/name :blaze.addon.sql-on-fhir.export.resource/token]))
(s/def :blaze.addon.sql-on-fhir.export.resource/outputs
  (s/coll-of :blaze.addon.sql-on-fhir.export.resource/output))
