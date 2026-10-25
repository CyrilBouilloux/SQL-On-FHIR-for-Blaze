(ns blaze.addon.sql-on-fhir.view.compilation-unique-test
  "T-021 (F-005, critère « Compilation unique ») : pour chacune des 133 vues `expect` de la suite
  copiée `S`, le nombre d'appels à `blaze.fhir-path/compile` (var redéfinie) est le même sur les
  ressources du cas et sur ces ressources répétées jusqu'à 1 000 (`id` renumérotés). Contre l'API
  cible de T-020, livrée par T-022. Témoin rouge :
  `temoin-rouge-compilation-par-ressource-test`."
  (:require
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [blaze.test-util :as tu]
   [clojure.java.io :as io]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [integrant.core :as ig]
   [jsonista.core :as j]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private suite "conformance/sql-on-fhir-js/tests")
(def ^:private mapper (j/object-mapper {:decode-key-fn true :bigdecimals true}))
(def ^:private cible 1000)

(def ^:private contexte
  (delay (ig/init-key :blaze.fhir/parsing-context
                      {:structure-definition-repo
                       (ig/init-key :blaze.fhir/structure-definition-repo {})})))

(defn- lire [m]
  (let [r (fhir-spec/parse-json @contexte (j/write-value-as-string m))]
    (assert (not (ba/anomaly? r)) (pr-str r))
    r))

(defn- repeter
  "`cible` ressources : celles du cas répétées en cycle, `id` renumérotés (`<id>-<k>`)."
  [resources]
  (if (empty? resources)
    []
    (vec (for [k (range cible)
               :let [r (nth resources (mod k (count resources)))]]
           (assoc r :id (str (:id r) "-" k))))))

(defn- api [sym]
  (try
    (requiring-resolve sym)
    (catch java.io.FileNotFoundException _ nil)))

(defn- moteur []
  {:compile (api 'blaze.addon.sql-on-fhir.view.compile/compile)
   :run (api 'blaze.addon.sql-on-fhir.view.run/run)})

(defn compter
  "Nombre d'appels à `blaze.fhir-path/compile` pendant `compile` puis `run` de la vue sur
  `ressources`. Une anomalie ou une exception arrête le décompte sans le fausser."
  [{:keys [compile run]} vue ressources]
  (let [n (atom 0)
        orig fp/compile]
    (with-redefs [fp/compile (fn [& args] (swap! n inc) (apply orig args))]
      (try
        (let [c (compile vue)]
          (when-not (ba/anomaly? c) (run c ressources)))
        (catch Throwable _ nil)))
    @n))

(defn ecart
  "Chaîne d'écart, ou nil si le décompte ne dépend pas du nombre de ressources."
  [moteur vue brutes]
  (let [petit (compter moteur vue (mapv lire brutes))
        grand (compter moteur vue (mapv lire (repeter brutes)))]
    (when (not= petit grand)
      (str "appels à blaze.fhir-path/compile : " petit " sur les ressources du cas, "
           grand " sur " cible " ressources"))))

(defn- cas-expect []
  (vec (for [f (sort (map #(.getName ^java.io.File %) (.listFiles (io/file suite))))
             :when (str/ends-with? f ".json")
             :let [{:keys [resources tests]} (j/read-value (io/file suite f) mapper)]
             t tests
             :when (:expect t)]
         [f (:title t) (:view t) resources])))

(deftest suite-test
  (is (= 133 (count (cas-expect)))))

(deftest compilation-unique-test
  (let [m (moteur)]
    (is (and (:compile m) (:run m))
        "API absente : brancher blaze.addon.sql-on-fhir.view.compile/compile et view.run/run (T-022, T-023 ; docs/couverture.md §3)")
    (when (and (:compile m) (:run m))
      (doseq [[fichier titre vue brutes] (cas-expect)]
        (testing (str fichier " « " titre " »")
          (is (nil? (ecart m vue brutes))))))))

(deftest temoin-rouge-compilation-par-ressource-test
  (let [vue {:resource "Patient" :select [{:column [{:name "id" :path "id"}]}]}
        brutes [{:resourceType "Patient" :id "p1"} {:resourceType "Patient" :id "p2"}]
        par-ressource {:compile (fn [v] v)
                       :run (fn [_ rs] (doseq [_ rs] (fp/compile "id")) [])}
        unique {:compile (fn [v] (fp/compile "id") v)
                :run (fn [_ rs] (mapv (fn [_] {}) rs))}]
    (testing "compiler par ressource rend le critère rouge"
      (is (= "appels à blaze.fhir-path/compile : 2 sur les ressources du cas, 1000 sur 1000 ressources"
             (ecart par-ressource vue brutes))))
    (testing "compiler une fois : vert"
      (is (nil? (ecart unique vue brutes))))
    (testing "renumérotation des id"
      (is (= 1000 (count (set (map :id (repeter brutes)))))))))
