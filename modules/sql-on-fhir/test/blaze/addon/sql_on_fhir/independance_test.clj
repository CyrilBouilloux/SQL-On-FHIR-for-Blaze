(ns blaze.addon.sql-on-fhir.independance-test
  "T-025 (F-005, « Indépendance de la base ») : le juge rend trois réponses (§3.6 règle 5) —
  moteur absent : 3 ; moteur non chargeable, banc incomplet ou namespace fautif : 8 ; sinon 0."
  (:require
   [blaze.addon.sql-on-fhir.conformance :as conformance]
   [blaze.addon.sql-on-fhir.independance :as independance]
   [blaze.test-util :as tu]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each tu/fixture)

(def ^:private moteur {:compile identity :run identity})

(defn- banc [code]
  #(do (println "REUSSIS 91/144  ECHEC 53  NON-BRANCHE 0") code))

(deftest moteur-absent-test
  (is (= 3 (first (independance/verdict {:compile nil :run nil} (banc 0) (constantly []))))))

(deftest moteur-non-chargeable-test
  (let [m (conformance/charger 'blaze.addon.sql-on-fhir.fixture.casse/f
                               'blaze.addon.sql-on-fhir.fixture.casse/f)
        [code message] (independance/verdict m (banc 0) (constantly []))]
    (is (= 8 code))
    (is (str/includes? message (:erreur m)))))

(deftest banc-incomplet-test
  (testing "le décompte du banc est dans le message"
    (let [[code message] (independance/verdict moteur (banc 8) (constantly []))]
      (is (= 8 code))
      (is (str/includes? message "REUSSIS 91/144  ECHEC 53")))))

(deftest namespace-fautif-test
  (let [[code message] (independance/verdict moteur (banc 0) (constantly ["blaze.db.api"]))]
    (is (= 8 code))
    (is (str/includes? message "blaze.db.api"))))

(deftest succes-test
  (is (= 0 (first (independance/verdict moteur (banc 0) (constantly []))))))
