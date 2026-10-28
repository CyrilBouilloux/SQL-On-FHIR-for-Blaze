(ns blaze.addon.sql-on-fhir.sql.tables-test
  "T-115 (SQ-01, F-039) : les vues, calculées par le moteur F-005, deviennent des tables d'une base
  DuckDB en mémoire, le temps d'une requête. Colonnes (nom, type) et lignes comparées à des
  attendus écrits en clair, jamais recalculés par la fonction éprouvée."
  (:require
   [blaze.addon.sql-on-fhir.sql.tables :as tables]
   [blaze.addon.sql-on-fhir.sql.tables-spec]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.compile :as compile]
   [blaze.addon.sql-on-fhir.view.run :as run]
   [blaze.anomaly :as ba]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]])
  (:import
   [java.sql Connection]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private contexte
  (delay (ig/init-key :blaze.fhir/parsing-context
                      {:structure-definition-repo
                       (ig/init-key :blaze.fhir/structure-definition-repo {})})))

(defn- lire [m]
  (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(def ^:private p1
  (lire {:resourceType "Patient" :id "p1" :active true :birthDate "1970-06"
         :multipleBirthInteger 2 :name [{:family "f1" :given ["g1" "g2"]}]}))

(def ^:private p2 (lire {:resourceType "Patient" :id "p2" :active false}))

(def ^:private o1
  (lire {:resourceType "Observation" :id "o1" :status "final" :code {:text "c"}
         :subject {:reference "Patient/p1"} :valueQuantity {:value 1.5}}))

(def ^:private o3
  (lire {:resourceType "Observation" :id "o3" :status "final" :code {:text "c"} :valueInteger 7}))

(def ^:private o2
  (lire {:resourceType "Observation" :id "o2" :status "final" :code {:text "c"}
         :subject {:reference "Patient/p2"} :valueQuantity {:value 2}}))

;; Vues de référence : types déclarés (chaîne, booléen, date, entier, décimal), collection, sans type.
(def ^:private vue-patient
  {:resource "Patient"
   :select [{:column [{:name "id" :path "id" :type "id"}
                      {:name "actif" :path "active" :type "boolean"}
                      {:name "naissance" :path "birthDate" :type "date"}
                      {:name "prenoms" :path "name.given" :collection true :type "string"}
                      {:name "rang" :path "multipleBirth.ofType(integer)" :type "integer"}
                      {:name "sans_type" :path "active"}]}]})

(def ^:private vue-observation
  {:resource "Observation"
   :select [{:column [{:name "id" :path "id" :type "id"}
                      {:name "patient" :path "subject.reference" :type "string"}
                      {:name "valeur" :path "value.ofType(Quantity).value" :type "decimal"}]}]})

(defn- table [nom vue ressources]
  (let [v (compile/compile vue)]
    (assert (not (ba/anomaly? v)) (pr-str v))
    {:name nom :view v :resources ressources}))

(defn- requete
  "Lignes de `sql` sous forme de vecteurs ; une liste SQL devient un vecteur."
  [^Connection conn sql]
  (with-open [st (.createStatement conn) rs (.executeQuery st sql)]
    (let [n (.getColumnCount (.getMetaData rs))]
      (loop [acc []]
        (if (.next rs)
          (recur (conj acc (mapv #(let [v (.getObject rs (int (inc %)))]
                                    (if (instance? java.sql.Array v) (vec (.getArray ^java.sql.Array v)) v))
                                 (range n))))
          acc)))))

(deftest sql-type-test
  (testing "type FHIR → type SQL"
    (doseq [[fhir sql] {"string" "VARCHAR" "id" "VARCHAR" "code" "VARCHAR" "uri" "VARCHAR"
                        "date" "VARCHAR" "dateTime" "VARCHAR" "instant" "VARCHAR" "time" "VARCHAR"
                        "boolean" "BOOLEAN" "integer" "INTEGER" "positiveInt" "INTEGER"
                        "unsignedInt" "INTEGER" "integer64" "BIGINT" "decimal" "DOUBLE"
                        "http://hl7.org/fhirpath/System.Boolean" "BOOLEAN"
                        "http://hl7.org/fhirpath/System.Integer" "INTEGER"
                        "http://hl7.org/fhirpath/System.Decimal" "DOUBLE"
                        "http://hl7.org/fhirpath/System.String" "VARCHAR"}]
      (is (= sql (tables/sql-type fhir)) fhir)))
  (testing "type absent ou inconnu : pas de type"
    (is (nil? (tables/sql-type nil)))
    (is (= "VARCHAR" (tables/sql-type "Quantity")))))

(deftest quote-identifier-test
  (is (= "\"patient\"" (tables/quote-identifier "patient")))
  (is (= "\"a\"\"b\"" (tables/quote-identifier "a\"b")))
  (is (= "\"x\"\"; DROP TABLE patient; --\"" (tables/quote-identifier "x\"; DROP TABLE patient; --"))))

(deftest colonnes-des-tables-test
  (with-open [conn (tables/open-memory-db!)]
    (is (nil? (tables/load-tables! conn [(table "patient" vue-patient [p1 p2])
                                         (table "obs" vue-observation [o1 o2])])))
    (testing "SQ-01 : colonnes (nom, type) de chaque table = colonnes de la ViewDefinition, dans l'ordre"
      (is (= [{:name "id" :type "VARCHAR"} {:name "actif" :type "BOOLEAN"}
              {:name "naissance" :type "VARCHAR"} {:name "prenoms" :type "VARCHAR[]"}
              {:name "rang" :type "INTEGER"} {:name "sans_type" :type "BOOLEAN"}]
             (tables/columns conn "patient")))
      (is (= [{:name "id" :type "VARCHAR"} {:name "patient" :type "VARCHAR"}
              {:name "valeur" :type "DOUBLE"}]
             (tables/columns conn "obs")))
      (is (= (mapv :name (-> vue-patient :select first :column))
             (mapv :name (tables/columns conn "patient")))))
    (testing "même résultat par SQL : DESCRIBE et information_schema.columns"
      (is (= [["id" "VARCHAR"] ["valeur" "DOUBLE"]]
             (requete conn "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'obs' AND column_name IN ('id', 'valeur') ORDER BY column_name")))
      (is (= ["id" "VARCHAR"]
             (subvec (first (requete conn "DESCRIBE patient")) 0 2))))
    (testing "table inconnue : aucune colonne"
      (is (= [] (tables/columns conn "absente"))))))

(deftest lignes-des-tables-test
  (with-open [conn (tables/open-memory-db!)]
    (tables/load-tables! conn [(table "patient" vue-patient [p1 p2])
                               (table "obs" vue-observation [o1 o2])])
    (testing "mêmes lignes que le moteur de vue, valeurs en clair"
      (is (= [["p1" true "1970-06" ["g1" "g2"] 2 true]
              ["p2" false nil [] nil false]]
             (requete conn "SELECT * FROM patient ORDER BY id")))
      (is (= [["o1" "Patient/p1" 1.5] ["o2" "Patient/p2" 2.0]]
             (requete conn "SELECT * FROM obs ORDER BY id"))))
    (testing "le nombre de lignes est celui du moteur de vue"
      (is (= (count (run/run (:view (table "patient" vue-patient [p1 p2])) [p1 p2]))
             (ffirst (requete conn "SELECT count(*) FROM patient")))))
    (testing "jointure entre deux tables"
      (is (= [["p1" 1.5] ["p2" 2.0]]
             (requete conn "SELECT p.id, o.valeur FROM patient p JOIN obs o ON o.patient = 'Patient/' || p.id ORDER BY p.id"))))))

(deftest type-inferre-test
  (testing "colonne sans type : type des valeurs (booléen, entier, décimal, sinon texte)"
    (with-open [conn (tables/open-memory-db!)]
      (tables/load-tables!
       conn [(table "v" {:resource "Observation"
                         :select [{:column [{:name "b" :path "status = 'final'"}
                                            {:name "i" :path "value.ofType(integer)"}
                                            {:name "d" :path "value.ofType(Quantity).value"}
                                            {:name "t" :path "status"}
                                            {:name "vide" :path "code.coding.code"}
                                            {:name "liste" :path "code.coding.code" :collection true}]}]}
                    [o1 o3])])
      (is (= [{:name "b" :type "BOOLEAN"} {:name "i" :type "BIGINT"} {:name "d" :type "DOUBLE"}
              {:name "t" :type "VARCHAR"} {:name "vide" :type "VARCHAR"} {:name "liste" :type "VARCHAR[]"}]
             (tables/columns conn "v"))))))

(deftest base-par-requete-test
  (with-open [a (tables/open-memory-db!)
              b (tables/open-memory-db!)]
    (tables/load-tables! a [(table "patient" vue-patient [p1])])
    (is (= 6 (count (tables/columns a "patient"))))
    (is (= [] (tables/columns b "patient")) "une base fraîche ne porte aucune table")))

(deftest noms-hostiles-test
  (testing "un nom de table est un identifiant, jamais du SQL (les noms de colonne sont déjà bornés par la validation de la vue)"
    (with-open [conn (tables/open-memory-db!)]
      (tables/load-tables!
       conn [(table "patient" vue-patient [p1])
             (table "x\"; DROP TABLE patient; --"
                    {:resource "Patient"
                     :select [{:column [{:name "c" :path "id"}]}]}
                    [p1])])
      (is (= 1 (ffirst (requete conn "SELECT count(*) FROM patient"))))
      (is (= [{:name "c" :type "VARCHAR"}]
             (tables/columns conn "x\"; DROP TABLE patient; --"))))))

(deftest refus-sans-ecriture-test
  (with-open [conn (tables/open-memory-db!)]
    (testing "nom vide ou blanc : incorrect"
      (given (tables/load-tables! conn [(table "" vue-patient [p1])])
        ::anom/category := ::anom/incorrect))
    (testing "nom en double, casse comprise : incorrect, et la première table n'est pas créée"
      (given (tables/load-tables! conn [(table "patient" vue-patient [p1])
                                        (table "PATIENT" vue-observation [o1])])
        ::anom/category := ::anom/incorrect)
      (is (= [] (tables/columns conn "patient"))))
    (testing "table déjà présente dans la base : fault"
      (is (nil? (tables/load-tables! conn [(table "t" vue-patient [p1])])))
      (given (tables/load-tables! conn [(table "t" vue-patient [p1])])
        ::anom/category := ::anom/fault))
    (testing "vue en erreur : l'anomalie du moteur de vue, aucune table créée"
      (given (tables/load-tables!
              conn [(table "patient" vue-patient [p1])
                    (table "bad" {:resource "Patient"
                                  :select [{:column [{:name "n" :path "name"}]}]} [p1])])
        ::anom/category := ::anom/incorrect)
      (is (= [] (tables/columns conn "patient"))))))
