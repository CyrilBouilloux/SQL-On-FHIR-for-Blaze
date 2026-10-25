(ns blaze.addon.sql-on-fhir.export.task
  "AS-03 : la `Task` d'un export. Fonctions pures : la `Task` porte tout ce qu'il faut pour
  exécuter l'export, le reprendre et servir ses URL (`t`, format, vues, filtres, jetons).
  Jetons rangés en `identifier` (systèmes `urn:blaze-platform:viewdefinition-export:{status,
  result,file}`), retrouvés par la recherche `identifier` du nœud d'administration (ADR 0008,
  point 3) ; entrées sous le système `…:param`."
  (:require
   [blaze.fhir.spec.type :as type]
   [blaze.job.util :as job-util]
   [jsonista.core :as j]))

(def ^:private prefix "urn:blaze-platform:viewdefinition-export:")

(defn identifier-system
  "Système de l'`identifier` du jeton d'une `kind` d'URL : `:status`, `:result` ou `:file`."
  [kind]
  (str prefix (name kind)))

(defn- param-type [code]
  (type/codeable-concept
   {:coding [(type/coding {:system (type/uri-interned (str prefix "param")) :code (type/code code)})]}))

(defn- input [code value]
  {:fhir/type :fhir.Task/input :type (param-type code) :value value})

(defn- identifier [kind token]
  (type/identifier {:system (type/uri-interned (identifier-system kind)) :value (type/string token)}))

(def ^:private view-mapper j/keyword-keys-object-mapper)

(defn new-task
  "La `Task` `ready` d'un export. `spec` : `:t`, `:format`, `:views` (`[{:name :token :view}]`),
  `:header` (booléen), `:status-token`, `:result-token`, `:authored-on` (`OffsetDateTime`) ; facultatifs :
  `:client-tracking-id`, `:patient`, `:group`, `:since`."
  [{:keys [t format header views client-tracking-id patient group since status-token result-token authored-on]}]
  {:fhir/type :fhir/Task
   :status #fhir/code "ready"
   :intent #fhir/code "order"
   :code (job-util/type-codeable-concept "viewdefinition-export" "ViewDefinition export")
   :authoredOn (type/dateTime authored-on)
   :identifier (into [(identifier :status status-token) (identifier :result result-token)]
                     (map #(identifier :file (:token %)))
                     views)
   :input (cond-> [(input "t" (type/unsignedInt t))
                   (input "format" (type/string format))
                   (input "header" (type/boolean header))]
            client-tracking-id (conj (input "clientTrackingId" (type/string client-tracking-id)))
            patient (conj (input "patient" (type/string patient)))
            group (conj (input "group" (type/string group)))
            since (conj (input "since" (type/string since)))
            true (into (map #(input "view" (type/string (j/write-value-as-string % view-mapper)))) views))})

(defn- inputs [{:keys [input]} code]
  (into [] (keep #(when (= code (-> % :type :coding first :code :value)) (-> % :value :value))) input))

(defn- identifiers [{:keys [identifier]} kind]
  (into [] (keep #(when (= (identifier-system kind) (-> % :system :value)) (-> % :value :value))) identifier))

(defn token
  "Jeton de la `kind` d'URL (`:status` ou `:result`) de `task`, ou `nil`."
  [task kind]
  (first (identifiers task kind)))

(defn spec
  "La spécification de `task` : celle qu'a reçue `new-task`, sans `:authored-on`. Les clés
  facultatives absentes de la tâche sont absentes du résultat."
  [task]
  (let [one #(first (inputs task %))]
    (cond-> {:t (one "t")
             :format (one "format")
             :header (one "header")
             :views (mapv #(j/read-value % view-mapper) (inputs task "view"))
             :status-token (token task :status)
             :result-token (token task :result)}
      (one "clientTrackingId") (assoc :client-tracking-id (one "clientTrackingId"))
      (one "patient") (assoc :patient (one "patient"))
      (one "group") (assoc :group (one "group"))
      (one "since") (assoc :since (one "since")))))
