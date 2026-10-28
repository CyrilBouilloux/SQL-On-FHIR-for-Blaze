(ns blaze.addon.sql-on-fhir.sql.query
  "SQ-03 (F-039, ADR 0014) : une Library SQLQuery (IG 2.1.0-pre, Q2) en requête SQL, tables et
  paramètres. Fonctions pures. La Library est lue sous sa forme JSON (clés en mots-clés) comme sous
  celle que rend `blaze.db.api/pull` (primitives enveloppées `{:value …}`), indifféremment.

  Les paramètres nommés (`:nom`) deviennent des `?` positionnels : la valeur n'entre jamais dans le
  texte SQL, le moteur la reçoit liée (Q3, « safely bound »)."
  (:require
   [blaze.anomaly :as ba :refer [if-ok]]
   [clojure.string :as str])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private token
  "Ce qu'il faut sauter dans le SQL (littéral, identifiant, commentaire, `$$`, cast) ou un paramètre
  nommé (groupe 1), jamais précédé d'un caractère d'identifiant, de `:`, `]` ou `)`."
  (re-pattern (str "(?s)'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|--[^\\n]*|/\\*.*?\\*/|\\$\\$.*?\\$\\$|::"
                   "|(?<![A-Za-z0-9_:\\])]):([A-Za-z_][A-Za-z0-9_]*)")))

(defn named->positional
  "`{:sql :names}` : `sql` dont chaque paramètre nommé `:nom` est remplacé par `?`, et les noms dans
  l'ordre d'apparition (un nom répété l'est aussi)."
  [sql]
  {:sql (str/replace sql token (fn [[all name]] (if name "?" all)))
   :names (into [] (keep second) (re-seq token sql))})

(defn- value [x] (cond-> x (map? x) :value))

(defn- unusable [msg] (ba/incorrect msg :http/status 422))

(defn- dialect [content-type]
  (some #(second (re-matches #"\s*dialect\s*=\s*(\S+)\s*" %)) (rest (str/split content-type #";"))))

(defn- query-text
  "Texte SQL du contenu `application/sql` de `library`, ou une anomalie 422."
  [library]
  (if-let [{:keys [contentType data]} (some #(when (some-> (value (:contentType %)) (str/starts-with? "application/sql")) %)
                                            (:content library))]
    (let [dialect (dialect (value contentType))]
      (if (contains? #{nil "duckdb"} dialect)
        (let [text (ba/try-anomaly (String. (.decode (Base64/getDecoder) ^String (value data)) "UTF-8"))]
          (if (or (ba/anomaly? text) (str/blank? text))
            (unusable "The `content` of the Library holds no readable SQL text.")
            text))
        (unusable (format "SQL dialect `%s` is not supported, use `application/sql;dialect=duckdb`." dialect))))
    (unusable "The Library has no `application/sql` content.")))

(defn- tables [library]
  (reduce (fn [acc {:keys [type label resource]}]
            (cond
              (not= "depends-on" (value type)) acc
              (and (string? (value label)) (string? (value resource)))
              (conj acc {:name (value label) :canonical (value resource)})
              :else (reduced (unusable "A `relatedArtifact` of the Library has no `label` or no `resource`."))))
          []
          (:relatedArtifact library)))

(defn- declared
  "Paramètres déclarés `in` de `library` : `[{:name :type}]`, ou l'anomalie 422 d'un paramètre sans nom ou sans type."
  [library]
  (reduce (fn [acc {:keys [name type use]}]
            (cond
              (= "out" (value use)) acc
              (and (not (str/blank? (value name))) (not (str/blank? (value type))))
              (conj acc {:name (value name) :type (value type)})
              :else (reduced (unusable "A `parameter` of the Library has no `name` or no `type`."))))
          []
          (:parameter library)))

(defn read-library
  "`{:sql :tables [{:name :canonical}] :declared [{:name :type}]}` d'une Library SQLQuery : le SQL
  (contenu `application/sql`, dialecte absent ou `duckdb`), une table par `relatedArtifact`
  `depends-on` (nom = `label`, vue = canonique `resource`), les paramètres déclarés `in` ; ou
  l'anomalie 422 de la Library inutilisable."
  [library]
  (if-ok [sql (query-text library)
          tables (tables library)
          declared (declared library)]
    {:sql sql :tables tables :declared declared}
    identity))

(defn- bad [msg] (ba/incorrect msg :http/status 400))

(defn- value-key
  "Clé de la valeur d'un paramètre de type FHIR `type` : `:valueString`, `:valueInteger`…"
  [type]
  (keyword (str "value" (str/upper-case (subs type 0 1)) (subs type 1))))

(def ^:private type-check
  {"integer" integer? "boolean" boolean? "decimal" number?})

(defn- check [{:keys [name type]} p]
  (let [key (when-not (str/blank? type) (value-key type))
        v (get p key)]
    (if (and key ((get type-check type string?) v))
      {:type type :value v}
      (bad (format "Parameter `%s` must have a `%s` of type `%s`." name (or key "value") type)))))

(defn bind-values
  "Valeurs `[{:type :value}]` à lier aux `?` de `names`, d'après les `declared` et le `Parameters`
  `parameters` (ou nil). Ou l'anomalie 400 : nom du SQL non déclaré, valeur d'un paramètre non
  déclaré, répétée, manquante ou d'un autre type que celui déclaré."
  [declared names parameters]
  (let [by-name (group-by :name (:parameter parameters))
        types (into {} (map (juxt :name identity)) declared)
        checked (into {} (map (fn [d] [(:name d) (if-let [ps (get by-name (:name d))]
                                                   (if (next ps)
                                                     (bad (format "Parameter `%s` is given more than once." (:name d)))
                                                     (check d (first ps)))
                                                   (bad (format "Parameter `%s` has no value." (:name d))))]))
                      declared)]
    (or (some #(when-not (types %) (bad (format "Parameter `%s` is not declared by the Library." %)))
              (concat names (keys by-name)))
        (some #(when (ba/anomaly? %) %) (vals checked))
        (mapv checked names))))
