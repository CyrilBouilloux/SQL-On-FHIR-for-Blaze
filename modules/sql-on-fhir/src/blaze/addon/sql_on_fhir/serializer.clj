(ns blaze.addon.sql-on-fhir.serializer
  "SR-01 (NDJSON), SR-02 (JSON), SR-03 (CSV, RFC 4180) : les lignes d'une vue (maps à clés en
  mots-clés) en texte. Fonctions pures ; le corps est une chaîne, bornée par le plafond de lignes."
  (:require
   [clojure.string :as str]
   [jsonista.core :as j]))

(defn- csv-field [v]
  (let [s (cond (nil? v) "" (string? v) v :else (j/write-value-as-string v))]
    (if (re-find #"[\",\r\n]" s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn- csv-line [values]
  (str (str/join "," (map csv-field values)) "\r\n"))

(defn encoder
  "Codage des lignes d'une vue de colonnes `schema` au `format` (`json`, `ndjson` ou `csv`) :
  `{:head :row :tail :content-type}`. `head` et `tail` (chaînes ou `nil`) encadrent les lignes ;
  `(row i ligne)` est le texte de la ligne `i` (de rang 0), un seul morceau. `header?` n'agit
  que sur `csv`. Fonction pure ; `serialize` et l'écriture au fil de l'eau (`serializer.file`)
  en partagent le texte."
  [format schema header?]
  (let [names (map (comp keyword :name) schema)]
    (case format
      "ndjson" {:row (fn [_ row] (str (j/write-value-as-string row) "\n"))
                :content-type "application/x-ndjson"}
      "json" {:head "[" :tail "]"
              :row (fn [i row] (str (when (pos? i) ",") (j/write-value-as-string row)))
              :content-type "application/json"}
      "csv" {:head (when header? (csv-line (map :name schema)))
             :row (fn [_ row] (csv-line (map row names)))
             :content-type "text/csv"})))

(defn serialize
  "Corps et type de contenu `{:body :content-type}` des `rows` d'une vue de colonnes `schema`,
  au `format` (`json`, `ndjson` ou `csv`) ; `header?` n'agit que sur `csv`."
  [format schema rows header?]
  (let [{:keys [head row tail content-type]} (encoder format schema header?)]
    {:body (str head (apply str (map-indexed row rows)) tail)
     :content-type content-type}))
