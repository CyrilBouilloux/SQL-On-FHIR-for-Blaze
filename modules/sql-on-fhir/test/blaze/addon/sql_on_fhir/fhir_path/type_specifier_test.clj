(ns blaze.addon.sql-on-fhir.fhir-path.type-specifier-test
  "T-012 : type qualifié dans `ofType` (`FHIR.Quantity`), IB-01 (`-compile-as-type-specifier`
  ajouté à `InvocationExpressionContext` par fusion, `-compile` d'origine intact)."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.type-specifier]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.fhir.spec.type :as type]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]])
  (:import
   [org.cqframework.cql.gen fhirpathParser$InvocationExpressionContext]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private observation
  {:fhir/type :fhir/Observation :id "obs-q" :status #fhir/code "final"
   :value (type/quantity {:value #fhir/decimal 5M :unit #fhir/string "mg"})})

(defn- evaluer [expression]
  (let [expr (fp/compile expression)]
    (if (ba/anomaly? expr) expr (fp/eval nil expr observation))))

(deftest type-qualifie-test
  (testing "FHIR.Quantity rend l'élément de Quantity"
    (let [qualifie (evaluer "value.ofType(FHIR.Quantity)")]
      (is (= 1 (count qualifie)))
      (is (= (evaluer "value.ofType(Quantity)") qualifie))))
  (testing "FHIR.Coding rend vide"
    (is (= [] (evaluer "value.ofType(FHIR.Coding)")))))

(deftest espace-de-noms-inconnu-test
  (testing "un espace de noms autre que FHIR est une anomalie incorrect, pas un fault"
    (given (fp/compile "value.ofType(Foo.Quantity)")
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*Foo.*"))
  (testing "une fonction après FHIR n'est pas un type"
    (given (fp/compile "value.ofType(FHIR.first())")
      ::anom/category := ::anom/incorrect))
  (testing "un type inconnu sous FHIR est une anomalie incorrect"
    (given (fp/compile "value.ofType(FHIR.Inconnu)")
      ::anom/category := ::anom/incorrect)))

(deftest fusion-test
  (testing "IB-01 : les deux fonctions de la classe sont présentes, `-compile` n'est pas perdu"
    (is (= #{:-compile :-compile-as-type-specifier}
           (set (keys (get-in fp/FPCompiler [:impls fhirpathParser$InvocationExpressionContext]))))))
  (testing "`name.given` compile encore"
    (is (not (ba/anomaly? (fp/compile "name.given"))))))
