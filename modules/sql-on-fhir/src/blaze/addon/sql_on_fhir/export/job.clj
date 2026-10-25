(ns blaze.addon.sql-on-fhir.export.job
  "AS-03, AS-04 (IB-11) : tâche de fond de l'export, gestionnaire de tâche (`JobHandler`) de
  l'ordonnanceur de Blaze. Un fichier par vue, écrit en flux sous le dossier d'export, à l'instant
  `t` de la `Task`. Le gestionnaire sonde le statut de sa `Task` toutes les 100 ms au plus et s'arrête
  si elle n'est plus `in-progress` (annulation, arrêt ordonné : sinon l'arrêt de Blaze l'attend)."
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.addon.sql-on-fhir.export.job.spec]
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.serializer.file :as file]
   [blaze.addon.sql-on-fhir.view.compile :as compile]
   [blaze.anomaly :as ba]
   [blaze.async.comp :as ac]
   [blaze.executors :as ex]
   [blaze.handler.fhir.util :as fhir-util]
   [blaze.job-scheduler.protocols :as p]
   [blaze.job.util :as job-util]
   [blaze.module :as m]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [taoensso.timbre :as log])
  (:import
   [clojure.lang ExceptionInfo]
   [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private poll-period-nanos 100000000)

(defn status-check
  "Fonction sans argument qui relit le statut de la `Task` `id` au premier appel puis au plus toutes
  les 100 ms, et lance une `ExceptionInfo` (donnée `::stop` : le statut) s'il n'est plus
  `in-progress`."
  [admin-node id]
  (let [last-poll (volatile! (- (System/nanoTime) poll-period-nanos 1))]
    (fn []
      (let [now (System/nanoTime)]
        (when (< poll-period-nanos (- now @last-poll))
          (vreset! last-poll now)
          (let [status (-> @(job-util/pull-job admin-node id) :status :value)]
            (when (not= "in-progress" status)
              (throw (ex-info (str "The export was stopped, the task is " status ".")
                              {::stop status})))))))))

(defn interruptible
  "Source réductible des mêmes lignes que `source`, qui appelle `check!` avant chaque ligne."
  [check! source]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init]
      (reduce (fn [acc row] (check!) (f acc row)) init source))))

(def ^:private space-check-step
  "Nombre de caractères écrits entre deux contrôles du plafond d'espace : le plafond est tenu à
  cette précision près en cours d'écriture, et exactement à la fermeture de chaque fichier."
  1048576)

(defn- ensure-space!
  "Lance un conflit si les fichiers du dossier (`.part` compris) dépassent `max-bytes` octets."
  [dir max-bytes]
  (when (< max-bytes (storage/used-bytes dir))
    (ba/throw-anom (ba/conflict (format "The export files would exceed the space limit of %d bytes." max-bytes)))))

(defn write-view!
  "Écrit dans `out` les lignes de `source` de la vue de colonnes `schema`, au `format`, par la chaîne
  de production : sonde `check!` avant chaque ligne, écrivain à plafond d'espace (`step`,
  `check-space!`), sérialiseur. Rend le nombre de lignes écrites ; ferme `out`."
  [out step check-space! check! format schema header source]
  (with-open [out (storage/limited-writer out step check-space!)]
    (file/write-rows! format schema header (interruptible check! source) out)))

