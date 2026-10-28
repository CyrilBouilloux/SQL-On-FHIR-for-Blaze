(ns blaze.addon.sql-on-fhir.sql.tables
  "Vues exposées comme tables d'une base DuckDB en mémoire (SQ-01, F-039, ADR 0014) : les lignes de
  chaque vue sont calculées par le moteur de vue (`view.run/run`, F-005), puis chargées dans une
  table nommée par l'appelant, le temps d'une requête (une base par requête, fermée par l'appelant).

  Aucune interpolation : un nom de table ou de colonne n'entre dans le SQL que comme identifiant
  entre guillemets (`quote-identifier`), une valeur que liée (`?`). Tout est calculé et vérifié avant
  la première écriture ; un refus ne crée aucune table (§3.6 règle 4)."
  (:require
   [blaze.addon.sql-on-fhir.view.run :as run]
   [blaze.anomaly :as ba :refer [if-ok]]
   [clojure.string :as str])
  (:import
   [java.sql Connection DriverManager PreparedStatement]))

(set! *warn-on-reflection* true)

(def ^:private batch-size 1000)

(defn sql-type
  "Type SQL de la colonne de type FHIR (ou FHIRPath `System.*`) `type` : booléen, entier, entier 64
  bits, décimal (DOUBLE : *décidé, non mesuré* pour la précision), sinon texte (dates comprises : une
  date FHIR peut être partielle, `1970-06`). `nil` si la colonne n'a pas de type."
  [type]
  (when type
    (case (str/lower-case (peek (str/split type #"[./]")))
      "boolean" "BOOLEAN"
      ("integer" "positiveint" "unsignedint") "INTEGER"
      "integer64" "BIGINT"
      "decimal" "DOUBLE"
      "VARCHAR")))

(defn quote-identifier
  "Identifiant SQL : `s` entre guillemets, guillemets doublés."
  [s]
  (str \" (str/replace s "\"" "\"\"") \"))

(defn open-memory-db!
  "Connexion à une base DuckDB neuve, en mémoire, qui ne partage rien avec une autre. L'appelant la
  ferme (`with-open`)."
  ^Connection []
  (DriverManager/getConnection "jdbc:duckdb:"))

(defn- infer
  "Type SQL d'après les valeurs non nulles `vs` : booléen, entier, décimal, sinon texte."
  [vs]
  (cond
    (empty? vs) "VARCHAR"
    (every? boolean? vs) "BOOLEAN"
    (every? int? vs) "BIGINT"
    (every? number? vs) "DOUBLE"
    :else "VARCHAR"))

(defn- values [rows {:keys [name collection]}]
  (cond->> (into [] (comp (map #(get % (keyword name))) (remove nil?)) rows)
    collection (into [] cat)))

(defn- column-type
  "Type SQL de la colonne `column` du schéma de la vue : celui qu'elle déclare, sinon celui de ses
  valeurs dans `rows`."
  [rows {:keys [type collection] :as column}]
  (let [t (or (sql-type type) (infer (values rows column)))]
    (cond-> t collection (str "[]"))))

(defn- coerce [sql-type v]
  (cond
    (nil? v) nil
    (= "VARCHAR" sql-type) (str v)
    (= "DOUBLE" sql-type) (double v)
    :else v))

(defn- create-sql [table types columns]
  (str "CREATE TABLE " (quote-identifier table) " ("
       (str/join ", " (map (fn [{:keys [name]} t] (str (quote-identifier name) " " t)) columns types))
       ")"))

(defn- insert-sql [table n]
  (str "INSERT INTO " (quote-identifier table) " VALUES (" (str/join ", " (repeat n "?")) ")"))

(defn- binder
  "Fonction `(f conn ps i v)` qui lie la valeur `v` au paramètre `i` de `ps`, d'après la colonne."
  [{:keys [collection]} sql-type]
  (if collection
    (let [element (subs sql-type 0 (- (count sql-type) 2))]
      (fn [^Connection conn ^PreparedStatement ps i v]
        (.setObject ps (int i) (.createArrayOf conn element (object-array (map #(coerce element %) v))))))
    (fn [_conn ^PreparedStatement ps i v]
      (.setObject ps (int i) (coerce sql-type v)))))

(defn- insert!
  "Insère les lignes de la table en lots de `batch-size`."
  [^Connection conn {:keys [name rows columns types]}]
  (let [binders (mapv binder columns types)
        keys (mapv (comp keyword :name) columns)]
    (with-open [ps (.prepareStatement conn ^String (insert-sql name (count columns)))]
      (run! (fn [part]
              (run! (fn [row]
                      (dorun (map-indexed (fn [i bind] (bind conn ps (inc i) (get row (nth keys i)))) binders))
                      (.addBatch ps))
                    part)
              (.executeBatch ps))
            (partition-all batch-size rows)))))

(defn- plan
  "Table prête à charger `{:name :rows :columns :types}`, ou une anomalie (vue en erreur, nom vide)."
  [{:keys [name view resources]}]
  (if (str/blank? name)
    (ba/incorrect "A table name must not be blank.")
    (if-ok [rows (run/run view resources)]
      (let [columns (:schema view)]
        {:name name :rows rows :columns columns :types (mapv #(column-type rows %) columns)})
      identity)))

(defn load-tables!
  "Crée dans `conn` une table par élément de `tables` (`{:name :view :resources}` : nom de la table,
  vue compilée, ressources) et la remplit des lignes de la vue. Rend `nil`, ou une anomalie :
  `incorrect` pour un nom vide ou en double (casse ignorée), celle du moteur de vue pour une vue en
  erreur, `fault` si la base refuse. Aucune table n'est créée tant que toutes ne sont pas prêtes."
  [^Connection conn tables]
  (let [plans (mapv plan tables)
        names (map (comp str/lower-case :name) plans)]
    (cond
      (some ba/anomaly? plans) (first (filter ba/anomaly? plans))
      (not= (count names) (count (set names)))
      (ba/incorrect "Two tables have the same name.")
      :else
      (ba/try-anomaly
       (with-open [st (.createStatement conn)]
         (run! (fn [{:keys [name columns types] :as p}]
                 (.execute st ^String (create-sql name types columns))
                 (insert! conn p))
               plans))))))

(defn columns
  "Colonnes `{:name :type}` de la table `table` de `conn`, dans l'ordre, type du moteur (`VARCHAR`,
  `VARCHAR[]`, `BOOLEAN`…) ; vecteur vide si la table n'existe pas."
  [^Connection conn table]
  (with-open [ps (.prepareStatement conn "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position")]
    (.setString ps 1 table)
    (with-open [rs (.executeQuery ps)]
      (loop [acc []]
        (if (.next rs)
          (recur (conj acc {:name (.getString rs 1) :type (.getString rs 2)}))
          acc)))))
