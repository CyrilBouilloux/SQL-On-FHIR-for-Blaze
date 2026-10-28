(ns blaze.addon.sql-on-fhir.sql.engine
  "SQ-03 (F-039, ADR 0014) : exécution d'une requête SQL sur une base DuckDB en mémoire, sans
  fichier, sans réseau, en lecture seule. Chaque levier a été mesuré par T-112 (ADR 0014, « Leviers
  et témoins ») ; pas d'analyseur SQL : le contrôle de lecture seule est confié à `json_serialize_sql`
  de DuckDB ; seule la substitution des paramètres nommés est une colle par expression régulière
  (`sql/query.clj`) ; les valeurs sont liées (`?`), jamais interpolées.

  Les erreurs du moteur sont des anomalies `incorrect` 422 (Q3 : erreur d'exécution SQL) ; la durée et
  la mémoire dépassées portent `timeout` et `too-costly`."
  (:require
   [blaze.anomaly :as ba]
   [clojure.string :as str])
  (:import
   [java.sql Array Connection PreparedStatement ResultSet SQLException SQLTimeoutException]))

(set! *warn-on-reflection* true)

(defn memory-size?
  "Vrai si `s` est une taille du moteur : entier positif et unité (`256MiB`, `1GB`…)."
  [s]
  (and (string? s) (boolean (re-matches #"[1-9][0-9]*\s?[KMGT]i?B" s))))

(defn- execute! [^Connection conn ^String sql]
  (with-open [st (.createStatement conn)] (.execute st sql)))

(defn- first-line [^SQLException e]
  (first (str/split-lines (or (.getMessage e) "SQL error"))))

(defn timeout
  "L'anomalie 422 `timeout` : la requête a dépassé le plafond de durée du serveur."
  []
  (ba/incorrect "The query was interrupted: it exceeded the time limit of this server."
                :http/status 422 :fhir/issue "timeout"))

(defn- sql-anomaly [^SQLException e]
  (let [msg (or (.getMessage e) "")]
    (cond
      (or (instance? SQLTimeoutException e) (str/includes? msg "INTERRUPT Error"))
      (timeout)

      (str/includes? msg "Out of Memory Error")
      (ba/incorrect "The query exceeds the memory limit of this server."
                    :http/status 422 :fhir/issue "too-costly")

      :else (ba/incorrect (first-line e) :http/status 422))))

(defn- attempt
  "Valeur de `(f)`, ou l'anomalie du moteur (422) ; toute autre exception : anomalie `fault`."
  [f]
  (ba/try-anomaly (try (f) (catch SQLException e (sql-anomaly e)))))

(defn configure!
  "Règle la base `conn` (neuve) dans l'ordre qui tient, mesuré par T-112 : `temp_directory = ''` (pas de
  débordement sur disque), `memory_limit`, `enable_external_access = false` (ni fichier, ni réseau,
  ni extension), `lock_configuration = true` (la requête ne peut plus rien changer). `temp_directory`
  doit précéder l'accès externe, qui le verrouille. Rend nil, ou une anomalie."
  [^Connection conn max-memory]
  (if (memory-size? max-memory)
    (attempt #(run! (partial execute! conn)
                    ["SET temp_directory = ''"
                     (str "SET memory_limit = '" max-memory "'")
                     "SET enable_external_access = false"
                     "SET lock_configuration = true"]))
    (ba/incorrect (format "`%s` is not a memory size, e.g. `256MiB`." max-memory))))

(defn check-select!
  "Garde lecture seule par la brique : le moteur analyse `sql` (`json_serialize_sql`), qui ne sérialise
  que des `SELECT`. Rend nil si `sql` n'est que `SELECT` (une faute de syntaxe ou toute autre
  instruction est refusée), sinon l'anomalie 422 ; une réponse que l'on ne sait pas lire est une
  `fault`, jamais un accord."
  [^Connection conn sql]
  (attempt
   #(with-open [ps (.prepareStatement conn "SELECT j ->> 'error', j ->> 'error_message' FROM (SELECT json_serialize_sql(?) AS j)")]
      (.setString ps 1 sql)
      (with-open [rs (.executeQuery ps)]
        (if (.next rs)
          (case (.getString rs 1)
            "false" nil
            "true" (ba/incorrect (str "Only a SELECT query is allowed: " (.getString rs 2)) :http/status 422)
            (ba/fault "The SQL engine did not state whether the query is a SELECT."))
          (ba/fault "The SQL engine returned no answer for the query check."))))))

(defn- bind! [^PreparedStatement ps i {:keys [type value]}]
  (case type
    "integer" (.setLong ps (int i) (long value))
    "boolean" (.setBoolean ps (int i) (boolean value))
    "decimal" (.setDouble ps (int i) (double value))
    (.setString ps (int i) (str value))))

(defn- plain
  "Valeur JSON simple de la valeur JDBC `x` : liste en vecteur, tout ce qui n'est pas simple en texte."
  [x]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x)) x
    (instance? Array x) (mapv plain (.getArray ^Array x))
    :else (str x)))

(defn- unique
  "Noms de colonnes `labels` rendus distincts : un nom répété reçoit `_1`, `_2`…"
  [labels]
  (first (reduce (fn [[names seen] label]
                   (let [name (if (seen label)
                                (first (remove seen (map #(str label "_" %) (iterate inc 1))))
                                label)]
                     [(conj names name) (conj seen name)]))
                 [[] #{}]
                 labels)))

(defn- read-result [^ResultSet rs max-rows]
  (let [md (.getMetaData rs)
        n (.getColumnCount md)
        columns (unique (map #(.getColumnLabel md (int %)) (range 1 (inc n))))
        keys (mapv keyword columns)]
    {:columns (vec columns)
     :rows (loop [rows []]
             (if (and (< (count rows) max-rows) (.next rs))
               (recur (conj rows (apply array-map (mapcat (fn [i k] [k (plain (.getObject rs (int i)))])
                                                          (range 1 (inc n)) keys))))
               rows))}))

(defn query!
  "Exécute le `SELECT` `sql` sur `conn` avec les valeurs liées `params` (`[{:type :value}]`, dans l'ordre
  des `?`), au plus `timeout-seconds` secondes. Rend `{:columns [nom] :rows [map]}` (colonnes dans
  l'ordre, au plus `max-rows` lignes) ou une anomalie 422."
  [^Connection conn sql params {:keys [timeout-seconds max-rows]}]
  (attempt
   #(with-open [ps (.prepareStatement conn ^String sql)]
      (.setQueryTimeout ps (int timeout-seconds))
      (run! (fn [[i p]] (bind! ps (inc i) p)) (map-indexed vector params))
      (with-open [rs (.executeQuery ps)]
        (read-result rs max-rows)))))
