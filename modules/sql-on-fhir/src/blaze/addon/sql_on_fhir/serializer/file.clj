(ns blaze.addon.sql-on-fhir.serializer.file
  "SR-05 : écriture des lignes d'une vue dans un `java.io.Writer`, une ligne à la fois. La source
  (`IReduceInit`) et la sortie (`Writer`) sont des arguments : l'une et l'autre s'enveloppent, par
  exemple pour compter lignes tirées et lignes écrites. Chaque ligne est écrite par **un** appel
  `write(String)` ; le cadrage (en-tête CSV, `[` et `]` de JSON) est écrit à part."
  (:require
   [blaze.addon.sql-on-fhir.serializer :as serializer])
  (:import
   [java.io Writer]))

(set! *warn-on-reflection* true)

(defn write-rows!
  "Écrit dans `out` les `rows` (une `IReduceInit` de maps) de la vue de colonnes `schema`, au
  `format` (`json`, `ndjson`, `csv`) ; texte identique à celui de `serializer/serialize`. Une ligne
  n'est tirée de `rows` qu'une fois la précédente écrite : aucune ligne n'est retenue. Rend le
  nombre de lignes écrites ; ne ferme pas `out`."
  [format schema header? rows ^Writer out]
  (let [{:keys [head row tail]} (serializer/encoder format schema header?)]
    (when head (.write out ^String head))
    (let [n (reduce (fn [i r] (.write out ^String (row i r)) (inc i)) 0 rows)]
      (when tail (.write out ^String tail))
      n)))
