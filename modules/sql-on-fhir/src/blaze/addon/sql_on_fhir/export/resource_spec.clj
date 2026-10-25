(ns blaze.addon.sql-on-fhir.export.resource-spec
  (:require
   [blaze.addon.sql-on-fhir.export.resource :as resource]
   [blaze.addon.sql-on-fhir.export.resource.spec]
   [clojure.spec.alpha :as s]))

(s/def ::resource/ctx (s/keys :req-un [::resource/base-url ::resource/context-path]))

(s/fdef resource/url
  :args (s/cat :ctx ::resource/ctx :kind #{:status :result :file} :token string?)
  :ret string?)

(s/fdef resource/status-parameters
  :args (s/cat :ctx ::resource/ctx
               :m (s/keys :req-un [::resource/status ::resource/id ::resource/status-token]
                          :opt-un [::resource/client-tracking-id]))
  :ret (comp #{:fhir/Parameters} :fhir/type))

(s/fdef resource/result-parameters
  :args (s/cat :ctx ::resource/ctx
               :m (s/keys :req-un [::resource/id ::resource/outputs] :opt-un [::resource/client-tracking-id]))
  :ret (comp #{:fhir/Parameters} :fhir/type))
