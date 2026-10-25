(ns blaze.addon.sql-on-fhir.view.validate
  "Validation de la définition d'une vue (VE-01, VE-02, VE-15), sans la compiler : structure,
  noms de colonnes, colonnes des branches d'`unionAll`. Rend `nil` ou une anomalie `incorrect`
  nommant le premier défaut. Les expressions FHIRPath sont vérifiées à la compilation."
  (:require
   [blaze.addon.sql-on-fhir.view.schema :as schema]
   [blaze.anomaly :as ba]
   [clojure.string :as str]))

(defn- text? [x]
  (and (string? x) (not (str/blank? x))))

(defn- each-problem
  "Premier défaut des éléments de `xs` (`problem` : élément → message ou nil), ou celui du
  conteneur s'il n'est pas un tableau ; `nil` si `xs` est absent."
  [what problem xs]
  (cond
    (nil? xs) nil
    (not (sequential? xs)) (str what " must be an array")
    :else (some problem xs)))

(defn- column-problem [{:keys [name path type collection] :as column}]
  (cond
    (not (map? column)) "column must be an object"
    (not (and (string? name) (re-matches #"[A-Za-z][A-Za-z0-9_]*" name)))
    (str "invalid column name " (pr-str name))
    (not (text? path)) (str "column `" name "` needs a path")
    (not (or (nil? collection) (boolean? collection)))
    (str "`collection` of column `" name "` must be a boolean")
    (not (or (nil? type) (string? type))) (str "`type` of column `" name "` must be a string")))

(defn- repeat-problem [path]
  (when-not (text? path) "`repeat` must be an array of strings"))

(declare select-problem)

(defn- union-problem [branches]
  (or (each-problem "unionAll" select-problem branches)
      (when (and (sequential? branches)
                 (< 1 (count (distinct (map #(mapv :name (schema/select-schema %)) branches)))))
        "`unionAll` branches must have the same columns in the same order")))

(defn- select-problem [{:keys [forEach forEachOrNull unionAll] :as select}]
  (if-not (map? select)
    "select must be an object"
    (or (each-problem "column" column-problem (:column select))
        (each-problem "select" select-problem (:select select))
        (when-not (or (nil? forEach) (text? forEach)) "`forEach` must be a string")
        (when-not (or (nil? forEachOrNull) (text? forEachOrNull))
          "`forEachOrNull` must be a string")
        (when (contains? select :repeat)
          (each-problem "repeat" repeat-problem (:repeat select)))
        (when (some? unionAll) (union-problem unionAll)))))

(defn- where-problem [clause]
  (when-not (and (map? clause) (text? (:path clause)))
    "`where` clause needs a path"))

(defn- constant-problem [constant]
  (when-not (and (map? constant) (text? (:name constant)))
    "`constant` needs a name"))

(defn- view-problem [{:keys [resource select] :as view}]
  (cond
    (not (map? view)) "view must be an object"
    (not (text? resource)) "view needs a `resource`"
    (not (and (sequential? select) (seq select))) "view needs a non-empty `select` array"
    :else (or (some select-problem select)
              (each-problem "where" where-problem (:where view))
              (each-problem "constant" constant-problem (:constant view))
              (let [names (map :name (schema/schema view))]
                (when-not (= (count names) (count (set names)))
                  (str "duplicate column name in " (pr-str (vec names))))))))

(defn validate
  "Nil si `view` est bien formée, sinon l'anomalie `incorrect` décrivant le premier défaut."
  [view]
  (some-> (view-problem view) ba/incorrect))
