(ns blaze.addon.sql-on-fhir.export.task-spec
  (:require
   [blaze.addon.sql-on-fhir.export.task :as task]
   [clojure.spec.alpha :as s]))

(s/fdef task/new-task
  :args (s/cat :spec map?)
  :ret (comp #{:fhir/Task} :fhir/type))

(s/fdef task/spec
  :args (s/cat :task (comp #{:fhir/Task} :fhir/type))
  :ret map?)

(s/fdef task/token
  :args (s/cat :task (comp #{:fhir/Task} :fhir/type) :kind #{:status :result :file})
  :ret (s/nilable string?))
