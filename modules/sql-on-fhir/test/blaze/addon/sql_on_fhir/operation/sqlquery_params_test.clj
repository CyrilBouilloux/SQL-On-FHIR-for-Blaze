(ns blaze.addon.sql-on-fhir.operation.sqlquery-params-test
  "T-116 (SQ-03, F-039) : entrées de `$sqlquery-run`, codes de refus de l'OperationDefinition (Q3)."
  (:require
   [blaze.addon.sql-on-fhir.operation.sqlquery-params :as params]
   [blaze.addon.sql-on-fhir.operation.sqlquery-params-spec]
   [blaze.test-util :as tu]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :each tu/fixture)

(defn- body [& parameters] {:resourceType "Parameters" :parameter (vec parameters)})

(def ^:private lib {:name "queryResource" :resource {:resourceType "Library"}})

(deftest entrees-lues
  (testing "Library en ligne : format ndjson et en-tête par défaut"
    (given (params/read-params (body lib) nil nil)
      :library := {:resourceType "Library"}
      :format := "ndjson"
      :header := true
      :limit := nil))
  (testing "référence, valeurs, en-tête, limite ; le format de la requête l'emporte sur celui du corps, lui-même sur Accept"
    (given (params/read-params (body {:name "queryReference" :valueReference {:reference "Library/l1"}}
                                     {:name "parameters" :resource {:resourceType "Parameters"}}
                                     {:name "header" :valueBoolean false}
                                     {:name "_limit" :valueInteger 5}
                                     {:name "_format" :valueCode "json"})
                               nil "csv")
      :reference := "Library/l1"
      :parameters := {:resourceType "Parameters"}
      :header := false
      :limit := 5
      :format := "json")
    (is (= "csv" (:format (params/read-params (body lib) "csv" "json"))))
    (is (= "json" (:format (params/read-params (body lib) nil "json"))))))

(deftest entrees-refusees
  (doseq [[libelle b qf categorie]
          [["pas un Parameters" {:resourceType "Patient"} nil ::anom/incorrect]
           ["ni queryResource ni queryReference" (body) nil ::anom/incorrect]
           ["les deux" (body lib {:name "queryReference" :valueString "Library/l1"}) nil ::anom/incorrect]
           ["entrée inconnue" (body lib {:name "zzz" :valueString "x"}) nil ::anom/incorrect]
           ["entrée répétée" (body lib lib) nil ::anom/incorrect]
           ["_limit nul" (body lib {:name "_limit" :valueInteger 0}) nil ::anom/incorrect]
           ["_limit sans entier" (body lib {:name "_limit" :valueString "3"}) nil ::anom/incorrect]
           ["queryReference sans référence" (body {:name "queryReference" :valueInteger 1}) nil ::anom/incorrect]
           ["source" (body lib {:name "source" :valueString "s"}) nil ::anom/unsupported]
           ["format xml" (body lib) "xml" ::anom/unsupported]]]
    (testing libelle
      (given (params/read-params b qf nil)
        ::anom/category := categorie
        :http/status := 400))))

(deftest specification-generative
  (testing "`:args` et `:ret` de `read-params` tiennent sur des entrées engendrées"
    (let [res (st/check [`params/read-params] {:clojure.spec.test.check/opts {:num-tests 200}})]
      (is (every? #(nil? (:failure %)) res) (pr-str (map #(select-keys % [:sym :failure]) res))))))

(deftest specification-de-retour
  (let [ret (:ret (s/get-spec `params/read-params))]
    (is (s/valid? ret (params/read-params (body lib) nil nil)))
    (is (s/valid? ret (params/read-params (body) nil nil)))))
