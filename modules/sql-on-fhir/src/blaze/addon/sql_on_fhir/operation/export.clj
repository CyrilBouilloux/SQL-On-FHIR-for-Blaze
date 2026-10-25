(ns blaze.addon.sql-on-fhir.operation.export
  "AS-01, AS-02 (IB-08, IB-11) : opération système `$viewdefinition-export`, lancement. Le handler
  lit `:blaze/job-scheduler`, `:blaze/db` et le corps brut dans la requête (jamais dans sa
  configuration : `Circular dependency`, T-044). Il refuse sans rien écrire (§3.6 règle 4) :
  `Prefer: respond-async`, paramètres, vues et filtres sont jugés avant la création de la `Task`.
  Rend 202, `Content-Location` = lieu de suivi."
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.addon.sql-on-fhir.export.resource :as resource]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.export.token :as token]
   [blaze.addon.sql-on-fhir.operation.export.spec]
   [blaze.addon.sql-on-fhir.operation.params :as params]
   [blaze.addon.sql-on-fhir.operation.reference :as reference]
   [blaze.addon.sql-on-fhir.view.compile :as compile]
   [blaze.anomaly :as ba :refer [if-ok]]
   [blaze.async.comp :as ac]
   [blaze.db.api :as d]
   [blaze.handler.util :as handler-util]
   [blaze.job-scheduler :as js]
   [blaze.module :as m]
   [blaze.time :as bt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [ring.util.response :as ring]
   [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

(defn- compile-views
  "Vues compilées de `views` (dans l'ordre), ou l'anomalie de la première refusée par le moteur."
  [views]
  (reduce (fn [acc {:keys [view]}]
            (let [compiled (compile/compile view)]
              (if (ba/anomaly? compiled) (reduced compiled) (conj acc compiled))))
          [] views))

(defn- resolve-views
  "`views` dont chaque `:view-reference` est résolue dans `db`, la base de la requête : au retour,
  la `Task` reçoit la vue, comme une vue en ligne (ADR 0009 : la `Library` remplacée ensuite ne
  change pas l'export). Ou l'anomalie du premier refus, avec son `:http/status`."
  [db views]
  (reduce (fn [acc entry]
            (let [resolved (reference/resolve-entry db entry)]
              (if (ba/anomaly? resolved) (reduced resolved) (conj acc resolved))))
          [] views))

(defn- validate
  "Paramètres de la requête, vues résolues puis nommées (noms distincts), ou l'anomalie (avec son `:http/status`) du premier
  refus : paramètres (400), référence (400), vues refusées par le moteur (422), patient ou groupe
  absent (400)."
  [db body]
  (if-ok [params (params/read-export-params body)
          {:keys [views] :as params} (if-ok [views (resolve-views db (:views params))
                                             views (params/name-views views)]
                                       (assoc params :views views)
                                       identity)]
    (if-ok [compiled (compile-views views)
            _ (ds/resources db (:resource (first compiled))
                            (into {} (filter val) (select-keys params [:patient :group :since])))]
      params
      params/unprocessable)
    identity))

(defn- task-spec [clock db {:keys [views] :as params}]
  (-> (select-keys params [:format :header :client-tracking-id :patient :group :since])
      (assoc :t (d/t db)
             :authored-on (bt/offset-date-time clock)
             :views (mapv #(assoc % :token (token/new-token)) views)
             :status-token (token/new-token)
             :result-token (token/new-token))))

(defn- accepted [ctx {:keys [id]} {:keys [status-token client-tracking-id]}]
  (let [parameters (resource/status-parameters
                    ctx {:status "accepted" :id id :status-token status-token
                         :client-tracking-id client-tracking-id})]
    (-> (ring/response parameters)
        (ring/status 202)
        (ring/header "Content-Location" (resource/url ctx :status status-token)))))

(defn- launch [{:keys [clock] :as ctx} {:keys [body headers] :blaze/keys [db job-scheduler]}]
  (if (handler-util/preference headers "respond-async")
    (if-ok [params (validate db body)]
      (let [spec (task-spec clock db params)]
        (-> (js/create-job job-scheduler (task/new-task spec))
            (ac/then-apply #(accepted ctx % spec))))
      ac/completed-future)
    (ac/completed-future (ba/incorrect "The header `Prefer: respond-async` is required."))))

(defmethod m/pre-init-spec :blaze.addon.sql-on-fhir.operation.export/handler [_]
  (s/keys :req-un [::base-url ::context-path ::clock]))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.operation.export/handler
  [_ ctx]
  (log/info "Init FHIR $viewdefinition-export operation handler")
  (fn [request] (launch ctx request)))
