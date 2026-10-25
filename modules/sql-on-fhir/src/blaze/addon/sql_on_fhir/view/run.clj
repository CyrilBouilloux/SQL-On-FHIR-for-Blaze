(ns blaze.addon.sql-on-fhir.view.run
  "Exécution d'une vue compilée sur des ressources (VE-04…VE-12, VE-14, VE-16, VE-18, VE-19) : fonction
  pure, sans base ni HTTP. Les lignes sont des maps de valeurs simples (chaîne, nombre, booléen,
  `nil`, vecteur), types Blaze déballés ici, colonnes dans l'ordre de la vue. Toute erreur de
  ressource ou d'expression sort en anomalie."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.variable :as variable]
   [blaze.anomaly :as ba :refer [throw-anom]]
   [blaze.fhir-path :as fp]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.spec.type.system :as system]))

(set! *warn-on-reflection* true)

(defn- items [coll]
  (reduce conj [] coll))

(defn- unwrap
  "Valeur FHIRPath primitive de l'élément `x` (type Blaze déballé), ou nil si `x` est complexe."
  [x]
  (let [v (cond-> x (fhir-spec/primitive-val? x) :value)]
    (cond
      (or (string? v) (number? v) (boolean? v)) v
      (some? (system/type v)) (str v))))

(defn- column-value [context coll {:keys [name expr collection]}]
  (let [xs (items (fp/-eval expr context coll))
        plain (fn [x]
                (let [v (unwrap x)]
                  (if (nil? v)
                    (throw-anom (ba/incorrect (format "column `%s` is not a primitive value" name)))
                    v)))]
    (cond
      collection (mapv plain xs)
      (empty? xs) nil
      (next xs) (throw-anom (ba/incorrect (format "column `%s` has %d values but `collection` is not true"
                                                  name (count xs))))
      :else (plain (first xs)))))

(defn- cross [rows-a rows-b]
  (for [a rows-a b rows-b] (into a b)))

(def ^:private max-repeat-depth 1000)

(defn- repeat-items
  "Éléments atteints depuis `focus` en suivant récursivement `paths`, en profondeur d'abord, la
  racine exclue (VE-11). Un élément identique à l'un de ses ancêtres (cycle) n'est pas suivi."
  [context paths focus]
  (letfn [(walk [ancestors depth item]
            (when (< max-repeat-depth depth)
              (throw-anom (ba/incorrect "`repeat` does not terminate")))
            (mapcat (fn [child]
                      (when-not (some #(identical? child %) ancestors)
                        (cons child (walk (cons child ancestors) (inc depth) child))))
                    (mapcat #(items (fp/-eval % context [item])) paths)))]
    (doall (mapcat #(walk (list %) 0 %) focus))))

(defn- iteration-items
  "Éléments que parcourt `node` depuis `focus`, ou nil s'il n'itère pas ; `:or-null` pour un
  `forEachOrNull` vide, qui rend une ligne à valeurs nulles."
  [context {:keys [for-each for-each-or-null] repeat-paths :repeat} focus]
  (cond
    for-each (items (fp/-eval for-each context focus))
    for-each-or-null (let [xs (items (fp/-eval for-each-or-null context focus))]
                       (if (empty? xs) :or-null xs))
    repeat-paths (repeat-items context repeat-paths focus)))

(declare node-rows)

(defn- body-rows
  "Lignes de `node` sur `focus` : ses colonnes, croisées avec les lignes de chacun de ses `select`
  puis de la concaténation de ses branches `unionAll`."
  [context {:keys [columns selects union-all]} focus]
  (let [own [(mapv #(column-value context focus %) columns)]
        parts (cond-> (mapv #(node-rows context % focus) selects)
                (seq union-all) (conj (mapcat #(node-rows context % focus) union-all)))]
    (reduce cross own parts)))

(defn- node-rows
  "Lignes (vecteurs de valeurs) du `select` compilé `node` sur la collection `focus` ; `%rowIndex`
  est la position de l'élément dans l'itération de `node`, héritée par un `select` sans itération."
  [context node focus]
  (let [xs (iteration-items context node focus)]
    (cond
      (nil? xs) (body-rows context node focus)
      (= :or-null xs) (body-rows (assoc context ::variable/row-index 0) node [])
      :else (mapcat (fn [i x] (body-rows (assoc context ::variable/row-index i) node [x]))
                    (range) xs))))

(defn- retained?
  "Vrai si la ressource satisfait la clause `where` : vide = faux, un booléen, sinon `incorrect`."
  [context resource {:keys [path expr]}]
  (let [xs (items (fp/-eval expr context [resource]))
        v (when (= 1 (count xs)) (unwrap (first xs)))]
    (cond
      (empty? xs) false
      (boolean? v) v
      :else (throw-anom (ba/incorrect (format "`where` path `%s` does not resolve to a boolean" path))))))

(defn- row [names values]
  (apply array-map (interleave names values)))

(defn- resource-rows [{:keys [where select schema]} context resource]
  (when (every? #(retained? context resource %) where)
    (let [names (map (comp keyword :name) schema)]
      (map #(row names %)
           (reduce cross [[]] (map #(node-rows context % [resource]) select))))))

(defn- resource-type [resource]
  (if-some [type (:fhir/type resource)]
    (name type)
    (throw-anom (ba/incorrect "not a FHIR resource"))))

(defn run
  "Lignes de la vue compilée `view` sur `resources` : un vecteur de maps, ou une anomalie. Les
  ressources d'un autre type que `resource` de la vue sont ignorées."
  [{:keys [resource constants] :as view} resources]
  (let [context {:resolver nil :constants constants}]
    (ba/try-anomaly
     (into []
           (comp (filter #(= resource (resource-type %)))
                 (mapcat #(resource-rows view context %)))
           resources))))
