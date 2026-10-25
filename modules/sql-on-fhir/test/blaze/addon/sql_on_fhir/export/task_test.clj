(ns blaze.addon.sql-on-fhir.export.task-test
  "AS-03 : la `Task` d'un export porte sa spécification et ses jetons."
  (:require
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.export.task-spec]
   [blaze.addon.sql-on-fhir.export.token :as token]
   [blaze.job.util :as job-util]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [juxt.iota :refer [given]])
  (:import
   [java.time OffsetDateTime]))

(set! *warn-on-reflection* true)
(st/instrument)

(test/use-fixtures :each tu/fixture)

(def ^:private spec
  {:t 42
   :format "csv"
   :header false
   :views [{:name "pat" :token (token/new-token) :view {:resourceType "ViewDefinition" :resource "Patient"}}
           {:name "obs" :token (token/new-token) :view {:resourceType "ViewDefinition" :resource "Observation"}}]
   :client-tracking-id "suivi-1"
   :patient "p1"
   :group "g1"
   :since "2026-10-12T08:00:00Z"
   :status-token (token/new-token)
   :result-token (token/new-token)})

(def ^:private authored-on (OffsetDateTime/parse "2026-10-12T10:00:00+02:00"))

(deftest nouvelle-tache
  (given (task/new-task (assoc spec :authored-on authored-on))
    :fhir/type := :fhir/Task
    [:status :value] := "ready"
    [:intent :value] := "order"
    job-util/job-type := :viewdefinition-export))

(deftest aller-retour
  (testing "la spécification relue de la tâche est celle qui l'a faite"
    (is (= (dissoc spec :authored-on)
           (task/spec (task/new-task (assoc spec :authored-on authored-on))))))
  (testing "filtres et suivi facultatifs"
    (let [minimal (dissoc spec :client-tracking-id :patient :group :since)]
      (is (= minimal (task/spec (task/new-task (assoc minimal :authored-on authored-on))))))))

(deftest jetons
  (let [t (task/new-task (assoc spec :authored-on authored-on))]
    (is (= (:status-token spec) (task/token t :status)))
    (is (= (:result-token spec) (task/token t :result)))
    (testing "jetons de fichier : un identifiant par vue, dans l'ordre"
      (is (= (mapv :token (:views spec))
             (mapv :token (:views (task/spec t))))))
    (testing "tâche d'un autre type : aucun jeton"
      (is (nil? (task/token (dissoc t :identifier) :status))))))
