(ns blaze.addon.sql-on-fhir.export.lifecycle-test
  "AS-10 : cycle de vie de l'export. Reprise au démarrage (R1 : `in-progress` sans exécution vivante →
  échouée ; arrêt ordonné → reprise), expiration des `Task` terminées et nettoyage des orphelins.
  Sur deux nœuds en mémoire et l'ordonnanceur de tâches de Blaze."
  (:require
   [blaze.addon.sql-on-fhir.export.job]
   [blaze.addon.sql-on-fhir.export.lifecycle :as lifecycle]
   [blaze.addon.sql-on-fhir.export.lifecycle-spec]
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [blaze.addon.sql-on-fhir.export.systeme :as systeme]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.export.token :as token]
   [blaze.addon.sql-on-fhir.test-util :as su :refer [ret-conforme?]]
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.anomaly :as ba]
   [blaze.async.comp :as ac]
   [blaze.db.api :as d]
   [blaze.executors :as ex]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.spec.type :as type]
   [blaze.job-scheduler :as js]
   [blaze.job.util :as job-util]
   [blaze.module :as m]
   [blaze.test-util :as tu :refer [given-thrown]]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]
   [taoensso.timbre :as log])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Clock Instant OffsetDateTime ZoneOffset]))

(set! *warn-on-reflection* true)
(st/instrument)
(log/set-min-level! :info)

(test/use-fixtures :once su/registre-types)
(test/use-fixtures :each tu/fixture)

(def ^:private contexte (delay (fichier/contexte)))

(def ^:private vue-patient
  {:resourceType "ViewDefinition" :status "active" :resource "Patient"
   :select [{:column [{:name "id" :path "id"}]}]})

(defn- noms [^java.io.File dir] (vec (sort (.list dir))))

