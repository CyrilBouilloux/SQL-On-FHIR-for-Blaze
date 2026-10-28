(ns blaze.addon.sql-on-fhir.operation.sqlquery-test
  "T-116 (SQ-03, F-039, ADR 0014) : le handler de `$sqlquery-run` sur le nœud en mémoire du jar : lignes,
  paramètres liés, codes de refus (400, 404, 422, 503), plafonds de lignes, de durée, de mémoire et de
  concurrence, lecture seule (0 effet sur les données)."
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.addon.sql-on-fhir.noeud :as noeud]
   [blaze.addon.sql-on-fhir.operation.sortie :as sortie]
   [blaze.addon.sql-on-fhir.operation.sqlquery]
   [blaze.addon.sql-on-fhir.operation.sqlquery-spec]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.anomaly :as ba]
   [blaze.async.comp :as ac]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.test-util :as tu :refer [given-thrown]]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]
   [taoensso.timbre :as log])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)
(st/instrument)
(log/set-min-level! :trace)

(test/use-fixtures :once su/registre-types)
(test/use-fixtures :each tu/fixture)

(def ^:private cle :blaze.addon.sql-on-fhir.operation.sqlquery/handler)

(def ^:private config
  {:max-rows 10 :timeout-seconds 5 :max-memory "256MiB" :max-concurrent 2})

(deftest handler-init
  (testing "plafond absent ou invalide : démarrage refusé"
    (doseq [cfg [{} (assoc config :max-rows 0) (assoc config :timeout-seconds "30")
                 (assoc config :max-memory "256") (assoc config :max-memory "1'; --")
                 (assoc config :max-concurrent 0)]]
      (given-thrown (ig/init {cle cfg})
        :message := "Assertion failed on key :blaze.addon.sql-on-fhir.operation.sqlquery/handler when building system"))))

(def ^:private contexte (delay (fichier/contexte)))

