(ns blaze.addon.sql-on-fhir.sql.query-test
  "T-116 (SQ-03, F-039) : lecture d'une Library SQLQuery, paramètres nommés `:nom` → positionnels `?`
  (jamais interpolés), valeurs typées liées. Fonctions pures, attendus écrits en clair."
  (:require
   [blaze.addon.sql-on-fhir.sql.query :as query]
   [blaze.addon.sql-on-fhir.sql.query-spec]
   [blaze.test-util :as tu]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :each tu/fixture)

(defn- base64 [s] (.encodeToString (Base64/getEncoder) (.getBytes ^String s "UTF-8")))

(defn- library
  ([sql] (library sql "application/sql;dialect=duckdb"))
  ([sql content-type]
   {:resourceType "Library"
    :type {:coding [{:system "https://sql-on-fhir.org/ig/CodeSystem/LibraryTypesCodes" :code "sql-query"}]}
    :relatedArtifact [{:type "depends-on" :label "patient" :resource "http://example.org/vd|1.0.0"}]
    :parameter [{:name "genre" :use "in" :type "string"}]
    :content [{:contentType content-type :data (base64 sql)}]}))

(deftest named->positional-test
  (testing "chaque :nom devient ? et les noms sont rendus dans l'ordre d'apparition"
    (given (query/named->positional "SELECT a FROM t WHERE x = :genre AND y > :n AND z = :genre")
      :sql := "SELECT a FROM t WHERE x = ? AND y > ? AND z = ?"
      :names := ["genre" "n" "genre"]))
  (testing "ni les littéraux, ni les identifiants, ni les commentaires, ni le cast :: ne sont des paramètres"
    (given (query/named->positional
            "SELECT ':a' AS \"b:c\", 'it''s :d', x::int, $$ :e $$ -- :f\n, /* :g */ y[1:2] FROM t WHERE k = :h")
      :sql := "SELECT ':a' AS \"b:c\", 'it''s :d', x::int, $$ :e $$ -- :f\n, /* :g */ y[1:2] FROM t WHERE k = ?"
      :names := ["h"]))
  (testing "sans paramètre"
    (given (query/named->positional "SELECT 1")
      :sql := "SELECT 1"
      :names := [])))

(deftest read-library-test
  (testing "la requête, les tables (label → canonique) et les paramètres déclarés"
    (given (query/read-library (library "SELECT id FROM patient WHERE genre = :genre"))
      :sql := "SELECT id FROM patient WHERE genre = :genre"
      :tables := [{:name "patient" :canonical "http://example.org/vd|1.0.0"}]
      :declared := [{:name "genre" :type "string"}]))
  (testing "un type de contenu sans dialecte est admis"
    (given (query/read-library (library "SELECT 1" "application/sql"))
      :sql := "SELECT 1"))
  (testing "Library sans vue ni paramètre"
    (given (query/read-library (-> (library "SELECT 1") (dissoc :relatedArtifact :parameter)))
      :tables := []
      :declared := []))
  (testing "refus 422 : dialecte autre, aucun contenu SQL, contenu illisible, relatedArtifact sans label"
    (doseq [lib [(library "SELECT 1" "application/sql;dialect=postgresql")
                 (assoc (library "SELECT 1") :content [{:contentType "text/plain" :data (base64 "SELECT 1")}])
                 (dissoc (library "SELECT 1") :content)
                 (assoc (library "SELECT 1") :content [{:contentType "application/sql" :data "***"}])
                 (assoc (library "SELECT 1") :relatedArtifact [{:type "depends-on" :resource "http://x"}])
                 (assoc (library "SELECT 1") :parameter [{:name "genre" :use "in"}])
                 (assoc (library "SELECT 1") :parameter [{:name "genre" :use "in" :type ""}])]]
      (given (query/read-library lib)
        ::anom/category := ::anom/incorrect
        :http/status := 422))))

(def ^:private declared [{:name "genre" :type "string"} {:name "n" :type "integer"}])

(defn- parameters [& params] {:resourceType "Parameters" :parameter (vec params)})

(deftest fonctions-pures-generatif
  (testing "les spécifications `:args` et `:ret` tiennent sur des entrées engendrées"
    (let [res (st/check [`query/named->positional `query/read-library `query/bind-values]
                        {:clojure.spec.test.check/opts {:num-tests 200}})]
      (is (= 3 (count res)))
      (is (every? #(nil? (:failure %)) res) (pr-str (map #(select-keys % [:sym :failure]) (remove #(nil? (:failure %)) res)))))))

(deftest bind-values-test
  (testing "valeurs typées dans l'ordre des ? (un nom répété donne deux valeurs)"
    (is (= [{:type "string" :value "f"} {:type "integer" :value 3} {:type "string" :value "f"}]
           (query/bind-values declared ["genre" "n" "genre"]
                              (parameters {:name "genre" :valueString "f"} {:name "n" :valueInteger 3})))))
  (testing "une valeur portant du SQL reste une valeur"
    (is (= [{:type "string" :value "'); DROP TABLE patient; --"}]
           (query/bind-values declared ["genre"]
                              (parameters {:name "genre" :valueString "'); DROP TABLE patient; --"} {:name "n" :valueInteger 1})))))
  (testing "refus 400"
    (doseq [[libelle names params]
            [["valeur manquante" ["genre"] (parameters {:name "n" :valueInteger 1})]
             ["aucun Parameters" ["genre"] nil]
             ["valeur d'un paramètre non déclaré" ["genre"] (parameters {:name "genre" :valueString "f"} {:name "n" :valueInteger 1} {:name "zzz" :valueString "x"})]
             ["paramètre du SQL non déclaré" ["zzz"] (parameters {:name "genre" :valueString "f"} {:name "n" :valueInteger 1})]
             ["type : chaîne pour un entier" ["n"] (parameters {:name "genre" :valueString "f"} {:name "n" :valueString "abc"})]
             ["valeur en double" ["genre"] (parameters {:name "genre" :valueString "f"} {:name "genre" :valueString "g"} {:name "n" :valueInteger 1})]]]
      (testing libelle
        (given (query/bind-values declared names params)
          ::anom/category := ::anom/incorrect
          :http/status := 400))))
  (testing "un paramètre déclaré sans valeur est refusé même s'il n'est pas utilisé"
    (given (query/bind-values declared [] (parameters {:name "genre" :valueString "f"}))
      :http/status := 400)))

(deftest specifications-de-retour
  (testing "les valeurs réellement rendues (avec tables, paramètres, valeurs) et les anomalies satisfont `:ret`"
    (letfn [(ret [sym] (:ret (s/get-spec sym)))]
      (is (s/valid? (ret `query/named->positional) (query/named->positional "SELECT :a")))
      (is (s/valid? (ret `query/read-library) (query/read-library (library "SELECT :genre"))))
      (is (s/valid? (ret `query/read-library) (query/read-library (dissoc (library "SELECT 1") :content))))
      (is (s/valid? (ret `query/bind-values)
                    (query/bind-values declared ["genre" "n"] (parameters {:name "genre" :valueString "f"} {:name "n" :valueInteger 1}))))
      (is (s/valid? (ret `query/bind-values) (query/bind-values declared ["genre"] nil))))))