(defn- sur-systeme [f]
  (let [dir (.toFile (Files/createTempDirectory "export-cycle" (make-array FileAttribute 0)))]
    (try
      (let [sys (ig/init (systeme/config dir))]
        (try (f sys dir) (finally (ig/halt! sys))))
      (finally
        (run! #(.delete ^java.io.File %) (.listFiles dir))
        (.delete dir)))))

(defmacro ^:private avec-systeme
  {:clj-kondo/lint-as 'clojure.core/fn}
  [params & body]
  `(sur-systeme (fn ~params ~@body)))

(defn- spec-de-tache [main]
  {:t (d/t (d/db main)) :format "ndjson" :header true
   :views [{:name "pat" :token (token/new-token) :view vue-patient}]
   :status-token (token/new-token) :result-token (token/new-token)})

(defn- tache
  "La `Task` `id` de `spec` à `statut` (et `raison`, code du `statusReason`)."
  [id spec statut & [raison]]
  (cond-> (-> (task/new-task (assoc spec :authored-on (OffsetDateTime/parse "2026-10-12T10:00:00Z")))
              (assoc :id id :status (type/code statut)))
    raison (assoc :statusReason (case raison
                                  "orderly-shutdown" job-util/orderly-shut-down-status-reason
                                  "paused" job-util/paused-status-reason
                                  "started" job-util/started-status-reason))))

(defn- ecrire! [node & ressources] @(d/transact node (mapv #(vector :create %) ressources)))

(defn- fichier-de ^java.io.File [dir spec]
  (let [token (:token (first (:views spec)))]
    (storage/write-file! dir token "ndjson" #(.write ^java.io.Writer % "{\"id\":\"p1\"}\n"))
    (storage/file dir token "ndjson")))

(defn- relire [node id] @(job-util/pull-job node id))

(defn- attendre-statut [node id statut]
  (let [fin (+ (System/currentTimeMillis) 20000)]
    (loop []
      (let [job (relire node id)]
        (if (or (= statut (-> job :status :value)) (< fin (System/currentTimeMillis)))
          job
          (do (Thread/sleep 50) (recur)))))))

(defn- instant [s] (Instant/ofEpochSecond s))

(def ^:private t-epoch
  "Les écritures des nœuds de test portent l'instant 1970-01-01T00:00:00Z."
  (instant 0))

;; --- fonctions pures ---------------------------------------------------------------------

(deftest expiration
  (let [spec {:t 1 :format "ndjson" :header true :views [] :status-token "s" :result-token "r"}
        avec-date (fn [statut] (-> (tache "x" spec statut)
                                   (assoc :meta {:lastUpdated (type/instant (OffsetDateTime/of 1970 1 1 0 0 0 0 ZoneOffset/UTC))})))]
    (testing "tâche terminée (completed, failed, cancelled) : expirée à lastUpdated + conservation"
      (doseq [statut ["completed" "failed" "cancelled"]]
        (is (false? (lifecycle/expired? (avec-date statut) (instant 59) 60)) statut)
        (is (true? (lifecycle/expired? (avec-date statut) (instant 60) 60)) statut)
        (is (true? (lifecycle/expired? (avec-date statut) (instant 600) 60)) statut)))
    (testing "tâche non terminée : jamais expirée"
      (doseq [statut ["ready" "in-progress" "on-hold"]]
        (is (false? (lifecycle/expired? (avec-date statut) (instant 100000) 60)) statut)))))

(deftest action-de-reprise
  (let [spec {:t 1 :format "ndjson" :header true :views [] :status-token "s" :result-token "r"}
        action #(lifecycle/recovery-action (tache "x" spec %1 %2))]
    (testing "arrêt ordonné : reprise"
      (is (= :resume (action "on-hold" "orderly-shutdown"))))
    (testing "R1 : en cours sans exécution vivante : échouée, pas relancée"
      (is (= :fail (action "in-progress" "started")))
      (is (= :fail (action "in-progress" nil))))
    (testing "le reste est laissé"
      (is (nil? (action "on-hold" "paused")))
      (doseq [statut ["ready" "completed" "failed" "cancelled"]]
        (is (nil? (action statut nil)) statut)))))

(defn- spec-pure [& tokens]
  {:t 1 :format "ndjson" :header true :status-token (token/new-token) :result-token (token/new-token)
   :views (mapv (fn [t] {:name t :token t :view vue-patient}) tokens)})

(deftest jetons-designes-et-orphelins
  (let [tasks [(tache "1" (spec-pure "AAA" "BBB") "completed")
               (tache "2" (spec-pure "CCC") "in-progress" "started")
               (tache "3" (spec-pure "DDD") "failed")
               (tache "4" (spec-pure "EEE") "cancelled")]]
    (testing "désignent leurs fichiers : les tâches qui peuvent encore en servir ou en écrire"
      (is (= #{"AAA" "BBB" "CCC"} (lifecycle/designated-tokens tasks))))
    (testing "orphelin : un nom de fichier d'export que personne ne désigne"
      (let [a (token/new-token) b (token/new-token) c (token/new-token)]
        (is (= [(str b ".csv") (str c ".json.part")]
               (lifecycle/orphan-names [(str a ".ndjson") (str b ".csv") (str c ".json.part") (str a ".json.part")
                                        "README" "ailleurs" "court.ndjson" (str a ".txt")]
                                       #{a})))
        (testing "seuls les noms de la forme <jeton>.<ext>[.part] sont jugés"
          (is (= [] (lifecycle/orphan-names ["README" "ailleurs" "court.ndjson" (str a ".txt")] #{}))))))))

;; --- reprise au démarrage ----------------------------------------------------------------

(defn- ctx-reprise [systeme dir]
  {:admin-node (:blaze.db.admin/node systeme) :job-scheduler (:blaze/job-scheduler systeme) :dir dir})

(deftest reprise-au-demarrage
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)
                      admin (:blaze.db.admin/node systeme)
                      _ @(d/transact main [[:put (fhir-spec/parse-json @contexte (j/write-value-as-string {:resourceType "Patient" :id "p1"}))]])
                      [ordonnee brutale perdue intacte en-attente] (repeatedly 5 #(spec-de-tache main))]
                  (ecrire! admin
                           (tache "ordonnee" ordonnee "on-hold" "orderly-shutdown")
                           (tache "brutale" brutale "in-progress" "started")
                           (tache "perdue" perdue "completed")
                           (tache "intacte" intacte "completed")
                           (tache "en-attente" en-attente "on-hold" "paused"))
                  (fichier-de dir intacte)
                  (fichier-de dir brutale)
                  (let [bilan (lifecycle/recover! (ctx-reprise systeme dir))]
                    (testing "bilan"
                      (is (= {:resumed 1 :failed 2} bilan))
                      (is (ret-conforme? `lifecycle/recover! bilan)))
                    (testing "arrêt ordonné : reprise, l'export aboutit"
                      (let [job (attendre-statut admin "ordonnee" "completed")]
                        (given job [:status :value] := "completed")
                        (is (.isFile (storage/file dir (:token (first (:views ordonnee))) "ndjson")))))
                    (testing "R1 : tâche en cours sans exécution : échouée, ses fichiers partiels supprimés"
                      (let [job (relire admin "brutale")]
                        (given job
                          [:status :value] := "failed"
                          job-util/error-category := ::anom/fault)
                        (is (some? (job-util/error-msg job)))
                        (is (not (.exists (storage/file dir (:token (first (:views brutale))) "ndjson"))))))
                    (testing "terminée dont le fichier manque : échouée, jamais une location à 404 sous `completed`"
                      (given (relire admin "perdue")
                        [:status :value] := "failed"))
                    (testing "terminée dont le fichier est là, ou mise en attente par un autre motif : intactes"
                      (given (relire admin "intacte") [:status :value] := "completed")
                      (is (.isFile (storage/file dir (:token (first (:views intacte))) "ndjson")))
                      (given (relire admin "en-attente") [:status :value] := "on-hold"))))))

