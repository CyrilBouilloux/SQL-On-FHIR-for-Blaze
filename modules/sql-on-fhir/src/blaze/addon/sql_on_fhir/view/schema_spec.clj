(ns blaze.addon.sql-on-fhir.view.schema-spec
  (:require
   [blaze.addon.sql-on-fhir.view.schema :as schema]
   [clojure.spec.alpha :as s]))

(s/fdef schema/select-schema
  :args (s/cat :select map?)
  :ret (s/coll-of map? :kind vector?))

(s/fdef schema/schema
  :args (s/cat :view map?)
  :ret (s/coll-of map? :kind vector?))
