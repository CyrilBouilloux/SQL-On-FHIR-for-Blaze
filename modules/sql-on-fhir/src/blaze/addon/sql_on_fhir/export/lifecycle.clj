(ns blaze.addon.sql-on-fhir.export.lifecycle
  "AS-10 (IB-11) : cycle de vie des exports. Au démarrage, les tâches interrompues sont reprises
  (arrêt ordonné) ou déclarées échouées (R1, ADR 0008 point 2) ; ensuite, à chaque période, les tâches
  terminées depuis plus longtemps que la durée de conservation sont supprimées avec leurs fichiers,
  ainsi que tout fichier d'export qu'aucune tâche ne désigne (orphelin)."
  (:require
   [blaze.addon.sql-on-fhir.export.lifecycle.spec]
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.anomaly :as ba]
   [blaze.db.api :as d]
   [blaze.job-scheduler :as js]
   [blaze.job.util :as job-util]
   [blaze.module :as m]
   [blaze.scheduler :as sched]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [java-time.api :as time]
   [taoensso.timbre :as log])
  (:import
   [java.time Instant]))

(set! *warn-on-reflection* true)

;; --- fonctions pures ---------------------------------------------------------------------

(defn- status [task] (-> task :status :value))

(defn expired?
  "Vrai si `task` est terminée (`completed`, `failed`, `cancelled`) depuis `retention-seconds`
  secondes ou plus à l'instant `now`, comptées depuis sa dernière modification."
  [task now retention-seconds]
  (boolean
   (and (#{"completed" "failed" "cancelled"} (status task))
        (when-let [updated (-> task :meta :lastUpdated :value)]
          (not (.isBefore ^Instant now (.plusSeconds (.toInstant ^java.time.OffsetDateTime updated)
                                                     retention-seconds)))))))

(defn recovery-action
  "Sort d'une tâche au démarrage : `:resume` (arrêt ordonné, elle repart de zéro), `:fail` (R1 :
  `in-progress` alors qu'aucune exécution n'est vivante), sinon `nil`."
  [task]
  (case (status task)
    "on-hold" (when (= "orderly-shutdown" (job-util/status-reason task)) :resume)
    "in-progress" :fail
    nil))

(defn- file-tokens [task]
  (into #{} (map :token) (:views (task/spec task))))

(defn designated-tokens
  "Jetons des fichiers que désignent les `tasks` qui peuvent encore en servir ou en écrire (ni
  `failed` ni `cancelled`)."
  [tasks]
  (into #{}
        (comp (remove (comp #{"failed" "cancelled"} status)) (mapcat file-tokens))
        tasks))

(def ^:private file-name-pattern #"([A-Za-z0-9_-]{32})\.(?:ndjson|csv|json)(?:\.part)?")

(defn orphan-names
  "Parmi `names` (noms d'un dossier d'export), ceux de la forme `<jeton>.<ext>[.part]` dont le jeton
  n'est pas dans `tokens`. Les autres noms ne sont pas des fichiers d'export : jamais jugés."
  [names tokens]
  (filterv #(when-let [[_ token] (re-matches file-name-pattern %)] (not (contains? tokens token))) names))

;; --- effets --------------------------------------------------------------------------------

(defn- export-tasks [admin-node]
  (->> @(d/pull-many admin-node (vec (d/type-list (d/db admin-node) "Task")))
       (filter #(= :viewdefinition-export (job-util/job-type %)))))

(defn- delete-task-files! [dir task]
  (storage/delete-files! dir (file-tokens task) (:format (task/spec task))))

(defn- fail! [{:keys [admin-node dir]} {:keys [id] :as task} message]
  (log/warn "Export task" id "declared failed:" message)
  @(job-util/update-job-with-retry admin-node 5 id #(job-util/fail-job % (ba/fault message)))
  (delete-task-files! dir task))

(defn- recover-task! [{:keys [job-scheduler] :as ctx} {:keys [id] :as task}]
  (case (recovery-action task)
    :resume (do (log/info "Resume export task" id)
                @(js/resume-job job-scheduler id)
                :resumed)
    :fail (do (fail! ctx task "The export was interrupted by an unclean shutdown and is not restarted.")
              :failed)
    (when (and (= "completed" (status task))
               (not-every? #(.isFile (storage/file (:dir ctx) % (:format (task/spec task)))) (file-tokens task)))
      (fail! ctx task "The export files were lost.")
      :failed)))

(defn recover!
  "Reprend les tâches d'export interrompues et déclare échouées celles qui ne peuvent pas l'être, à
  partir de l'état de `admin-node` au moment de l'appel. Rend le nombre de tâches `:resumed` et
  `:failed`. Une tâche en échec de reprise est journalisée, les autres suivent."
  [ctx]
  (->> (export-tasks (:admin-node ctx))
       (keep (fn [task]
               (try
                 (recover-task! ctx task)
                 (catch Exception e
                   (log/error "Recovery of export task" (:id task) "failed:" (ex-message e))))))
       (frequencies)
       (merge {:resumed 0 :failed 0})))

(defn sweep!
  "Supprime les fichiers orphelins du dossier, puis les tâches expirées. Rend le nombre de fichiers
  `:orphans` et de tâches `:expired` supprimés. Le dossier est listé *avant* les tâches : un fichier
  écrit pendant le passage a sa tâche, créée plus tôt."
  [{:keys [admin-node dir clock retention-seconds]}]
  (let [names (vec (.list ^java.io.File dir))
        now (Instant/now clock)
        tasks (export-tasks admin-node)
        {expired true live false :or {expired [] live []}} (group-by #(expired? % now retention-seconds) tasks)
        orphans (orphan-names names (designated-tokens live))]
    (run! #(.delete (io/file dir %)) orphans)
    (run! (fn [{:keys [id]}] @(d/transact admin-node [[:delete "Task" id]])) expired)
    (when (or (seq orphans) (seq expired))
      (log/info "Export cleanup:" (count expired) "expired task(s)," (count orphans) "orphan file(s)"))
    {:expired (count expired) :orphans (count orphans)}))

(defn- safe-sweep! [ctx]
  (try
    (sweep! ctx)
    (catch Throwable e
      (log/error "Export cleanup failed:" (ex-message e)))))

;; --- composant -----------------------------------------------------------------------------

(defmethod m/pre-init-spec :blaze.addon.sql-on-fhir.export.lifecycle/manager [_]
  (s/keys :req-un [::job-scheduler ::admin-node ::dir ::retention-seconds ::period-seconds
                   ::scheduler ::clock]))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.export.lifecycle/manager
  [_ {:keys [dir period-seconds scheduler] :as config}]
  (log/info "Init ViewDefinition export lifecycle, directory" dir "- cleanup every" period-seconds "s")
  (let [ctx (assoc config :dir (io/file dir))]
    (log/info "Export recovery:" (try (recover! ctx) (catch Throwable e (log/error "Export recovery failed:" (ex-message e)))))
    (safe-sweep! ctx)
    (sched/schedule-at-fixed-rate scheduler #(safe-sweep! ctx)
                                  (time/seconds period-seconds) (time/seconds period-seconds))))

(defmethod ig/halt-key! :blaze.addon.sql-on-fhir.export.lifecycle/manager
  [_ sweeping]
  (log/info "Stopping ViewDefinition export lifecycle...")
  (sched/cancel sweeping false))
