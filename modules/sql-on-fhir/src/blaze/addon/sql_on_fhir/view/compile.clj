(ns blaze.addon.sql-on-fhir.view.compile
  "Compilation d'une vue (VE-03, VE-13) : validation, constantes, puis chaque expression FHIRPath
  compilée **une fois** par `blaze.fhir-path/compile` (appelé par sa var), jamais par ressource.
  Rend la vue compilée ou une anomalie : `incorrect` (vue ou expression illisible, constante non
  déclarée, colonnes d'`unionAll` différentes), `unsupported` (construction FHIRPath non gérée)."
  (:refer-clojure :exclude [compile])
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.constant :as constant]
   ;; les extensions FHIRPath du module (F-004), chargées avec le moteur de vue
   [blaze.addon.sql-on-fhir.fhir-path.function]
   [blaze.addon.sql-on-fhir.fhir-path.operator]
   [blaze.addon.sql-on-fhir.fhir-path.unsupported]
   [blaze.addon.sql-on-fhir.fhir-path.variable]
   [blaze.addon.sql-on-fhir.view.schema :as schema]
   [blaze.addon.sql-on-fhir.view.validate :as validate]
   [blaze.anomaly :as ba :refer [throw-anom]]
   [blaze.fhir-path :as fp]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]))

(set! *warn-on-reflection* true)

(defn- referenced-constants
  "Noms des constantes `%nom`, `%'nom'` ou `%`nom`` que `path` emploie, hors chaînes littérales."
  [path]
  (->> (re-seq #"%(?:([A-Za-z_][A-Za-z0-9_]*)|'([^']*)'|`([^`]*)`)"
               (str/replace path #"'(?:[^'\\]|\\.)*'(?!\w)" "''"))
       (map #(some identity (rest %)))))

(defn- compile-path
  "Expression compilée de `path`, ou lève l'anomalie : constante non déclarée ou expression
  illisible (`incorrect`, le `fault` du jar pour une erreur de syntaxe en devient un)."
  [constants path]
  (when-some [undefined (first (remove #(or (contains? constants %) (= "rowIndex" %))
                                       (referenced-constants path)))]
    (throw-anom (ba/incorrect (format "undefined constant `%%%s` in `%s`" undefined path))))
  (let [expr (fp/compile path)]
    (if (ba/anomaly? expr)
      (throw-anom (cond-> expr
                    (= ::anom/fault (::anom/category expr))
                    (assoc ::anom/category ::anom/incorrect)))
      expr)))

(defn- compile-select [constants select]
  (cond-> {:columns (mapv (fn [{:keys [name path type collection]}]
                            {:name name :type type :collection (true? collection)
                             :expr (compile-path constants path)})
                          (:column select))
           :selects (mapv #(compile-select constants %) (:select select))}
    (:forEach select)
    (assoc :for-each (compile-path constants (:forEach select)))
    (:forEachOrNull select)
    (assoc :for-each-or-null (compile-path constants (:forEachOrNull select)))
    (:repeat select)
    (assoc :repeat (mapv #(compile-path constants %) (:repeat select)))
    (:unionAll select)
    (assoc :union-all (mapv #(compile-select constants %) (:unionAll select)))))

(defn compile
  "Vue compilée `{:resource :constants :where :select :schema}` de la définition `view` (clés en
  mots-clés ; `constant` passé tel quel à `fhir-path.constant/constants`, IB-05), ou une anomalie."
  [view]
  (or (validate/validate view)
      (ba/try-anomaly
       (let [constants (let [c (constant/constants (vec (:constant view)))]
                         (if (ba/anomaly? c) (throw-anom c) c))]
         {:resource (:resource view)
          :constants constants
          :where (mapv (fn [{:keys [path]}]
                         {:path path :expr (compile-path constants path)})
                       (:where view))
          :select (mapv #(compile-select constants %) (:select view))
          :schema (schema/schema view)}))))
