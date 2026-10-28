(ns blaze.addon.sql-on-fhir.sql.engine-test
  "T-116 (SQ-03, F-039, ADR 0014) : réglages d'isolement dans l'ordre, garde SELECT seul par la brique
  (`json_serialize_sql`), exécution à valeurs liées, plafonds de durée, de mémoire et de lignes. Les
  attendus viennent des relevés de T-112 (ADR 0014, « Leviers et témoins »)."
  (:require
   [blaze.addon.sql-on-fhir.sql.engine :as engine]
   [blaze.addon.sql-on-fhir.sql.engine-spec]
   [blaze.addon.sql-on-fhir.sql.tables :as tables]
   [blaze.test-util :as tu]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]])
  (:import
   [java.nio.file Files]
   [java.sql Connection SQLException]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :each tu/fixture)

(defn- execute! [^Connection conn ^String sql]
  (with-open [st (.createStatement conn)] (.execute st sql)))

(defn- message
  "Message de l'erreur du moteur lancée par `f`, ou nil si `f` réussit."
  [f]
  (try (f) nil (catch SQLException e (.getMessage e))))

(defn- ouverte
  "Base neuve réglée et garnie d'une table `patient` de deux lignes."
  ^Connection []
  (let [conn (tables/open-memory-db!)]
    (execute! conn "CREATE TABLE patient (id VARCHAR, genre VARCHAR)")
    (execute! conn "INSERT INTO patient VALUES ('a', 'f'), ('b', 'm')")
    (is (nil? (engine/configure! conn "256MiB")))
    conn))

(def ^:private limites {:timeout-seconds 5 :max-rows 10})

