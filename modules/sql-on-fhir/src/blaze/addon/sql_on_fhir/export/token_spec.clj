(ns blaze.addon.sql-on-fhir.export.token-spec
  (:require
   [blaze.addon.sql-on-fhir.export.token :as token]
   [clojure.spec.alpha :as s]))

(s/fdef token/new-token
  :args (s/cat)
  :ret string?)

(s/fdef token/token?
  :args (s/cat :x any?)
  :ret boolean?)
