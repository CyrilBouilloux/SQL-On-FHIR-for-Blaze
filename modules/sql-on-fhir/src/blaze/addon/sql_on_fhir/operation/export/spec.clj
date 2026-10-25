(ns blaze.addon.sql-on-fhir.operation.export.spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def :blaze.addon.sql-on-fhir.operation.export/base-url string?)
(s/def :blaze.addon.sql-on-fhir.operation.export/context-path string?)
(s/def :blaze.addon.sql-on-fhir.operation.export/clock some?)
