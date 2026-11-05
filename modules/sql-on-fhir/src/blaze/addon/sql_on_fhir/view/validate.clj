(ns blaze.addon.sql-on-fhir.view.validate
  "Validation de la définition d'une vue (VE-01, VE-02, VE-15), sans la compiler : structure,
  noms de colonnes, de vue et de constantes, `status`, `forEach` exclusif de `forEachOrNull`,
  noms et types déclarés des colonnes des branches d'`unionAll`. Rend `nil` ou une anomalie
  `incorrect` nommant le premier défaut. Les expressions FHIRPath sont vérifiées à la compilation."
  (:require
   [blaze.addon.sql-on-fhir.view.schema :as schema]
   [blaze.anomaly :as ba]
   [clojure.string :as str]))

(def ^:private name-pattern #"[A-Za-z][A-Za-z0-9_]*")

(defn- sql-name? [x]
  (and (string? x) (some? (re-matches name-pattern x))))

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
    (not (sql-name? name))
    (str "invalid column name " (pr-str name))
    (not (text? path)) (str "column `" name "` needs a path")
    (not (or (nil? collection) (boolean? collection)))
    (str "`collection` of column `" name "` must be a boolean")
    (not (or (nil? type) (string? type))) (str "`type` of column `" name "` must be a string")))

(defn- repeat-problem [path]
  (when-not (text? path) "`repeat` must be an array of strings"))

(declare select-problem)

(defn- type-conflict
  "Message du premier conflit de types déclarés entre les branches, par position, ou nil. Les
  `type` déclarés de toutes les branches doivent y être identiques ; une position où une branche
  ne déclare pas de `type` n'est pas jugée sur cette branche (inférence facultative). Sans
  branche, rien à comparer (`(apply map vector [])` rendrait un transducteur)."
  [branches]
  (when (seq branches)
    (first
     (for [column (apply map vector (map schema/select-schema branches))
           :let [types (distinct (keep :type column))]
           :when (< 1 (count types))]
       (str "`unionAll` branches declare column `" (:name (first column)) "` with types "
            (str/join " and " (map pr-str types)))))))

(defn- union-problem [branches]
  (or (each-problem "unionAll" select-problem branches)
      (when (and (sequential? branches)
                 (< 1 (count (distinct (map #(mapv :name (schema/select-schema %)) branches)))))
        "`unionAll` branches must have the same columns in the same order")
      (when (sequential? branches) (type-conflict branches))))

(defn- select-problem [{:keys [forEach forEachOrNull unionAll] :as select}]
  (if-not (map? select)
    "select must be an object"
    (or (when (and (some? forEach) (some? forEachOrNull))
          "a select cannot have both `forEach` and `forEachOrNull`")
        (each-problem "column" column-problem (:column select))
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
  (cond
    (not (and (map? constant) (text? (:name constant)))) "`constant` needs a name"
    (not (sql-name? (:name constant)))
    (str "invalid `constant` name " (pr-str (:name constant)))))

(def ^:private publication-status #{"draft" "active" "retired" "unknown"})

(defn- view-problem [{:keys [resource select] :as view}]
  (cond
    (not (map? view)) "view must be an object"
    (not (text? resource)) "view needs a `resource`"
    (not (and (sequential? select) (seq select))) "view needs a non-empty `select` array"
    (and (contains? view :name) (not (sql-name? (:name view))))
    (str "invalid view `name` " (pr-str (:name view)))
    (and (contains? view :status) (not (publication-status (:status view))))
    (str "invalid view `status` " (pr-str (:status view))
         ", expected one of draft, active, retired, unknown")
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

(defn require-status
  "Nil si `view` porte un `status` (1..1 dans la norme) ou n'est pas un objet, sinon l'anomalie
  `incorrect`. Contrôle des opérations seul : le moteur en mémoire tolère l'absence. La valeur
  est jugée par `validate`."
  [view]
  (when (and (map? view) (not (contains? view :status)))
    (ba/incorrect "view needs a `status`")))
