(ns blaze.addon.sql-on-fhir.data-source
  "DS-01…DS-04, DS-06, DS-07 (IB-10) : source de données d'une vue sur **une** valeur de base
  `db` (l'instant `t` est celui de `db`, aucune copie). Filtres exprimés par les recherches de
  Blaze (`blaze.db.api`) : compartiment `Patient`, `Group.member`, `_lastUpdated=gt<since>`.
  Les ressources sont lues par paquets de 100, à la demande ; `rows` s'arrête à `limit` lignes."
  (:require
   [blaze.addon.sql-on-fhir.view.run :as run]
   [blaze.anomaly :as ba :refer [if-ok]]
   [blaze.db.api :as d]))

(set! *warn-on-reflection* true)

(defn- attendre
  "Valeur du future `f`, ou lance l'anomalie qui l'a fait échouer (à attraper par `try-anomaly`)."
  [f]
  (ba/throw-when @f))

(defn- existing [db type id]
  (let [handle (d/resource-handle db type id)]
    (if (or (nil? handle) (d/deleted? handle))
      (ba/throw-anom (ba/not-found (format "%s with id `%s` not found." type id)))
      handle)))

(defn- member-patient-ids [db group-id]
  (->> (:member (attendre (d/pull db (existing db "Group" group-id))))
       (keep #(some->> % :entity :reference :value (re-matches #"Patient/(.+)") second))
       distinct))

(defn- patient-ids
  "Identifiants de patients que retiennent `patient` et `group` (intersection si les deux),
  ou `nil` sans filtre de patient."
  [db {:keys [patient group]}]
  (when patient (existing db "Patient" patient))
  (if group
    (cond->> (member-patient-ids db group) patient (filter #{patient}))
    (some-> patient vector)))

(defn- distinct-id
  "Transducteur : ne garde que la première ressource de chaque `:id`."
  [rf]
  (let [seen (volatile! #{})]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result handle]
       (if (contains? @seen (:id handle))
         result
         (do (vswap! seen conj (:id handle)) (rf result handle)))))))

(defn- handles
  "Poignées de ressources de `type` : tout le type, ou le compartiment de chaque patient de
  `ids` ; `clauses` en plus, si non vides."
  [db type ids clauses]
  (cond
    (nil? ids)
    (if (seq clauses) (attendre (d/type-query db type clauses)) (d/type-list db type))

    (= "Patient" type)
    (if (empty? ids) [] (attendre (d/type-query db type (into [(into ["_id"] ids)] clauses))))

    :else
    (eduction (mapcat #(attendre (if (seq clauses)
                                   (d/compartment-query db "Patient" % type clauses)
                                   (d/compartment-type-list db "Patient" % type))))
              distinct-id
              ids)))

(defn resources
  "Ressources de `type` lues dans `db` (réductible, lecture à la demande), filtrées par
  `patient`, `group`, `since` (instant : `_lastUpdated=gt`) ; ou une anomalie (`not-found` si
  le patient ou le groupe n'existe pas, `incorrect` si `since` n'est pas un instant)."
  [db type {:keys [since] :as opts}]
  (ba/try-anomaly
   (let [ids (patient-ids db opts)
         clauses (cond-> [] since (conj ["_lastUpdated" (str "gt" since)]))]
     (eduction (comp (partition-all 100) (mapcat #(attendre (d/pull-many db %))))
               (handles db type ids clauses)))))

(defn resource-rows
  "Lignes de la vue compilée `view` sur les `resources` (réductible) : une source réductible qui
  ne retient aucune ligne. Une ressource que la vue refuse lance l'anomalie dans le réduit."
  [view resources]
  (eduction (mapcat (fn [resource] (ba/throw-when (run/run view [resource])))) resources))

(defn row-source
  "Lignes de la vue compilée `view` sur les `resources` de `db` (mêmes options, sans `limit`) :
  une source réductible, lue à la demande par paquets de 100 ressources, qui ne retient aucune
  ligne ; ou une anomalie de la source. Une ressource que la vue refuse lance l'anomalie dans le
  réduit (à attraper par `ba/try-anomaly`)."
  [view db opts]
  (if-ok [resources (resources db (:resource view) opts)]
    (resource-rows view resources)
    identity))

(defn rows
  "Lignes de la vue compilée `view` sur les `resources` de `db` (mêmes options, plus `limit` :
  au plus `limit` lignes, la lecture s'arrête dès qu'elles sont réunies), ou une anomalie."
  [view db {:keys [limit] :as opts}]
  (if-ok [source (row-source view db opts)]
    (ba/try-anomaly (into [] (cond-> identity limit (comp (take limit))) source))
    identity))
