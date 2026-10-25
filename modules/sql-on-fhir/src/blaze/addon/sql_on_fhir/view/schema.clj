(ns blaze.addon.sql-on-fhir.view.schema
  "Schéma de sortie d'une vue (VE-17) : nom, type et cardinalité de chaque colonne, dans l'ordre
  des lignes, déduit de la définition seule, donc disponible avant la première ligne.")

(defn- column-schema [{:keys [name type collection]}]
  {:name name :type type :collection (true? collection)})

(defn select-schema
  "Colonnes d'un `select` : les siennes, puis celles de ses `select`, puis celles de la première
  branche de son `unionAll` (les branches ont les mêmes colonnes, validées)."
  [{:keys [column select unionAll]}]
  (-> (mapv column-schema column)
      (into (mapcat select-schema) select)
      (into (some-> unionAll first select-schema))))

(defn schema
  "Colonnes de la vue `view` (définition lue, clés en mots-clés), dans l'ordre des lignes."
  [{:keys [select]}]
  (into [] (mapcat select-schema) select))
