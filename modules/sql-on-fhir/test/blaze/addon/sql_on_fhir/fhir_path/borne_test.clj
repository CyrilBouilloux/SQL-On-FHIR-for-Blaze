(ns blaze.addon.sql-on-fhir.fhir-path.borne-test
  "T-017 (F-005, FP-06) : les bornes sans argument sur décimal, date-heure, date et heure, contre
  les attendus littéraux du critère « Conformité » de F-005. Ressources synthétiques écrites ici,
  lues par le lecteur JSON R4 de Blaze ; aucun extrait de la suite S."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.function]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [integrant.core :as ig]
   [jsonista.core :as j]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private contexte
  (delay (ig/init-key :blaze.fhir/parsing-context
                      {:structure-definition-repo
                       (ig/init-key :blaze.fhir/structure-definition-repo {})})))

(defn- lire [m]
  (let [r (fhir-spec/parse-json @contexte (j/write-value-as-string m))]
    (assert (not (ba/anomaly? r)) (pr-str r))
    r))

(defn- observation [valeur]
  (lire (merge {:resourceType "Observation" :id "o1" :code {:text "code"} :status "final"} valeur)))

(def ^:private decimal (delay (observation {:valueQuantity {:value 1.0}})))
(def ^:private date-heure (delay (observation {:effectiveDateTime "2010-10-10"})))
(def ^:private heure (delay (observation {:valueTime "12:34:00"})))
(def ^:private date
  (delay (lire {:resourceType "Patient" :id "p1" :birthDate "1970-06"})))

(defn- brut [expression ressource]
  (let [r (su/evaluer expression @ressource)]
    (if (ba/anomaly? r) r (mapv :valeur r))))

(def ^:private cas
  [["décimal 1.0, bas" "value.ofType(Quantity).value.lowBoundary()" decimal [0.95M]]
   ["décimal 1.0, haut" "value.ofType(Quantity).value.highBoundary()" decimal [1.05M]]
   ["date-heure, bas" "effective.ofType(dateTime).lowBoundary()" date-heure
    ["2010-10-10T00:00:00.000+14:00"]]
   ["date-heure, haut" "effective.ofType(dateTime).highBoundary()" date-heure
    ["2010-10-10T23:59:59.999-12:00"]]
   ["date 1970-06, bas" "birthDate.lowBoundary()" date ["1970-06-01"]]
   ["date 1970-06, haut" "birthDate.highBoundary()" date ["1970-06-30"]]
   ["heure, bas" "value.ofType(time).lowBoundary()" heure ["12:34:00.000"]]
   ["heure, haut" "value.ofType(time).highBoundary()" heure ["12:34:00.999"]]])

(deftest ressources-test
  (testing "le décimal lu garde sa précision : une décimale"
    (is (= "1.0" (str (-> @decimal :value :value :value))))))

(deftest bornes-sans-argument-test
  (testing "8 cas"
    (is (= 8 (count cas))))
  (doseq [[libelle expression ressource attendu] cas]
    (testing libelle
      (is (= attendu (brut expression ressource)) expression))))

(deftest compilation-test
  (testing "les 8 expressions compilent"
    (doseq [[_ expression] cas]
      (is (not (ba/anomaly? (fp/compile expression))) expression))))
