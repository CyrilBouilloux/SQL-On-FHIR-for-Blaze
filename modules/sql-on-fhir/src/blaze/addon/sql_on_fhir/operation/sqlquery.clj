(ns blaze.addon.sql-on-fhir.operation.sqlquery
  "SQ-03 (F-039, ADR 0014) : opération système `$sqlquery-run`. Une Library SQLQuery (en ligne ou par
  référence) désigne des vues ; chacune devient une table d'une base DuckDB en mémoire, une par
  requête, réglée sans fichier ni réseau (`sql.engine/configure!`) ; la requête, jugée `SELECT` par
  le moteur, s'exécute à valeurs liées sous les plafonds de durée, de mémoire et de lignes.

  Les requêtes simultanées sont plafonnées : la suivante est refusée sur-le-champ (503 `throttled`),
  avant toute ouverture de base. Le corps et le format de sortie passent par les mêmes enveloppes que
  `$viewdefinition-run` (`operation.run/raw-body`, `operation.sortie/app`)."
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.addon.sql-on-fhir.operation.params :as params]
   [blaze.addon.sql-on-fhir.operation.reference :as reference]
   [blaze.addon.sql-on-fhir.operation.sortie :as sortie]
   [blaze.addon.sql-on-fhir.operation.sqlquery-params :as sqlquery-params]
   [blaze.addon.sql-on-fhir.operation.sqlquery-spec]
   [blaze.addon.sql-on-fhir.serializer :as serializer]
   [blaze.addon.sql-on-fhir.sql.engine :as engine]
   [blaze.addon.sql-on-fhir.sql.query :as query]
   [blaze.addon.sql-on-fhir.sql.tables :as tables]
   [blaze.addon.sql-on-fhir.view.compile :as compile]
   [blaze.anomaly :as ba :refer [if-ok throw-anom when-ok]]
   [blaze.async.comp :as ac]
   [blaze.db.api :as d]
   [blaze.module :as m]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [ring.util.response :as ring]
   [taoensso.timbre :as log])
  (:import
   [java.util.concurrent Semaphore TimeUnit]))

(set! *warn-on-reflection* true)

(defmethod m/pre-init-spec :blaze.addon.sql-on-fhir.operation.sqlquery/handler [_]
  (s/keys :req-un [::max-rows ::timeout-seconds ::max-memory ::max-concurrent]))

(defn- stored-library
  "La Library `reference` (`Library/<id>`) de `db`, ou l'anomalie 400 (forme) ou 404 (absente)."
  [db reference]
  (if-let [[_ id] (re-matches #"Library/([A-Za-z0-9\-.]{1,64})" reference)]
    (let [handle (d/resource-handle db "Library" id)]
      (if (or (nil? handle) (d/deleted? handle))
        (ba/not-found (format "Library `%s` not found." reference) :http/status 404)
        (ba/try-anomaly (ba/throw-when @(d/pull db handle)))))
    (ba/incorrect "`queryReference` must be of the form `Library/<id>`." :http/status 400)))

(defn- remaining-ns [deadline] (- deadline (System/nanoTime)))

(defn- remaining-seconds
  "Secondes restantes avant `deadline`, arrondies au-dessus, au moins 1 (0 = sans plafond pour le moteur)."
  [deadline]
  (max 1 (long (Math/ceil (/ (remaining-ns deadline) 1e9)))))

(defn- before-deadline
  "Réductible des `resources` qui interrompt le chargement d'une vue (anomalie `timeout`, interceptée
  par `view.run/run`) dès que `deadline` (`System/nanoTime`) est passée."
  [deadline resources]
  (eduction (map (fn [r] (if (neg? (remaining-ns deadline)) (throw-anom (engine/timeout)) r)))
            resources))

(defn- table
  "`{:name :view :resources}` de la table `name` : la vue de la Library que désigne `canonical` dans
  `db` (404 si aucune), compilée (422 si refusée), et ses ressources, lues avant `deadline`."
  [db deadline {:keys [name canonical]}]
  (when-ok [view (let [v (reference/resolve-view db canonical)]
                   (cond-> v (ba/not-found? v) (assoc :http/status 404)))
            compiled (if-ok [c (compile/compile view)] c params/unprocessable)
            resources (ds/resources db (:resource compiled) {})]
    {:name name :view compiled :resources (before-deadline deadline resources)}))

(defn- all-tables [db deadline tables]
  (reduce (fn [acc t]
            (let [r (table db deadline t)]
              (if (ba/anomaly? r) (reduced r) (conj acc r))))
          [] tables))

(defn- too-costly [max-rows]
  (ba/incorrect (format "The query yields more than %d rows, the maximum of this server." max-rows)
                :fhir/issue "too-costly" :http/status 422))

(defn- response [{:keys [body content-type]}]
  (assoc (ring/response nil) ::sortie/raw body ::sortie/content-type content-type))

(defn- run-query
  "Réponse (ou anomalie) de la requête. Tout ce qui se refuse sans base (corps, Library, paramètres,
  vues) se refuse avant l'ouverture de la base ; dans la base, les réglages d'isolement puis la garde
  `SELECT` précèdent le chargement des tables. Le plafond de durée est une échéance unique, posée à
  l'entrée, qui couvre le chargement des vues et l'exécution (qui n'a que le temps restant)."
  [{:keys [max-rows timeout-seconds max-memory]}
   {:keys [body] :blaze/keys [db] query-format ::sortie/format accept-format ::sortie/accept-format}]
  (let [deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS (long timeout-seconds)))]
    (when-ok [{:keys [format header limit parameters] :as input} (sqlquery-params/read-params body query-format accept-format)
              library (if (:library input) (:library input) (stored-library db (:reference input)))
              {:keys [sql tables declared]} (query/read-library library)
              {positional :sql :keys [names]} (query/named->positional sql)
              values (query/bind-values declared names parameters)
              tables (all-tables db deadline tables)]
      (with-open [conn (tables/open-memory-db!)]
        (when-ok [_ (engine/configure! conn max-memory)
                  _ (engine/check-select! conn positional)
                  _ (tables/load-tables! conn tables)
                  {:keys [columns rows]} (engine/query! conn positional values
                                                        {:timeout-seconds (remaining-seconds deadline)
                                                         :max-rows (min (or limit (inc max-rows)) (inc max-rows))})]
          (if (> (count rows) max-rows)
            (too-costly max-rows)
            (response (serializer/serialize format (mapv (fn [c] {:name c}) columns) rows header))))))))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.operation.sqlquery/handler
  [_ {:keys [max-concurrent] :as config}]
  (log/info "Init FHIR $sqlquery-run operation handler, max concurrent" max-concurrent
            ", timeout seconds" (:timeout-seconds config) ", max memory" (:max-memory config)
            ", max rows" (:max-rows config))
  (let [permits (Semaphore. (int max-concurrent))]
    (fn [request]
      (ac/completed-future
       (if (.tryAcquire permits)
         (try
           (ba/try-anomaly (run-query config request))
           (finally (.release permits)))
         (ba/busy (format "At most %d $sqlquery-run requests run at the same time; try again later." max-concurrent)
                  :fhir/issue "throttled"))))))
