(ns blaze.addon.sql-on-fhir.export.routes
  "AS-06, AS-07, AS-09 (IB-13) : routes `<contexte>/__export/{status,result,file}/<jeton>` de l'export,
  servies par l'enveloppe de `:blaze.handler/app` (ni routeur ni intergiciels de Blaze : l'encodage
  FHIR est fait ici avec le contexte d'écriture de Blaze). Une `Task` se retrouve par son jeton, rangé
  en `identifier` ; jeton inconnu, altéré, d'une autre sorte d'URL, ou tâche à l'état qui ne sert pas
  cette URL : 404 + `OperationOutcome` `not-found`."
  (:require
   [blaze.addon.sql-on-fhir.export.resource :as resource]
   [blaze.addon.sql-on-fhir.export.routes.spec]
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.export.token :as token]
   [blaze.anomaly :as ba]
   [blaze.async.comp :as ac]
   [blaze.coll.core :as coll]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.spec.type :as type]
   [blaze.job-scheduler :as js]
   [blaze.job.util :as job-util]
   [blaze.module :as m]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [integrant.core :as ig]
   [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

(defn- outcome [status code message]
  {:status status
   :resource {:fhir/type :fhir/OperationOutcome
              :issue [{:fhir/type :fhir.OperationOutcome/issue
                       :severity #fhir/code "error"
                       :code (type/code code)
                       :diagnostics (type/string message)}]}})

(def ^:private not-found (outcome 404 "not-found" "Not found."))

(defn- parse-uri
  "`[kind token]` de l'URI d'une route de l'export sous `context-path`, ou `nil`."
  [context-path uri]
  (when (str/starts-with? uri (str context-path "/__export/"))
    (when-let [[_ kind token] (re-matches #"/__export/(status|result|file)/([^/]+)"
                                          (subs uri (count context-path)))]
      [(keyword kind) token])))

(defn- find-task
  "Future de la `Task` d'export dont le jeton de la `kind` d'URL est `token`, ou de `nil`."
  [admin-node kind token]
  (if (token/token? token)
    (-> (d/type-query (d/db admin-node) "Task"
                      [["identifier" (str (task/identifier-system kind) "|" token)]])
        (ac/then-compose
         (fn [handles]
           (if-let [handle (coll/first handles)]
             (ac/then-apply (d/pull admin-node handle)
                            #(when (= :viewdefinition-export (job-util/job-type %)) %))
             (ac/completed-future nil)))))
    (ac/completed-future nil)))

(defn- serve-status [ctx {:keys [id] :as task}]
  (let [{:keys [client-tracking-id status-token result-token]} (task/spec task)
        status-parameters #(hash-map :status 202
                                     :resource (resource/status-parameters
                                                ctx {:status % :id id :status-token status-token
                                                     :client-tracking-id client-tracking-id}))]
    (case (-> task :status :value)
      "ready" (status-parameters "accepted")
      ("in-progress" "on-hold") (status-parameters "in-progress")
      ("completed" "failed") {:status 303 :headers {"Location" (resource/url ctx :result result-token)}}
      not-found)))

(defn- serve-result [ctx {:keys [id] :as task}]
  (case (-> task :status :value)
    "completed" (let [{:keys [client-tracking-id views]} (task/spec task)]
                  {:status 200
                   :resource (resource/result-parameters
                              ctx {:id id :client-tracking-id client-tracking-id
                                   :outputs (mapv #(select-keys % [:name :token]) views)})})
    "failed" (outcome 500 "exception" (or (job-util/error-msg task) "The export failed."))
    not-found))

(defn- serve-file [{:keys [dir]} token task]
  (let [file (when (= "completed" (-> task :status :value))
               (storage/file dir token (:format (task/spec task))))]
    (if (and file (.isFile file))
      {:status 200
       :headers {"Content-Type" (storage/content-type (:format (task/spec task)))
                 "Content-Length" (str (.length file))}
       :body file}
      not-found)))

(defn- serve [ctx kind token task]
  (if task
    (condp = kind
      :status (serve-status ctx task)
      :result (serve-result ctx task)
      :file (serve-file ctx token task))
    not-found))

(defn- encode
  "Réponse Ring de `{:status :headers :resource :body}` : la ressource FHIR est encodée en JSON."
  [{:keys [writing-context]} {:keys [resource] :as response}]
  (if resource
    (let [json (fhir-spec/write-json-as-string writing-context resource)]
      (if (ba/anomaly? json)
        (throw (ex-info "Encoding failed." json))
        (-> (dissoc response :resource)
            (assoc :body json)
            (assoc-in [:headers "Content-Type"] "application/fhir+json;charset=utf-8"))))
    response))

(def ^:private accepted {:status 202})

(defn- delete-task!
  "Future de la réponse au `DELETE` du lieu de suivi de `task` : une tâche en cours est annulée (son
  gestionnaire supprime les fichiers), une tâche terminée est supprimée avec ses fichiers ; 202. Une
  tâche annulée ou qui se termine entre la lecture et l'annulation : 404."
  [{:keys [admin-node job-scheduler dir]} {:keys [id] :as task}]
  (case (-> task :status :value)
    ("ready" "in-progress" "on-hold")
    (ac/handle (js/cancel-job job-scheduler id)
               (fn [_ e] (cond (nil? e) accepted (ba/conflict? e) not-found :else e)))

    ("completed" "failed")
    (let [{:keys [format views]} (task/spec task)]
      (storage/delete-files! dir (map :token views) format)
      (ac/then-apply (d/transact admin-node [[:delete "Task" id]]) (constantly accepted)))

    (ac/completed-future not-found)))

(defn- export-task?
  "Future de vrai si la `Task` `id` du nœud d'administration est un export."
  [admin-node id]
  (if-let [handle (d/resource-handle (d/db admin-node) "Task" id)]
    (ac/then-apply (d/pull admin-node handle) #(= :viewdefinition-export (job-util/job-type %)))
    (ac/completed-future false)))

(defn- async-status-id
  "Identifiant de la `Task` du chemin `<contexte>/__async-status/<id>` de Blaze, ou `nil`."
  [context-path uri]
  (when (str/starts-with? uri (str context-path "/__async-status/"))
    (second (re-matches #"/__async-status/([A-Za-z0-9.-]{1,64})" (subs uri (count context-path))))))

(defn- internal-error [e]
  (log/error "Error while serving an export route:" (ex-message e))
  (outcome 500 "exception" "Internal error."))

(defn- serve-export [{:keys [admin-node] :as ctx} request-method kind token]
  (cond
    (= :get request-method)
    (ac/then-apply (find-task admin-node kind token) #(serve ctx kind token %))

    (and (= :delete request-method) (= :status kind))
    (ac/then-compose (find-task admin-node kind token)
                     #(if % (delete-task! ctx %) (ac/completed-future not-found)))

    :else (ac/completed-future (outcome 405 "not-supported" "Method not allowed."))))

(defn route
  "Future de la réponse Ring de la requête `request` si c'est une route de l'export ; `nil` sinon.
  R2 (ADR 0008 point 4) : `GET` et `DELETE` de `/__async-status/<id>` d'une tâche d'export rendent
  404 ; pour toute autre tâche, le future rend `nil` : la requête est celle de Blaze."
  [{:keys [admin-node context-path] :as ctx} {:keys [request-method uri]}]
  (if-let [[kind token] (parse-uri context-path uri)]
    (-> (serve-export ctx request-method kind token)
        (ac/then-apply #(encode ctx %))
        (ac/exceptionally #(encode ctx (internal-error %))))
    (when-let [id (and (#{:get :delete} request-method) (async-status-id context-path uri))]
      (-> (export-task? admin-node id)
          (ac/then-apply #(when % (encode ctx not-found)))
          (ac/exceptionally #(encode ctx (internal-error %)))))))

(defmethod m/pre-init-spec :blaze.addon.sql-on-fhir.export.routes/handler [_]
  (s/keys :req-un [::admin-node ::job-scheduler ::dir ::writing-context ::base-url ::context-path]))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.export.routes/handler [_ {:keys [dir] :as ctx}]
  (log/info "Init ViewDefinition export routes, directory" dir)
  (let [ctx (assoc ctx :dir (io/file dir))]
    (fn [request] (route ctx request))))
