(ns blaze.addon.sql-on-fhir.export.resource
  "AS-02, AS-06 : URL des routes de l'export et `Parameters` de suivi et de résultat (types de
  Blaze, encodés par l'encodeur FHIR de Blaze). Fonctions pures."
  (:require
   [blaze.addon.sql-on-fhir.export.resource.spec]
   [blaze.fhir.spec.type :as type]))

(defn url
  "URL absolue de la route `kind` (`:status`, `:result`, `:file`) du `token`."
  [{:keys [base-url context-path]} kind token]
  (str base-url context-path "/__export/" (name kind) "/" token))

(defn- parameter [name value]
  (cond-> {:fhir/type :fhir.Parameters/parameter :name (type/string name)}
    value (assoc :value value)))

(defn- part [name value]
  {:fhir/type :fhir.Parameters.parameter/part :name (type/string name) :value value})

(defn- parameters [parameter]
  {:fhir/type :fhir/Parameters :parameter (vec parameter)})

(defn status-parameters
  "`Parameters` du lancement et du lieu de suivi d'une tâche en cours : `exportId` (l'`id` de la
  `Task`), `status` (`accepted` ou `in-progress`), `location` (le lieu de suivi), et
  `clientTrackingId` s'il est fourni."
  [ctx {:keys [status id status-token client-tracking-id]}]
  (parameters
   (cond-> [(parameter "exportId" (type/string id))
            (parameter "status" (type/code status))
            (parameter "location" (type/uri (url ctx :status status-token)))]
     client-tracking-id (conj (parameter "clientTrackingId" (type/string client-tracking-id))))))

(defn result-parameters
  "`Parameters` du résultat d'une tâche terminée : `exportId`, `status` `completed`,
  `clientTrackingId` s'il est fourni, et un `output` (`name`, `location`) par fichier."
  [ctx {:keys [id client-tracking-id outputs]}]
  (parameters
   (cond-> [(parameter "exportId" (type/string id))
            (parameter "status" (type/code "completed"))]
     client-tracking-id (conj (parameter "clientTrackingId" (type/string client-tracking-id)))
     true (into (map (fn [{:keys [name token]}]
                       (assoc (parameter "output" nil)
                              :part [(part "name" (type/string name))
                                     (part "location" (type/uri (url ctx :file token)))])))
                outputs))))