(defn- lire [m] (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(defn- patient [id genre] [:put (lire {:resourceType "Patient" :id id :active true :gender genre})])

(def ^:private url-vue "http://example.org/fhir/ViewDefinition/sq-patient")

(def ^:private vue
  {:resourceType "ViewDefinition" :status "active" :resource "Patient" :name "patient"
   :select [{:column [{:name "id" :path "id" :type "id"} {:name "genre" :path "gender" :type "code"}]}]})

(defn- base64 [s] (.encodeToString (Base64/getEncoder) (.getBytes ^String s "UTF-8")))

(defn- vue-rangee [id]
  [:put (lire {:resourceType "Library" :id id :url url-vue :version "1.0.0" :status "active"
               :type {:coding [{:system "http://terminology.hl7.org/CodeSystem/library-type" :code "asset-collection"}]}
               :content [{:contentType "application/json" :data (base64 (j/write-value-as-string (assoc vue :url url-vue :version "1.0.0")))}]})])

(defn- library
  "Library SQLQuery JSON : `sql`, la table `patient` (vue rangée), paramètres déclarés `declared`."
  [sql & [declared]]
  (cond-> {:resourceType "Library" :status "active"
           :type {:coding [{:system "https://sql-on-fhir.org/ig/CodeSystem/LibraryTypesCodes" :code "sql-query"}]}
           :relatedArtifact [{:type "depends-on" :label "patient" :resource (str url-vue "|1.0.0")}]
           :content [{:contentType "application/sql;dialect=duckdb" :data (base64 sql)}]}
    declared (assoc :parameter declared)))

(defn- requete
  [db lib & params]
  {:blaze/db db
   :body {:resourceType "Parameters"
          :parameter (into [{:name "queryResource" :resource lib}] params)}})

(def ^:private trois
  [[(patient "p1" "female") (patient "p2" "male") (patient "p3" "female") (vue-rangee "v1")]])

(defn- avec-handler [cfg txs f]
  (let [systeme (noeud/demarrer)
        node (:blaze.db/node systeme)
        handler (ig/init-key cle cfg)]
    (try
      (run! #(noeud/ecrire! node %) txs)
      (f handler (d/db node) node)
      (finally (ig/halt! systeme)))))

(defn- lignes [reponse]
  (->> (str/split-lines (::sortie/raw reponse)) (remove str/blank?) (mapv #(j/read-value % j/keyword-keys-object-mapper)) (sort-by :id) vec))

(defn- compte [node type] (d/type-total (d/db node) type))

(deftest lignes-et-formats
  (avec-handler config trois
                (fn [handler db _]
                  (testing "NDJSON par défaut : les lignes de la vue, triées"
                    (given @(handler (requete db (library "SELECT id, genre FROM patient")))
                      :status := 200
                      :body := nil
                      ::sortie/content-type := "application/x-ndjson"
                      lignes := [{:id "p1" :genre "female"} {:id "p2" :genre "male"} {:id "p3" :genre "female"}]))
                  (testing "filtre, agrégat"
                    (given @(handler (requete db (library "SELECT genre, count(*) AS n FROM patient GROUP BY genre")))
                      lignes := [{:genre "female" :n 2} {:genre "male" :n 1}]))
                  (testing "CSV avec puis sans en-tête, JSON"
                    (given @(handler (assoc (requete db (library "SELECT id FROM patient ORDER BY id")) ::sortie/format "csv"))
                      ::sortie/content-type := "text/csv"
                      ::sortie/raw := "id\r\np1\r\np2\r\np3\r\n")
                    (given @(handler (assoc (requete db (library "SELECT id FROM patient ORDER BY id") {:name "header" :valueBoolean false}) ::sortie/format "csv"))
                      ::sortie/raw := "p1\r\np2\r\np3\r\n")
                    (given @(handler (assoc (requete db (library "SELECT id FROM patient ORDER BY id")) ::sortie/format "json"))
                      ::sortie/content-type := "application/json"
                      #(count (j/read-value (::sortie/raw %))) := 3))
                  (testing "_limit"
                    (is (= 2 (count (lignes @(handler (requete db (library "SELECT id FROM patient") {:name "_limit" :valueInteger 2}))))))))))

(defn- erreur
  "Anomalie de l'échec du handler, avec ses clés."
  [handler requete]
  (let [f (handler requete)]
    (try @f nil (catch java.util.concurrent.ExecutionException e (ex-data (.getCause e))))))

(deftest library-rangee
  (let [lib (assoc (library "SELECT count(*) AS n FROM patient") :id "l1")]
    (avec-handler config (conj trois [[:put (lire lib)]])
                  (fn [handler db _]
                    (testing "queryReference : la Library rangée est servie"
                      (given @(handler {:blaze/db db :body {:resourceType "Parameters"
                                                            :parameter [{:name "queryReference" :valueReference {:reference "Library/l1"}}]}})
                        :status := 200
                        lignes := [{:n 3}]))
                    (testing "Library absente : 404"
                      (given (erreur handler {:blaze/db db :body {:resourceType "Parameters"
                                                                  :parameter [{:name "queryReference" :valueReference {:reference "Library/absente"}}]}})
                        ::anom/category := ::anom/not-found
                        :http/status := 404))
                    (testing "référence de forme inconnue : 400"
                      (given (erreur handler {:blaze/db db :body {:resourceType "Parameters"
                                                                  :parameter [{:name "queryReference" :valueReference {:reference "Patient/p1"}}]}})
                        :http/status := 400))))))

(deftest refus
  (avec-handler config trois
                (fn [handler db node]
                  (let [avant (compte node "Patient")]
                    (testing "400 : corps, paramètre manquant, inconnu, mal typé"
                      (given (erreur handler {:blaze/db db :body {:resourceType "Parameters"}})
                        :http/status := 400 :fhir/issue := "required")
                      (let [decl [{:name "genre" :use "in" :type "string"}]
                            sql "SELECT id FROM patient WHERE genre = :genre"]
                        (given (erreur handler (requete db (library sql decl)))
                          :http/status := 400)
                        (given (erreur handler (requete db (library sql decl) {:name "parameters" :resource {:resourceType "Parameters" :parameter [{:name "genre" :valueString "female"} {:name "zzz" :valueString "x"}]}}))
                          :http/status := 400)
                        (given (erreur handler (requete db (library "SELECT id FROM patient WHERE length(id) > :n" [{:name "n" :use "in" :type "integer"}])
                                                        {:name "parameters" :resource {:resourceType "Parameters" :parameter [{:name "n" :valueString "abc"}]}}))
                          :http/status := 400)))
                    (testing "404 : vue introuvable"
                      (given (erreur handler (requete db (assoc-in (library "SELECT * FROM patient") [:relatedArtifact 0 :resource] "http://example.org/absente|1.0.0")))
                        ::anom/category := ::anom/not-found
                        :http/status := 404))
                    (testing "422 : SQL fausse, colonne ou table inconnue, dialecte autre, vue refusée"
                      (doseq [sql ["SELEC id FROM patient" "SELECT zzz FROM patient" "SELECT * FROM table_inconnue"]]
                        (given (erreur handler (requete db (library sql)))
                          ::anom/category := ::anom/incorrect
                          :http/status := 422))
                      (given (erreur handler (requete db (assoc-in (library "SELECT 1") [:content 0 :contentType] "application/sql;dialect=oracle")))
                        :http/status := 422))
                    (testing "lecture seule : création, modification, suppression, fichier, réseau, extension refusés, 0 effet"
                      (doseq [sql ["DROP TABLE patient" "CREATE TABLE z (i INT)" "DELETE FROM patient" "INSERT INTO patient VALUES ('x', 'y')"
                                   "SELECT 1; DROP TABLE patient" "COPY patient TO '/tmp/t116-sortie.csv'"
                                   "INSTALL httpfs" "LOAD httpfs" "ATTACH '/tmp/t116.db'" "SET memory_limit = '100GB'"]]
                        (testing sql
                          (given (erreur handler (requete db (library sql)))
                            ::anom/category := ::anom/incorrect
                            :http/status := 422
                            [::anom/message #(subs % 0 (min 28 (count %)))] := "Only a SELECT query is allow")))
                      (doseq [[sql message] [["SELECT * FROM read_csv('/etc/passwd')" "Permission Error"]
                                             ["SELECT * FROM read_csv('http://127.0.0.1:9/x')" "Permission Error"]]]
                        (testing sql
                          (given (erreur handler (requete db (library sql)))
                            ::anom/category := ::anom/incorrect
                            :http/status := 422
                            [::anom/message #(subs % 0 (min 16 (count %)))] := message)))
                      (is (= avant (compte node "Patient")))
                      (is (not (.exists (java.io.File. "/tmp/t116-sortie.csv"))))
                      (is (not (.exists (java.io.File. "/tmp/t116.db"))))
                      (given @(handler (requete db (library "SELECT count(*) AS n FROM patient")))
                        lignes := [{:n 3}]))))))

(deftest parametre-lie
  (avec-handler config trois
                (fn [handler db node]
                  (let [sql "SELECT id FROM patient WHERE genre = :genre"
                        decl [{:name "genre" :use "in" :type "string"}]
                        valeur (fn [v] {:name "parameters" :resource {:resourceType "Parameters" :parameter [{:name "genre" :valueString v}]}})]
                    (testing "valeur normale"
                      (given @(handler (requete db (library sql decl) (valeur "male")))
                        lignes := [{:id "p2"}]))
                    (testing "valeur portant du SQL : 0 ligne, comme le littéral ; les données sont intactes"
                      (doseq [w ["'); DROP TABLE patient; --" "x' OR '1'='1"]]
                        (given @(handler (requete db (library sql decl) (valeur w)))
                          :status := 200
                          lignes := [])
                        (given @(handler (requete db (library (str "SELECT id FROM patient WHERE genre = '" (str/replace w "'" "''") "'"))))
                          lignes := []))
                      (is (= 3 (compte node "Patient")))
                      (given @(handler (requete db (library "SELECT count(*) AS n FROM patient")))
                        lignes := [{:n 3}]))))))

(deftest plafond-de-lignes
  (avec-handler (assoc config :max-rows 3) trois
                (fn [handler db _]
                  (testing "exactement le plafond : servi"
                    (is (= 3 (count (lignes @(handler (requete db (library "SELECT id FROM patient"))))))))
                  (testing "plafond + 1 : 422 too-costly, comme $viewdefinition-run"
                    (given (erreur handler (requete db (library "SELECT i FROM range(4) t(i)")))
                      ::anom/category := ::anom/incorrect
                      :http/status := 422
                      :fhir/issue := "too-costly"))
                  (testing "_limit = plafond sur plafond + 1 : servi"
                    (is (= 3 (count (lignes @(handler (requete db (library "SELECT i AS id FROM range(4) t(i)") {:name "_limit" :valueInteger 3}))))))))))

(def ^:private lourde "SELECT sum(i * j) AS s FROM range(100000) t(i), range(100000) u(j)")

(deftest plafond-de-duree
  (avec-handler (assoc config :timeout-seconds 1) trois
                (fn [handler db _]
                  (let [t0 (System/nanoTime)
                        e (erreur handler (requete db (library lourde)))
                        ms (quot (- (System/nanoTime) t0) 1000000)]
                    (given e
                      ::anom/category := ::anom/incorrect
                      :http/status := 422
                      :fhir/issue := "timeout")
                    (is (< 900 ms 5000) (str ms " ms")))
                  (testing "le handler sert encore (le permis est rendu)"
                    (given @(handler (requete db (library "SELECT count(*) AS n FROM patient")))
                      lignes := [{:n 3}])))))

(defn- chargement-lent
  "`ds/resources` dont chaque ressource prend `ms` millisecondes à lire (chargement des vues lent)."
  [ms]
  (let [resources ds/resources]
    (fn [& args]
      (eduction (map (fn [r] (Thread/sleep (long ms)) r)) (apply resources args)))))

(deftest plafond-de-duree-chargement
  (avec-handler (assoc config :timeout-seconds 1) trois
                (fn [handler db _]
                  (testing "chargement des vues seul plus long que le plafond : 422 timeout"
                    (with-redefs [ds/resources (chargement-lent 500)]
                      (let [t0 (System/nanoTime)
                            e (erreur handler (requete db (library "SELECT count(*) AS n FROM patient")))
                            ms (quot (- (System/nanoTime) t0) 1000000)]
                        (given e
                          ::anom/category := ::anom/incorrect
                          :http/status := 422
                          :fhir/issue := "timeout")
                        (is (< ms 3000) (str ms " ms")))))
                  (testing "le handler sert encore (le permis est rendu)"
                    (given @(handler (requete db (library "SELECT count(*) AS n FROM patient")))
                      lignes := [{:n 3}])))))

(deftest plafond-de-duree-total
  (testing "le plafond couvre chargement + exécution : l'exécution n'a que le temps restant"
    (avec-handler (assoc config :timeout-seconds 2) trois
                  (fn [handler db _]
                    (with-redefs [ds/resources (chargement-lent 500)]
                      (let [t0 (System/nanoTime)
                            e (erreur handler (requete db (library lourde)))
                            ms (quot (- (System/nanoTime) t0) 1000000)]
                        (given e
                          :fhir/issue := "timeout")
                        (is (< ms 3000) (str ms " ms"))))))))

(deftest plafond-de-memoire
  (avec-handler (assoc config :max-memory "32MiB" :timeout-seconds 30) trois
                (fn [handler db _]
                  (given (erreur handler (requete db (library "SELECT i AS id FROM range(50000000) t(i) ORDER BY i DESC")))
                    ::anom/category := ::anom/incorrect
                    :http/status := 422
                    :fhir/issue := "too-costly")
                  (given @(handler (requete db (library "SELECT count(*) AS n FROM patient")))
                    lignes := [{:n 3}]))))

(deftest plafond-de-concurrence
  (avec-handler (assoc config :max-concurrent 1 :timeout-seconds 30) trois
                (fn [handler db _]
                  (let [fond (future @(handler (requete db (library lourde))))]
                    (Thread/sleep 700)
                    (is (not (realized? fond)) "essai probant : la requête de fond tourne encore")
                    (let [t0 (System/nanoTime)
                          e (erreur handler (requete db (library "SELECT 1 AS i")))
                          ms (quot (- (System/nanoTime) t0) 1000000)]
                      (given e
                        ::anom/category := ::anom/busy
                        :fhir/issue := "throttled")
                      (is (< ms 500) (str "refus sur-le-champ : " ms " ms")))
                    (is (= 200 (:status @fond)))
                    (testing "le permis est rendu : la suivante est servie"
                      (let [r (ba/try-anomaly (ac/join (handler (requete db (library "SELECT 1 AS i")))))]
                        (is (not (ba/anomaly? r)) (pr-str r))
                        (given r lignes := [{:i 1}])))))))
