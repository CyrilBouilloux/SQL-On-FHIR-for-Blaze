(ns blaze.addon.sql-on-fhir.fhir-path.existants-test
  "T-004 : socle de mesure FHIRPath. Registre de types initialisé hors serveur (IB-06) ;
  cas existants de `blaze.fhir-path` intacts après chargement du module (IB-01…IB-04)."
  (:require
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private patient
  {:fhir/type :fhir/Patient :id "x"
   :name [{:fhir/type :fhir/HumanName :family "Doe"}]})

(deftest registre-de-types-test
  (testing "un spécificateur de type compile et s'évalue une fois le registre initialisé"
    (let [expr (fp/compile "Patient.name.ofType(HumanName)")]
      (is (not (ba/anomaly? expr)))
      (given (fp/eval nil expr patient)
        [0 :family] := "Doe"))))

(def ^:private invocation
  "org.cqframework.cql.gen.fhirpathParser$InvocationExpressionContext")

(def ^:private rapport (delay (su/rapport-existants)))

(deftest instantane-non-vide-test
  (testing "relevé de départ : 8 méthodes, 24 contextes"
    (given @rapport
      :methodes := '(":default" "as" "exists" "extension" "first" "ofType" "resolve" "where")
      :classes := 24)))

(deftest cas-existants-intacts-test
  (testing "après chargement du module, chaque fonction du jar est identical?"
    (is (empty? (:apres-chargement @rapport))))
  (testing "les remplacements d'AdditiveExpressionContext et d'EqualityExpressionContext sont les seules exceptions admises"
    (is (empty? (:apres-additive @rapport)))))

(deftest egalite-fusion-test
  (testing "IB-15 : l'original capturé est celui du jar, seul -compile est remplacé"
    (is (true? (:egalite-original-identique @rapport)))
    (is (true? (:egalite-compile-remplace @rapport)))
    (is (true? (:egalite-autres-fonctions-identiques @rapport)))))

(deftest invocation-expression-compile-intact-test
  (testing "le jar n'implémente que -compile pour InvocationExpressionContext"
    (is (= [:-compile] (:invocation-avant @rapport))))
  (testing "le module ne le remplace pas"
    (is (not-any? #(= invocation (second %)) (:apres-chargement @rapport))))
  (testing "témoin rouge : remplacer ce -compile est détecté"
    (is (= [[:impl invocation :-compile]] (:apres-invocation @rapport)))))

(deftest differences-test
  (let [f (fn []) g (fn [])
        avant {:methods {"a" f} :impls {"C" {:-compile f}}}]
    (testing "identique"
      (is (empty? (su/differences avant avant))))
    (testing "méthode remplacée"
      (is (= [[:method "a"]] (su/differences avant (assoc-in avant [:methods "a"] g)))))
    (testing "méthode de classe perdue"
      (is (= [[:impl "C" :-compile]] (su/differences avant (assoc-in avant [:impls "C"] {})))))))
