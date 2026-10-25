(ns blaze.addon.sql-on-fhir.view.fichier
  "Exécution en mémoire d'une vue sur un fichier NDJSON (T-030, oracle des N2 de F-006) : lit une
  vue JSON et des ressources NDJSON (sortie de `blazectl download`), exécute `view.run/run`
  inchangé, écrit une ligne NDJSON par ligne de vue. Cœur pur : `executer` ; périphérie :
  `verdict` (fichiers) et `-main`. Codes : 0 succès, 3 fichier absent (NON-BRANCHE), 8 anomalie
  du moteur (catégorie et message sur stderr), 2 usage (`lib/codes.sh`)."
  (:require
   [blaze.addon.sql-on-fhir.view.compile :as compile]
   [blaze.addon.sql-on-fhir.view.run :as run]
   [blaze.anomaly :as ba :refer [if-ok]]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]))

(set! *warn-on-reflection* true)

(defn contexte
  "Contexte d'analyse des ressources FHIR, initialisé hors serveur."
  []
  (ig/init-key :blaze.fhir/parsing-context
               {:structure-definition-repo (ig/init-key :blaze.fhir/structure-definition-repo {})}))

(def ^:private mapper
  (j/object-mapper {:decode-key-fn true :bigdecimals true}))

(defn- lire-vue [texte]
  (ba/try-anomaly (j/read-value texte mapper)))

(defn- lire-ressources [ctx lignes]
  (transduce
   (comp (remove str/blank?) (map #(fhir-spec/parse-json ctx %)))
   (completing (fn [acc r] (if (ba/anomaly? r) (reduced r) (conj acc r))))
   [] lignes))

(defn executer
  "Lignes NDJSON (vecteur de chaînes) de la vue JSON `texte-vue` sur les `lignes` de ressources
  NDJSON, ou une anomalie. Fonction pure."
  [ctx texte-vue lignes]
  (if-ok [vue (lire-vue texte-vue)
          compiled (compile/compile vue)
          ressources (lire-ressources ctx lignes)
          rows (run/run compiled ressources)]
    (mapv j/write-value-as-string rows)
    identity))

(defn- absent [chemin]
  (when-not (.isFile (io/file chemin)) chemin))

(defn verdict
  "Exécute la vue du fichier `vue` sur le fichier de ressources `ressources` :
  `{:code 0 :lignes [..]}`, `{:code 3 :erreur ..}` si un fichier manque, `{:code 8 :erreur ..}`
  sur anomalie."
  [ctx vue ressources]
  (if-some [chemin (or (absent vue) (absent ressources))]
    {:code 3 :erreur (str "NON-BRANCHE : fichier absent " chemin)}
    (with-open [rdr (io/reader ressources)]
      (let [r (executer ctx (slurp vue) (line-seq rdr))]
        (if (ba/anomaly? r)
          {:code 8 :erreur (str (name (::anom/category r)) " : " (::anom/message r))}
          {:code 0 :lignes r})))))

(defn lancer
  "Exécute `vue` sur `ressources` : lignes de vue seules sur stdout, journaux de l'initialisation
  et erreurs sur stderr (le journal du jar écrit sur `*out*`, il est donc dérouté, non coupé).
  Rend le code de sortie."
  [vue ressources]
  (let [{:keys [code lignes erreur]} (verdict (binding [*out* *err*] (contexte)) vue ressources)]
    (run! println lignes)
    (when erreur (binding [*out* *err*] (println erreur)))
    (flush)
    code))

(defn -main [& args]
  (if (= 2 (count args))
    (System/exit (lancer (first args) (second args)))
    (do (binding [*out* *err*] (println "usage : fichier <vue.json> <ressources.ndjson>"))
        (System/exit 2))))
