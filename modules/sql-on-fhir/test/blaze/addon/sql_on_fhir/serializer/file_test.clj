(ns blaze.addon.sql-on-fhir.serializer.file-test
  "SR-05 : écriture au fil de l'eau. L'écrivain prend une source de lignes (`IReduceInit`) et un
  `Writer` : l'un et l'autre s'enveloppent (T-050 compte, à chaque ligne écrite, lignes tirées
  moins lignes écrites)."
  (:require
   [blaze.addon.sql-on-fhir.serializer :as serializer]
   [blaze.addon.sql-on-fhir.serializer.file :as file]
   [blaze.addon.sql-on-fhir.serializer.file-spec]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [juxt.iota :refer [given]])
  (:import
   [java.io StringWriter Writer]))

(set! *warn-on-reflection* true)
(st/instrument)

(test/use-fixtures :each tu/fixture)

(def ^:private schema [{:name "id"} {:name "name"}])

(defn- rows [n]
  (mapv (fn [i] {:id (str i) :name (when (even? i) "a,b")}) (range n)))

(defn- source
  "Source réductible de `lignes`, qui compte dans `tirees` chaque ligne remise au consommateur."
  [lignes tirees]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init]
      (reduce (fn [acc row] (swap! tirees inc) (f acc row)) init lignes))))

(defn- sortie
  "Écrivain qui journalise dans `ecarts` l'écart tirées - écrites à chaque appel de `write`,
  et compte les appels dans `ecritures`."
  [^Writer sw tirees ecritures ecarts]
  (proxy [Writer] []
    (write
      ([x] (.write sw ^String x)
           (swap! ecritures inc)
           (swap! ecarts conj (- @tirees @ecritures)))
      ([x off len] (.write sw ^String x ^long off ^long len)))
    (flush [] (.flush sw))
    (close [] (.close sw))))

(deftest identique-a-serialize
  (doseq [fmt ["ndjson" "csv" "json"]
          n [0 1 7]
          header? [true false]]
    (testing (str fmt " " n " lignes, header " header?)
      (let [sw (StringWriter.)]
        (is (= n (file/write-rows! fmt schema header? (source (rows n) (atom 0)) sw)))
        (is (= (:body (serializer/serialize fmt schema (rows n) header?)) (str sw)))))))

(deftest fil-de-l-eau
  (doseq [fmt ["ndjson" "csv" "json"]]
    (testing fmt
      (let [tirees (atom 0) ecritures (atom 0) ecarts (atom [])
            sw (StringWriter.)]
        (file/write-rows! fmt schema true (source (rows 50) tirees) (sortie sw tirees ecritures ecarts))
        (testing "chaque ligne écrite l'est avant que la suivante soit tirée"
          (is (<= 50 (count @ecarts)))
          (is (<= (apply max 0 @ecarts) 0)))))))

(deftest temoin-rouge-source-materialisee
  (testing "une variante qui tire toutes les lignes avant d'écrire la première rend l'écart égal au nombre de lignes"
    (let [tirees (atom 0) ecritures (atom 0) ecarts (atom [])
          sw (StringWriter.)
          src (source (rows 50) tirees)
          tout (reduce conj [] src)
          materialisee (reify clojure.lang.IReduceInit
                         (reduce [_ f init] (reduce f init tout)))]
      (file/write-rows! "ndjson" schema true materialisee (sortie sw tirees ecritures ecarts))
      (given @ecarts
        first := 49
        #(apply max 0 %) := 49))))