;; --- pannes ------------------------------------------------------------------------------------

(deftest reprise-qui-echoue
  (testing "la reprise d'une tâche échoue : journalisée, les autres tâches suivent, rien n'est compté"
    (avec-systeme [systeme dir]
                  (let [main (:blaze.db.main/node systeme)
                        admin (:blaze.db.admin/node systeme)
                        spec (spec-de-tache main)]
                    (ecrire! admin (tache "ordonnee" spec "on-hold" "orderly-shutdown"))
                    (with-redefs [js/resume-job (fn [_ _] (ac/completed-future (ba/fault "panne")))]
                      (is (= {:resumed 0 :failed 0} (lifecycle/recover! (ctx-reprise systeme dir)))))
                    (given (relire admin "ordonnee") [:status :value] := "on-hold")))))

(deftest nettoyage-qui-echoue
  (testing "un passage qui lève est journalisé, le composant démarre et reste arrêtable"
    (avec-systeme [systeme dir]
                  (let [scheduler (ex/scheduled-pool 1 "test-panne-%d")
                        config {:job-scheduler (:blaze/job-scheduler systeme)
                                :admin-node (:blaze.db.admin/node systeme)
                                :dir (str dir) :retention-seconds 3600 :period-seconds 1
                                :scheduler scheduler :clock (Clock/fixed t-epoch ZoneOffset/UTC)}]
                    (try
                      (with-redefs [lifecycle/sweep! (fn [_] (throw (ex-info "panne" {})))]
                        (let [gestionnaire (ig/init-key ::lifecycle/manager config)]
                          (Thread/sleep 1200)
                          (ig/halt-key! ::lifecycle/manager gestionnaire)
                          (is (some? gestionnaire))))
                      (finally (ex/shutdown! scheduler)))))))

;; --- expiration et nettoyage ----------------------------------------------------------------

(defn- ctx-nettoyage [systeme dir retention secondes]
  {:admin-node (:blaze.db.admin/node systeme) :dir dir :retention-seconds retention
   :clock (Clock/fixed (instant secondes) ZoneOffset/UTC)})

