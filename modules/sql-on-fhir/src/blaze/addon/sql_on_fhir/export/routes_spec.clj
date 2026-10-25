(ns blaze.addon.sql-on-fhir.export.routes-spec
  (:require
   [blaze.addon.sql-on-fhir.export.routes :as routes]
   [blaze.addon.sql-on-fhir.export.routes.spec]
   [clojure.spec.alpha :as s]))

(s/fdef routes/route
  :args (s/cat :ctx (s/and (s/keys :req-un [::routes/admin-node ::routes/job-scheduler ::routes/writing-context
                                            ::routes/base-url ::routes/context-path])
                           (comp (partial instance? java.io.File) :dir))
               :request map?)
  :ret (s/nilable (partial instance? java.util.concurrent.CompletableFuture)))
