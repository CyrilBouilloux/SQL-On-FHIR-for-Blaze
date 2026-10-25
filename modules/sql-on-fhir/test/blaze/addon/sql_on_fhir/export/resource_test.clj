(ns blaze.addon.sql-on-fhir.export.resource-test
  "AS-02, AS-06 : URL et `Parameters` de suivi et de résultat."
  (:require
   [blaze.addon.sql-on-fhir.export.resource :as resource]
   [blaze.addon.sql-on-fhir.export.resource-spec]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [blaze.fhir.test-util :refer [structure-definition-repo]]
   [blaze.fhir.writing-context]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]))

(st/instrument)

(test/use-fixtures :each tu/fixture)

(def ^:private ctx {:base-url "http://localhost:8088" :context-path "/fhir"})

(def ^:private writing-context
  (delay (ig/init-key :blaze.fhir/writing-context {:structure-definition-repo structure-definition-repo})))

(defn- json [resource]
  (j/read-value (fhir-spec/write-json-as-string @writing-context resource) j/keyword-keys-object-mapper))

(defn- param [parameters nom]
  (first (filter #(= nom (:name %)) (:parameter parameters))))

(deftest urls
  (is (= "http://localhost:8088/fhir/__export/status/abc" (resource/url ctx :status "abc")))
  (is (= "http://localhost:8088/fhir/__export/result/abc" (resource/url ctx :result "abc")))
  (is (= "http://localhost:8088/fhir/__export/file/abc" (resource/url ctx :file "abc"))))

(deftest suivi
  (testing "accepté : exportId, status, location (le lieu de suivi)"
    (let [p (json (resource/status-parameters ctx {:status "accepted" :id "t1" :status-token "s0"}))]
      (given p
        :resourceType := "Parameters"
        [:parameter count] := 3)
      (is (= "t1" (:valueString (param p "exportId"))))
      (is (= "accepted" (:valueCode (param p "status"))))
      (is (= "http://localhost:8088/fhir/__export/status/s0" (:valueUri (param p "location"))))))
  (testing "en cours, avec clientTrackingId"
    (let [p (json (resource/status-parameters ctx {:status "in-progress" :id "t1" :status-token "s0"
                                                   :client-tracking-id "c1"}))]
      (is (= "in-progress" (:valueCode (param p "status"))))
      (is (= "c1" (:valueString (param p "clientTrackingId")))))))

(deftest resultat
  (let [p (json (resource/result-parameters ctx {:id "t1" :client-tracking-id "c1"
                                                 :outputs [{:name "pat" :token "f1"} {:name "obs" :token "f2"}]}))
        sorties (filter #(= "output" (:name %)) (:parameter p))]
    (is (= "completed" (:valueCode (param p "status"))))
    (is (= "t1" (:valueString (param p "exportId"))))
    (is (= "c1" (:valueString (param p "clientTrackingId"))))
    (is (= 2 (count sorties)))
    (given (first sorties)
      [:part 0 :name] := "name"
      [:part 0 :valueString] := "pat"
      [:part 1 :name] := "location"
      [:part 1 :valueUri] := "http://localhost:8088/fhir/__export/file/f1"))
  (testing "sans clientTrackingId : paramètre absent"
    (is (nil? (param (json (resource/result-parameters ctx {:id "t1" :outputs []})) "clientTrackingId")))))