(defn- export-files!
  "Écrit un fichier par vue de `spec` dans `dir`, sur la base de `main-node` à l'instant `t`.
  Lance l'anomalie qui l'interrompt, dont le dépassement du plafond d'espace `max-bytes`."
  [{:keys [main-node dir db-sync-timeout max-bytes step] :or {step space-check-step}} check!
   {:keys [t format header views patient group since]}]
  (let [db @(fhir-util/sync main-node t db-sync-timeout)
        opts (cond-> {} patient (assoc :patient patient) group (assoc :group group) since (assoc :since since))
        check-space! #(ensure-space! dir max-bytes)]
    (run! (fn [{:keys [token view]}]
            (let [compiled (ba/throw-when (compile/compile view))
                  source (ba/throw-when (ds/row-source compiled db opts))]
              (check-space!)
              (storage/write-file! dir token format
                                   #(write-view! % step check-space! check! format (:schema compiled) header source))))
          views)))

(defn run-export
  "Exécute l'export de `spec` : `:done`, `:paused` (la tâche est `on-hold`), `:cancelled`, ou
  l'anomalie qui l'a fait échouer. Sauf `:done`, aucun fichier de la tâche ne reste dans `dir`."
  [{:keys [dir] :as ctx} check! {:keys [format views] :as spec}]
  (let [result (try
                 (export-files! ctx check! spec)
                 :done
                 (catch ExceptionInfo e
                   (case (::stop (ex-data e))
                     "on-hold" :paused
                     "cancelled" :cancelled
                     (ba/anomaly e)))
                 (catch Throwable e
                   (ba/anomaly e)))]
    (when-not (= :done result)
      (storage/delete-files! dir (map :token views) format))
    result))

(defn- start-job [job]
  (assoc job :status #fhir/code "in-progress" :statusReason job-util/started-status-reason))

(defn- complete-job
  "`completed` seulement si la `Task` lue est encore `in-progress` : une `Task` annulée ou mise en
  attente entre la dernière sonde et cette écriture reste telle quelle. `update-job-with-retry` relit
  la `Task` et écrit sous `If-Match` (412 → relecture) : la lecture et l'écriture sont atomiques."
  [job]
  (cond-> job
    (= "in-progress" (-> job :status :value))
    (-> (assoc :status #fhir/code "completed")
        (dissoc :statusReason))))

(defn- finish-cancellation [job]
  (assoc job :businessStatus job-util/cancellation-finished-sub-status))

(defn- finish
  "Suite de l'exécution : `result` n'est jamais une anomalie (`supply-async` fait alors échouer le
  future, et l'ordonnanceur met la `Task` en `failed` avec cette anomalie)."
  [{:keys [admin-node]} {:keys [id] :as job} result]
  (case result
    :done (job-util/update-job-with-retry admin-node 5 id complete-job)
    :paused (ac/completed-future job)
    :cancelled (job-util/update-job-with-retry admin-node 5 id finish-cancellation)))

(defn- execute
  "Exécute l'export de `job` sur le pool du module ; repart toujours de zéro."
  [{:keys [admin-node dir executor] :as ctx} {:keys [id] :as job}]
  (let [{:keys [format views] :as spec} (task/spec job)]
    (-> (ac/supply-async
         (fn []
           (storage/delete-files! dir (map :token views) format)
           (run-export ctx (status-check admin-node id) spec))
         executor)
        (ac/then-compose #(finish ctx job %)))))

(defmethod m/pre-init-spec :blaze.job/viewdefinition-export [_]
  (s/keys :req-un [::main-node ::admin-node ::dir ::db-sync-timeout ::max-bytes ::executor]))

(defmethod ig/init-key :blaze.job/viewdefinition-export
  [_ {:keys [admin-node dir] :as config}]
  (log/info "Init ViewDefinition export job handler, directory" dir)
  (let [ctx (assoc config :dir (io/file dir))]
    (reify p/JobHandler
      (-on-start [_ job]
        (-> (job-util/update-job admin-node job start-job)
            (ac/then-compose #(execute ctx %))))
      (-on-resume [_ job]
        (execute ctx job))
      (-on-cancel [_ job]
        (let [{:keys [format views]} (task/spec job)]
          (storage/delete-files! (:dir ctx) (map :token views) format))
        (ac/completed-future job)))))

(derive :blaze.job/viewdefinition-export :blaze.job/handler)

(defmethod m/pre-init-spec :blaze.addon.sql-on-fhir.export.job/executor [_]
  (s/keys))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.export.job/executor
  [_ _]
  (log/info "Init ViewDefinition export executor with 2 threads")
  (ex/io-pool 2 "viewdefinition-export-%d"))

(defmethod ig/halt-key! :blaze.addon.sql-on-fhir.export.job/executor
  [_ executor]
  (log/info "Stopping ViewDefinition export executor...")
  (ex/shutdown! executor)
  (if (ex/await-termination executor 10 TimeUnit/SECONDS)
    (log/info "ViewDefinition export executor was stopped successfully")
    (log/warn "Got timeout while stopping the ViewDefinition export executor")))

(derive :blaze.addon.sql-on-fhir.export.job/executor :blaze.metrics/thread-pool-executor)
