(ns blaze.addon.sql-on-fhir.view.schema-test
  "T-022 (VE-17) : schéma de sortie, disponible avant la première ligne."
  (:require
   [blaze.addon.sql-on-fhir.view.schema :as schema]
   [blaze.addon.sql-on-fhir.view.schema-spec]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is use-fixtures]]))

(st/instrument)

(use-fixtures :each tu/fixture)

(deftest schema-test
  (is (= [{:name "id" :type "id" :collection false}
          {:name "n" :type nil :collection true}
          {:name "f" :type "string" :collection false}
          {:name "u" :type nil :collection false}]
         (schema/schema
          {:resource "Patient"
           :select [{:column [{:name "id" :path "id" :type "id"}
                              {:name "n" :path "name.family" :collection true}]
                     :select [{:forEach "name" :column [{:name "f" :path "family" :type "string"}]}]}
                    {:unionAll [{:column [{:name "u" :path "id"}]}
                                {:column [{:name "u" :path "id"}]}]}]}))))
