(ns blaze.addon.sql-on-fhir.serializer-spec
  (:require
   [blaze.addon.sql-on-fhir.serializer :as serializer]
   [clojure.spec.alpha :as s]))

(s/def ::serializer/body string?)
(s/def ::serializer/content-type string?)

(s/fdef serializer/serialize
  :args (s/cat :format #{"json" "ndjson" "csv"} :schema (s/coll-of map?) :rows (s/coll-of map?)
               :header? boolean?)
  :ret (s/keys :req-un [::serializer/body ::serializer/content-type]))

(s/def ::serializer/head (s/nilable string?))
(s/def ::serializer/tail (s/nilable string?))
(s/def ::serializer/row fn?)

(s/fdef serializer/encoder
  :args (s/cat :format #{"json" "ndjson" "csv"} :schema (s/coll-of map?) :header? boolean?)
  :ret (s/keys :req-un [::serializer/row ::serializer/content-type] :opt-un [::serializer/head ::serializer/tail]))
