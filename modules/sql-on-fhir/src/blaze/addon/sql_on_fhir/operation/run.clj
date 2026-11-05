(ns blaze.addon.sql-on-fhir.operation.run
  "IB-08 : opération système `$viewdefinition-run`, composants de l'overlay `blaze.edn`.
  Le corps de la requête est lu brut (JSON) par `raw-body`, sans l'analyseur FHIR de Blaze.
  T-035 : le handler lit les paramètres, compile la vue, lit la base de la requête et sérialise."
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.addon.sql-on-fhir.operation.params :as params]
   [blaze.addon.sql-on-fhir.operation.reference :as reference]
   [blaze.addon.sql-on-fhir.operation.run-spec]
   [blaze.addon.sql-on-fhir.operation.sortie :as sortie]
   [blaze.addon.sql-on-fhir.serializer :as serializer]
   [blaze.addon.sql-on-fhir.view.compile :as compile]
   [blaze.addon.sql-on-fhir.view.validate :as validate]
   [blaze.anomaly :as ba :refer [if-ok]]
   [blaze.async.comp :as ac]
   [blaze.module :as m]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [ring.util.response :as ring]
   [taoensso.timbre :as log]))

(defmethod m/pre-init-spec :blaze.addon.sql-on-fhir.operation.run/handler [_]
  (s/keys :req-un [::max-rows ::timeout-seconds]))

(defn- too-costly [max-rows]
  (ba/incorrect (format "The view yields more than %d rows, the maximum of this server." max-rows)
                :fhir/issue "too-costly" :http/status 422))

(defn- response [{:keys [body content-type]}]
  (assoc (ring/response nil) ::sortie/raw body ::sortie/content-type content-type))

(defn- run-view
  "Réponse (ou anomalie) de la requête : paramètres, vue compilée une fois, lignes lues sur la
  base de la requête avec `limit` = min(`_limit`, plafond + 1) ; plafond + 1 lignes = trop coûteux.
  Une `viewReference` est résolue dans cette même base (F-011) ; ses refus portent leur statut.
  F-048 : l'échéance (`timeout-seconds` après l'appel) est vérifiée à chaque paquet de ressources lu ;
  dépassée, 422 `timeout`, jamais de réponse tronquée."
  [max-rows timeout-seconds {:keys [body] :blaze/keys [db] query-format ::sortie/format accept-format ::sortie/accept-format}]
  (if-ok [{:keys [view limit] :as params} (if-ok [params (params/read-params body query-format accept-format)]
                                            (reference/resolve-entry db params)
                                            identity)]
    (if-ok [compiled (or (validate/require-status view) (compile/compile view))]
      (if-ok [rows (ds/rows compiled db (assoc (into {} (filter val) (select-keys params [:patient :group :since]))
                                               :deadline (ds/deadline timeout-seconds)
                                               :limit (min (or limit (inc max-rows)) (inc max-rows))))]
        (if (> (count rows) max-rows)
          (too-costly max-rows)
          (response (serializer/serialize (:format params) (:schema compiled) rows (:header params))))
        params/unprocessable)
      params/unprocessable)
    identity))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.operation.run/handler [_ {:keys [max-rows timeout-seconds]}]
  (log/info "Init FHIR $viewdefinition-run operation handler, max rows" max-rows
            ", timeout seconds" timeout-seconds)
  (fn [request]
    (ac/completed-future (run-view max-rows timeout-seconds request))))

(defn- read-body [handler request]
  (if-let [body (:body request)]
    (let [parsed (ba/try-anomaly (j/read-value body j/keyword-keys-object-mapper))]
      (if (ba/anomaly? parsed)
        (ac/completed-future (ba/incorrect "Invalid JSON body."))
        (handler (assoc request :body parsed))))
    (ac/completed-future (ba/incorrect "Missing HTTP body."))))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.operation.run/raw-body [_ _]
  {:name :blaze.addon.sql-on-fhir.operation.run/raw-body
   :wrap (fn [handler] (partial read-body handler))})