(deftest configure-test
  (testing "les quatre réglages, dans l'ordre, rendent sans erreur ; la configuration est ensuite verrouillée"
    (with-open [conn (tables/open-memory-db!)]
      (is (nil? (engine/configure! conn "256MiB")))
      (is (str/includes? (message #(execute! conn "SET memory_limit = '100GB'")) "configuration has been locked"))
      (is (str/includes? (message #(execute! conn "SET enable_external_access = true")) "ocked"))))
  (testing "témoin de l'ordre : temp_directory après l'accès externe est refusé par le moteur"
    (with-open [conn (tables/open-memory-db!)]
      (execute! conn "SET enable_external_access = false")
      (is (= "Permission Error: Modifying the temp_directory has been disabled by configuration"
             (message #(execute! conn "SET temp_directory = ''"))))))
  (testing "une limite de mémoire qui n'est pas une taille est refusée avant tout réglage"
    (with-open [conn (tables/open-memory-db!)]
      (given (engine/configure! conn "1'; DROP TABLE x; --")
        ::anom/category := ::anom/incorrect)
      (execute! conn "SET temp_directory = ''"))))

(def ^:private non-select
  ["CREATE TABLE z (i INT)" "INSERT INTO patient VALUES ('c', 'f')" "UPDATE patient SET id = 'x'"
   "DELETE FROM patient" "DROP TABLE patient" "SELECT 1; DROP TABLE patient"
   "ATTACH '/tmp/zz.db'" "COPY (SELECT 1) TO '/tmp/zz.csv'" "INSTALL httpfs" "LOAD httpfs"
   "SET memory_limit = '100GB'" "CREATE VIEW v AS SELECT 1" "EXPORT DATABASE 'd'" "PRAGMA version"
   "SELEC id FROM patient"])

(deftest check-select-test
  (with-open [conn (ouverte)]
    (testing "un SELECT, un WITH, une union, FROM seul, une liste de SELECT sont admis"
      (doseq [sql ["SELECT id FROM patient" "WITH a AS (SELECT 1 AS i) SELECT * FROM a"
                   "SELECT 1 UNION SELECT 2" "FROM patient" "SELECT id FROM patient WHERE genre = ?"]]
        (is (nil? (engine/check-select! conn sql)) sql)))
    (testing "toute autre instruction, une faute de syntaxe : 422 avant exécution, rien n'est exécuté"
      (doseq [sql non-select]
        (given (engine/check-select! conn sql)
          ::anom/category := ::anom/incorrect
          :http/status := 422))
      (is (= 2 (count (:rows (engine/query! conn "SELECT id FROM patient" [] {:timeout-seconds 5 :max-rows 10}))))))
    (testing "témoin : sans la garde, le moteur exécute la suppression (le verrou ne l'interdit pas), même par `query!`"
      (is (nil? (message #(execute! conn "CREATE TABLE z2 (i INT)"))))
      (engine/query! conn "DROP TABLE z2" [] limites)
      (is (str/includes? (message #(execute! conn "SELECT i FROM z2")) "does not exist"))
      (is (nil? (message #(execute! conn "DROP TABLE patient"))))
      (is (str/includes? (message #(execute! conn "SELECT id FROM patient")) "does not exist")))))

(deftest query-test
  (with-open [conn (ouverte)]
    (testing "colonnes et lignes, dans l'ordre ; valeurs liées par position"
      (given (engine/query! conn "SELECT id, genre FROM patient WHERE genre = ? ORDER BY id"
                            [{:type "string" :value "f"}] limites)
        :columns := ["id" "genre"]
        :rows := [{:id "a" :genre "f"}]))
    (testing "ordre des colonnes au-delà de huit, noms répétés rendus uniques"
      (let [sql (str "SELECT " (str/join ", " (map #(str % " AS c" %) (range 12))) ", 7 AS a, 8 AS a")
            {:keys [columns rows]} (engine/query! conn sql [] limites)]
        (is (= (conj (mapv #(str "c" %) (range 12)) "a" "a_1") columns))
        (is (= columns (mapv name (keys (first rows)))))))
    (testing "types : booléen, entier, décimal, liste, date, grand entier"
      (given (first (:rows (engine/query! conn "SELECT true AS b, 7 AS i, 1.5::DECIMAL(5,2) AS d, [1,2] AS l, DATE '2026-10-27' AS t, sum(1)::HUGEINT AS h, NULL AS n"
                                          [] limites)))
        :b := true :i := 7 :d := 1.5M :l := [1 2] :t := "2026-10-27" :h := 1N :n := nil))
    (testing "types liés : entier, booléen, décimal"
      (given (first (:rows (engine/query! conn "SELECT ? + 1 AS i, ? AS b, ? * 2 AS d"
                                          [{:type "integer" :value 3} {:type "boolean" :value true} {:type "decimal" :value 1.5}] limites)))
        :i := 4 :b := true :d := 3.0))
    (testing "valeur liée portant du SQL : traitée comme une valeur, 0 effet"
      (let [r (engine/query! conn "SELECT id FROM patient WHERE genre = ?"
                             [{:type "string" :value "'); DROP TABLE patient; --"}] limites)]
        (is (= [] (:rows r)))
        (is (= 2 (count (:rows (engine/query! conn "SELECT id FROM patient" [] limites)))))))
    (testing "plafond de lignes : au plus max-rows lignes lues"
      (is (= 3 (count (:rows (engine/query! conn "SELECT i FROM range(100) t(i)" [] {:timeout-seconds 5 :max-rows 3}))))))
    (testing "erreur d'exécution du moteur : 422"
      (doseq [sql ["SELECT zzz FROM patient" "SELECT * FROM table_inconnue" "" ";" "-- rien"]]
        (given (engine/query! conn sql [] limites)
          ::anom/category := ::anom/incorrect
          :http/status := 422)))))

(deftest query-refuse-lecture-fichier-reseau-extension-test
  (let [dir (Files/createTempDirectory "t116" (make-array java.nio.file.attribute.FileAttribute 0))
        cible (str dir "/x.csv")]
    (with-open [conn (ouverte)]
      (doseq [sql ["SELECT * FROM read_csv('/etc/passwd')" "SELECT * FROM read_text('/etc/hostname')"
                   "SELECT * FROM glob('/etc/*')" "SELECT * FROM read_csv('http://127.0.0.1:9/x.csv')"
                   "SELECT * FROM duckdb_extensions()"]]
        (given (engine/query! conn sql [] limites)
          ::anom/category := ::anom/incorrect
          :http/status := 422))
      (given (engine/query! conn (str "COPY (SELECT 1) TO '" cible "'") [] limites)
        :http/status := 422))
    (is (not (Files/exists (java.nio.file.Path/of cible (make-array String 0)) (make-array java.nio.file.LinkOption 0))))
    (is (Files/deleteIfExists dir) "le dossier d'essai, resté vide, est retiré")))

(deftest query-plafond-duree-test
  (with-open [conn (ouverte)]
    (let [t0 (System/nanoTime)
          r (engine/query! conn "SELECT sum(i * j) FROM range(100000) t(i), range(100000) u(j)" []
                           {:timeout-seconds 1 :max-rows 10})
          ms (quot (- (System/nanoTime) t0) 1000000)]
      (given r
        ::anom/category := ::anom/incorrect
        :http/status := 422
        :fhir/issue := "timeout")
      (is (< 900 ms 5000) (str ms " ms")))
    (testing "la base reste utilisable"
      (is (= [{:i 1}] (:rows (engine/query! conn "SELECT 1 AS i" [] limites)))))))

(deftest query-plafond-memoire-test
  (with-open [conn (tables/open-memory-db!)]
    (is (nil? (engine/configure! conn "32MiB")))
    (given (engine/query! conn "SELECT i FROM range(50000000) t(i) ORDER BY i DESC" [] {:timeout-seconds 30 :max-rows 10})
      ::anom/category := ::anom/incorrect
      :http/status := 422
      :fhir/issue := "too-costly")
    (testing "la base reste utilisable"
      (is (= [{:i 1}] (:rows (engine/query! conn "SELECT 1 AS i" [] limites)))))))

(deftest specifications-de-retour
  (testing "les valeurs réellement rendues satisfont `:ret` de chaque fonction publique"
    (with-open [conn (ouverte)]
      (letfn [(ret [sym] (:ret (s/get-spec sym)))]
        (is (s/valid? (ret `engine/query!) (engine/query! conn "SELECT id FROM patient" [] limites)))
        (is (s/valid? (ret `engine/query!) (engine/query! conn "SELECT zzz FROM patient" [] limites)))
        (is (s/valid? (ret `engine/check-select!) (engine/check-select! conn "SELECT 1")))
        (is (s/valid? (ret `engine/check-select!) (engine/check-select! conn "DROP TABLE patient")))
        (is (s/valid? (ret `engine/configure!) (engine/configure! conn "xx")))
        (is (true? (engine/memory-size? "256MiB")))
        (is (false? (engine/memory-size? 256)))))))