(defn- jeton-ids [admin]
  (into #{} (map :id) @(d/pull-many admin (vec (d/type-list (d/db admin) "Task")))))

(deftest nettoyage
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)
                      admin (:blaze.db.admin/node systeme)
                      [terminee ratee annulee vivante] (repeatedly 4 #(spec-de-tache main))
                      orphelin (token/new-token)]
                  (ecrire! admin
                           (tache "terminee" terminee "completed")
                           (tache "ratee" ratee "failed")
                           (tache "annulee" annulee "cancelled")
                           (tache "vivante" vivante "on-hold" "paused"))
                  (let [^java.io.File f-terminee (fichier-de dir terminee)
                        ^java.io.File f-vivante (fichier-de dir vivante)]
                    (storage/write-file! dir orphelin "csv" #(.write ^java.io.Writer % "x"))
                    (spit (io/file dir (str orphelin ".json.part")) "x")
                    (spit (io/file dir "README") "autre")
                    (.mkdirs (io/file dir "ailleurs"))
                    (testing "avant la fin de la conservation : seuls les orphelins partent"
                      (let [bilan (lifecycle/sweep! (ctx-nettoyage systeme dir 60 59))]
                        (is (= {:expired 0 :orphans 2} bilan))
                        (is (ret-conforme? `lifecycle/sweep! bilan))
                        (is (= #{"terminee" "ratee" "annulee" "vivante"} (jeton-ids admin)))
                        (is (.isFile f-terminee))
                        (is (= ["README" "ailleurs"] (filterv #(not (re-matches #"[A-Za-z0-9_-]{32}\..*" %)) (noms dir))))))
                    (testing "à lastUpdated + conservation : tâches terminées et leurs fichiers disparaissent"
                      (let [bilan (lifecycle/sweep! (ctx-nettoyage systeme dir 60 60))]
                        (is (= {:expired 3 :orphans 1} bilan))
                        (is (= #{"vivante"} (jeton-ids admin)))
                        (is (not (.exists f-terminee)))))
                    (testing "la tâche non terminée garde ses fichiers, les fichiers étrangers sont laissés"
                      (is (.isFile f-vivante))
                      (is (= (sort ["README" "ailleurs" (.getName f-vivante)]) (noms dir))))))))

;; --- composant -----------------------------------------------------------------------------

(deftest gestionnaire
  (avec-systeme [systeme dir]
                (let [orphelin #(storage/write-file! dir (token/new-token) "csv" (fn [^java.io.Writer w] (.write w "x")))
                      scheduler (ex/scheduled-pool 1 "test-cycle-%d")
                      config {:job-scheduler (:blaze/job-scheduler systeme)
                              :admin-node (:blaze.db.admin/node systeme)
                              :dir (str dir) :retention-seconds 3600 :period-seconds 1
                              :scheduler scheduler :clock (Clock/fixed t-epoch ZoneOffset/UTC)}]
                  (try
                    (orphelin)
                    (let [gestionnaire (ig/init-key ::lifecycle/manager config)]
                      (try
                        (testing "au démarrage : reprise puis nettoyage"
                          (is (= [] (noms dir))))
                        (testing "puis à chaque période"
                          (orphelin)
                          (let [fin (+ (System/currentTimeMillis) 5000)]
                            (while (and (seq (noms dir)) (< (System/currentTimeMillis) fin)) (Thread/sleep 50)))
                          (is (= [] (noms dir))))
                        (finally (ig/halt-key! ::lifecycle/manager gestionnaire)))
                      (testing "arrêté : plus de nettoyage"
                        (orphelin)
                        (Thread/sleep 1500)
                        (is (= 1 (count (noms dir))))))
                    (finally (ex/shutdown! scheduler))))))

(deftest configuration-refusee
  (testing "configuration incomplète : refusée au démarrage"
    (given-thrown (ig/init {::lifecycle/manager {}})
      :message := "Assertion failed on key :blaze.addon.sql-on-fhir.export.lifecycle/manager when building system"))
  (testing "conservation et période : entiers ≥ 1 ; dossier : chemin absolu"
    (avec-systeme [systeme _]
                  (let [ok {:job-scheduler (:blaze/job-scheduler systeme) :admin-node (:blaze.db.admin/node systeme)
                            :dir "/x" :retention-seconds 1 :period-seconds 1
                            :scheduler (:blaze/scheduler systeme) :clock (Clock/systemUTC)}
                        invalide? #(some? (s/explain-data (m/pre-init-spec ::lifecycle/manager) %))]
                    (is (not (invalide? ok)) "témoin : valide")
                    (doseq [[k v] [[:retention-seconds 0] [:period-seconds 0] [:dir "export"] [:retention-seconds "60"]]]
                      (is (invalide? (assoc ok k v)) (str k " " v)))))))
