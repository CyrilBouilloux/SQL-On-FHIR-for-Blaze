(ns blaze.addon.sql-on-fhir.fhir-path.constant-test
  "T-011 (FP-12) : les 18 types de `ViewDefinition.constant.value[x]` (IG 2.1.0-pre, hors
  `integer64`, ADR 0005) versés dans `:constants`, contexte de `-eval` (IB-05), contre
  `:constantes-typees` de `attendus.edn`."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.constant :as constant]
   [blaze.addon.sql-on-fhir.fhir-path.constant-spec]
   [blaze.addon.sql-on-fhir.fhir-path.variable]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(defn- evaluer-constante [constante]
  (let [constantes (constant/constants [(assoc constante :name "c")])]
    (su/evaluer-avec "%c" (su/sonde :pat-vide) {:constants constantes})))

(deftest dix-huit-types-test
  (testing "l'oracle porte 18 types"
    (is (= 18 (count (:constantes-typees @su/attendus)))))
  (doseq [{:keys [cle valeur type]} (:constantes-typees @su/attendus)]
    (testing cle
      (is (= [{:type type :valeur valeur}]
             (evaluer-constante {(keyword cle) valeur}))))))

(deftest constantes-test
  (testing "plusieurs constantes, par nom"
    (is (= {"a" [1] "b" ["x"]}
           (constant/constants [{:name "a" :valueInteger 1} {:name "b" :valueString "x"}]))))
  (testing "aucune constante"
    (is (= {} (constant/constants [])))))

(deftest anomalies-test
  (testing "integer64 : absent de R4 (ADR 0005) -> unsupported, nomme le type"
    (given (constant/constants [{:name "c" :valueInteger64 "1"}])
      ::anom/category := ::anom/unsupported
      ::anom/message :# ".*valueInteger64.*"))
  (testing "aucun value[x] ou deux -> incorrect, nomme la constante"
    (given (constant/constants [{:name "c"}])
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*`c`.*")
    (given (constant/constants [{:name "c" :valueString "a" :valueBoolean true}])
      ::anom/category := ::anom/incorrect))
  (testing "valeur du mauvais type ou illisible -> incorrect, nomme la constante"
    (doseq [c [{:name "c" :valueString 1} {:name "c" :valueInteger "x"}
               {:name "c" :valueInteger 3000000000} {:name "c" :valueBoolean "true"}
               {:name "c" :valueDecimal "1.5"} {:name "c" :valueDate "xx"}]]
      (given (constant/constants [c])
        ::anom/category := ::anom/incorrect
        ::anom/message :# ".*`c`.*")))
  (testing "la première anomalie arrête le versement"
    (given (constant/constants [{:name "a" :valueString "x"} {:name "b" :valueDate "xx"}])
      ::anom/category := ::anom/incorrect)))
