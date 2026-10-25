(ns blaze.addon.sql-on-fhir.export.job-test
  "AS-03, AS-04 : tâche de fond de l'export, sur deux nœuds en mémoire du jar et l'ordonnanceur de
  tâches de Blaze. `t` est celui de la `Task` ; les écritures qui suivent n'entrent pas dans les fichiers."
  (:refer-clojure :exclude [compile])
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.addon.sql-on-fhir.export.job :as job]
   [blaze.addon.sql-on-fhir.export.job-spec]
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [blaze.addon.sql-on-fhir.export.systeme :as systeme]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.export.token :as token]
   [blaze.addon.sql-on-fhir.serializer :as serializer]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.compile :refer [compile]]
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.spec.type :as type]
   [blaze.job-scheduler :as js]
   [blaze.job-scheduler.protocols :as p]
   [blaze.job.util :as job-util]
   [blaze.module :as m]
   [blaze.test-util :as tu :refer [given-thrown]]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]
   [taoensso.timbre :as log])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time OffsetDateTime]))

(set! *warn-on-reflection* true)
(st/instrument)
(log/set-min-level! :info)

(test/use-fixtures :once su/registre-types)
(test/use-fixtures :each tu/fixture)

(def ^:private contexte (delay (fichier/contexte)))

(defn- lire [m] (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(defn- patient [id & [m]] [:put (lire (merge {:resourceType "Patient" :id id :active true} m))])

(defn- observation [id sujet statut]
  [:put (lire {:resourceType "Observation" :id id :status statut :code {:text "x"}
               :subject {:reference (str "Patient/" sujet)}})])

(defn- dossier-temporaire []
  (.toFile (Files/createTempDirectory "export-job" (make-array FileAttribute 0))))

(defn- supprimer! [dir]
  (run! #(.delete ^java.io.File %) (.listFiles ^java.io.File dir))
  (.delete ^java.io.File dir))

(defn- sur-systeme
  "Appelle `f` avec le système de test (`systeme/config`) et son dossier d'export, puis nettoie."
  [f]
  (let [dir (dossier-temporaire)]
    (try
      (let [sys (ig/init (systeme/config dir))]
        (try
          (f sys dir)
          (finally (ig/halt! sys))))
      (finally (supprimer! dir)))))

(defmacro ^:private avec-systeme
  {:clj-kondo/lint-as 'clojure.core/fn}
  [params & body]
  `(sur-systeme (fn ~params ~@body)))

(def ^:private vue-patient
  {:resourceType "ViewDefinition" :status "active" :resource "Patient"
   :select [{:column [{:name "id" :path "id"} {:name "actif" :path "active"}]}]})

(def ^:private vue-observation
  {:resourceType "ViewDefinition" :status "active" :resource "Observation"
   :select [{:column [{:name "id" :path "id"} {:name "statut" :path "status"}]}]})

(defn- ecrire! [node txs] @(d/transact node txs))

(defn- spec-de-tache [node format vues & [m]]
  (merge {:t (d/t (d/db node))
          :format format
          :header true
          :views (mapv (fn [[nom vue]] {:name nom :token (token/new-token) :view vue}) vues)
          :status-token (token/new-token)
          :result-token (token/new-token)}
         m))

(defn- creer-tache! [scheduler spec]
  @(js/create-job scheduler (task/new-task (assoc spec :authored-on (OffsetDateTime/parse "2026-10-12T10:00:00Z")))))

(defn- attendre-fin
  "Tâche `id` relue jusqu'à `completed` ou `failed` (au plus 20 s)."
  [admin-node id]
  (let [fin (+ (System/currentTimeMillis) 20000)]
    (loop []
      (let [job @(job-util/pull-job admin-node id)]
        (if (or (#{"completed" "failed"} (-> job :status :value)) (< fin (System/currentTimeMillis)))
          job
          (do (Thread/sleep 50) (recur)))))))

(defn- noms [^java.io.File dir] (vec (sort (.list dir))))

(defn- attendu [format vue db & [header?]]
  (let [compiled (compile vue)]
    (:body (serializer/serialize format (:schema compiled) (ds/rows compiled db {}) (not (false? header?))))))

(def ^:private donnees
  [(patient "p1") (patient "p2") (patient "p3")
   (observation "o1" "p1" "final") (observation "o2" "p2" "final")])

(deftest fichier-par-vue
  (doseq [format ["ndjson" "csv" "json"]]
    (testing format
      (avec-systeme [systeme dir]
                    (let [main (:blaze.db.main/node systeme)
                          admin (:blaze.db.admin/node systeme)]
                      (ecrire! main donnees)
                      (let [spec (spec-de-tache main format [["pat" vue-patient] ["obs" vue-observation]])
                            created (creer-tache! (:blaze/job-scheduler systeme) spec)
                            done (attendre-fin admin (:id created))
                            [{pat :token} {obs :token}] (:views spec)]
                        (given done
                          [:status :value] := "completed")
                        (testing "un fichier par vue, aucun .part"
                          (is (= (vec (sort [(str pat "." format) (str obs "." format)])) (noms dir))))
                        (testing "contenu = celui de serialize sur les lignes de la vue, au même t"
                          (is (= (attendu format vue-patient (d/db main)) (slurp (storage/file dir pat format))))
                          (is (= (attendu format vue-observation (d/db main)) (slurp (storage/file dir obs format)))))))))))

(deftest csv-sans-en-tete
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)
                      admin (:blaze.db.admin/node systeme)]
                  (ecrire! main donnees)
                  (let [spec (spec-de-tache main "csv" [["pat" vue-patient]] {:header false})
                        done (attendre-fin admin (:id (creer-tache! (:blaze/job-scheduler systeme) spec)))
                        contenu (slurp (storage/file dir (:token (first (:views spec))) "csv"))]
                    (given done [:status :value] := "completed")
                    (is (= (attendu "csv" vue-patient (d/db main) false) contenu))
                    (is (not (str/starts-with? contenu "id,")))))))

(deftest instant-fixe
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)
                      admin (:blaze.db.admin/node systeme)]
                  (ecrire! main [(patient "p1") (patient "p2")])
                  (let [spec (spec-de-tache main "ndjson" [["pat" vue-patient]])]
                    ;; écrit après le t de la tâche : une modifiée, une créée
                    (ecrire! main [(patient "p1" {:active false}) (patient "p9")])
                    (let [done (attendre-fin admin (:id (creer-tache! (:blaze/job-scheduler systeme) spec)))
                          lignes (->> (slurp (storage/file dir (:token (first (:views spec))) "ndjson"))
                                      str/split-lines
                                      (map #(j/read-value % j/keyword-keys-object-mapper))
                                      (sort-by :id))]
                      (given done [:status :value] := "completed")
                      (is (= [{:id "p1" :actif true} {:id "p2" :actif true}] lignes)))))))

(deftest filtres
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)
                      admin (:blaze.db.admin/node systeme)]
                  (ecrire! main donnees)
                  (let [spec (spec-de-tache main "ndjson" [["obs" vue-observation]] {:patient "p1"})
                        done (attendre-fin admin (:id (creer-tache! (:blaze/job-scheduler systeme) spec)))]
                    (given done [:status :value] := "completed")
                    (is (= "{\"id\":\"o1\",\"statut\":\"final\"}\n"
                           (slurp (storage/file dir (:token (first (:views spec))) "ndjson"))))))))

(deftest vue-qui-echoue-a-l-execution
  (testing "la tâche échoue, aucun fichier ne reste"
    (avec-systeme [systeme dir]
                  (let [main (:blaze.db.main/node systeme)
                        admin (:blaze.db.admin/node systeme)]
                    (ecrire! main donnees)
                    (let [mauvaise (assoc-in vue-observation [:select 0 :column 1 :path] "code")
                          spec (spec-de-tache main "ndjson" [["pat" vue-patient] ["obs" mauvaise]])
                          done (attendre-fin admin (:id (creer-tache! (:blaze/job-scheduler systeme) spec)))]
                      (given done
                        [:status :value] := "failed"
                        job-util/error-category := ::anom/incorrect)
                      (is (some? (job-util/error-msg done)))
                      (is (= [] (noms dir))))))))

(deftest reprise
  (testing "une tâche reprise (in-progress/resumed) repart de zéro et aboutit"
    (avec-systeme [systeme dir]
                  (let [main (:blaze.db.main/node systeme)
                        admin (:blaze.db.admin/node systeme)]
                    (ecrire! main donnees)
                    (let [spec (spec-de-tache main "csv" [["pat" vue-patient]])
                          pat (:token (first (:views spec)))
                          _ (storage/write-file! dir pat "csv" #(.write ^java.io.Writer % "partiel"))
                          task (-> (task/new-task (assoc spec :authored-on (OffsetDateTime/parse "2026-10-12T10:00:00Z")))
                                   (assoc :status #fhir/code "in-progress" :statusReason job-util/resumed-status-reason))
                          created @(js/create-job (:blaze/job-scheduler systeme) task)
                          done (attendre-fin admin (:id created))]
                      (given done [:status :value] := "completed")
                      (is (= (attendu "csv" vue-patient (d/db main)) (slurp (storage/file dir pat "csv")))))))))

(deftest annulation-du-gestionnaire
  (avec-systeme [systeme dir]
                (let [handler (:blaze.job/viewdefinition-export systeme)
                      spec (spec-de-tache (:blaze.db.main/node systeme) "ndjson" [["pat" vue-patient]])
                      pat (:token (first (:views spec)))
                      tache (task/new-task (assoc spec :authored-on (OffsetDateTime/parse "2026-10-12T10:00:00Z")))]
                  (storage/write-file! dir pat "ndjson" #(.write ^java.io.Writer % "x"))
                  (is (= tache @(p/-on-cancel handler tache)))
                  (testing "ses fichiers sont supprimés"
                    (is (= [] (noms dir)))))))

;; --- arrêt et interruption : fonctions du module, sur un nœud et un dossier ---------------------

(defn- contexte-export [systeme dir]
  {:main-node (:blaze.db.main/node systeme) :dir dir :db-sync-timeout 10000 :max-bytes 1073741824})

(defn- arreter-apres [n statut]
  (let [vues (atom 0)]
    (fn [] (when (= n (swap! vues inc)) (throw (ex-info "stop" {::job/stop statut}))))))

(deftest arret
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)]
                  (ecrire! main donnees)
                  (let [spec (spec-de-tache main "ndjson" [["pat" vue-patient] ["obs" vue-observation]])
                        ctx (contexte-export systeme dir)]
                    (testing "terminé"
                      (is (= :done (job/run-export ctx (fn []) spec)))
                      (is (= 2 (count (noms dir)))))
                    (testing "mis en attente (arrêt ordonné) : plus aucun fichier"
                      (is (= :paused (job/run-export ctx (arreter-apres 2 "on-hold") spec)))
                      (is (= [] (noms dir))))
                    (testing "annulé : plus aucun fichier"
                      (is (= :cancelled (job/run-export ctx (arreter-apres 1 "cancelled") spec)))
                      (is (= [] (noms dir))))
                    (testing "arrêt pour un autre statut : anomalie, plus aucun fichier"
                      (given (job/run-export ctx (arreter-apres 1 "completed") spec)
                        ::anom/category := ::anom/fault)
                      (is (= [] (noms dir))))
                    (testing "vue refusée par le moteur : anomalie rendue"
                      (given (job/run-export ctx (fn [])
                                             (assoc-in spec [:views 0 :view :select 0 :column 0 :path] "id.("))
                        ::anom/category := ::anom/incorrect)
                      (is (= [] (noms dir))))
                    (testing "patient absent : not-found"
                      (given (job/run-export ctx (fn []) (assoc spec :patient "zz"))
                        ::anom/category := ::anom/not-found))))))

(deftest init
  (testing "configuration incomplète : refusée au démarrage"
    (given-thrown (ig/init {:blaze.job/viewdefinition-export {}})
      :message := "Assertion failed on key :blaze.job/viewdefinition-export when building system"))
  (testing "plafond d'octets : entier ≥ 1, exigé"
    (avec-systeme [systeme dir]
                  (let [ok (-> (systeme/config dir) :blaze.job/viewdefinition-export
                               (assoc :main-node (:blaze.db.main/node systeme) :admin-node (:blaze.db.admin/node systeme)
                                      :executor (:blaze.addon.sql-on-fhir.export.job/executor systeme)
                                      :dir (str dir)))
                        invalide? #(some? (s/explain-data (m/pre-init-spec :blaze.job/viewdefinition-export) %))]
                    (is (not (invalide? ok)) "témoin : valide")
                    (doseq [v [0 -1 "8" nil]]
                      (is (invalide? (assoc ok :max-bytes v)) (str "max-bytes " (pr-str v))))
                    (is (invalide? (dissoc ok :max-bytes)))
                    (is (invalide? (assoc ok :dir "export")))))))

;; --- plafond d'espace --------------------------------------------------------------------------

(defn- octets [^java.io.File dir] (storage/used-bytes dir))

(deftest plafond-d-espace
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)
                      _ (ecrire! main donnees)
                      spec (spec-de-tache main "ndjson" [["pat" vue-patient] ["obs" vue-observation]])
                      ctx (contexte-export systeme dir)
                      [pat] (map :token (:views spec))]
                  (is (= :done (job/run-export ctx (fn []) spec)))
                  (let [total (octets dir)
                        taille-pat (.length (storage/file dir pat "ndjson"))]
                    (run! #(.delete ^java.io.File %) (.listFiles dir))
                    (testing "exactement au plafond : terminé"
                      (is (= :done (job/run-export (assoc ctx :max-bytes total) (fn []) spec)))
                      (is (= total (octets dir))))
                    (run! #(.delete ^java.io.File %) (.listFiles dir))
                    (testing "un octet de moins : échoué, conflit, aucun fichier ne reste"
                      (given (job/run-export (assoc ctx :max-bytes (dec total)) (fn []) spec)
                        ::anom/category := ::anom/conflict
                        ::anom/message :# ".*limit.*")
                      (is (= [] (noms dir))))
                    (testing "dépassement en cours d'écriture du deuxième fichier (vérification à chaque caractère)"
                      (given (job/run-export (assoc ctx :max-bytes (+ taille-pat 5) :step 1) (fn []) spec)
                        ::anom/category := ::anom/conflict)
                      (is (= [] (noms dir))))
                    (testing "dossier déjà plein avant la première écriture : rien n'est écrit, pas même un .part"
                      (spit (io/file dir "autre") "0123456789")
                      (given (job/run-export (assoc ctx :max-bytes 5) (fn []) spec)
                        ::anom/category := ::anom/conflict)
                      (is (= ["autre"] (noms dir))))))))

(deftest plafond-d-espace-d-une-tache
  (let [dir (.toFile (Files/createTempDirectory "export-plafond" (make-array FileAttribute 0)))
        systeme (ig/init (systeme/config dir {:max-bytes 10}))]
    (try
      (let [main (:blaze.db.main/node systeme)
            admin (:blaze.db.admin/node systeme)]
        (ecrire! main donnees)
        (let [spec (spec-de-tache main "ndjson" [["pat" vue-patient]])
              done (attendre-fin admin (:id (creer-tache! (:blaze/job-scheduler systeme) spec)))]
          (testing "la tâche échoue avec le plafond dans son message, sans fichier"
            (given done
              [:status :value] := "failed"
              job-util/error-category := ::anom/conflict)
            (is (str/includes? (job-util/error-msg done) "10"))
            (is (= [] (noms dir))))))
      (finally
        (ig/halt! systeme)
        (run! #(.delete ^java.io.File %) (.listFiles dir))
        (.delete dir)))))

(deftest sonde-du-statut
  (sur-systeme
   (fn [systeme _dir]
     (let [main (:blaze.db.main/node systeme)
           admin (:blaze.db.admin/node systeme)
           tache (fn [statut raison]
                   (let [spec (spec-de-tache main "ndjson" [["pat" vue-patient]])]
                     (-> (task/new-task (assoc spec :authored-on (OffsetDateTime/parse "2026-10-12T10:00:00Z")))
                         (assoc :status (type/code statut) :statusReason raison))))
           creee @(js/create-job (:blaze/job-scheduler systeme) (tache "in-progress" job-util/paused-status-reason))
           check! (job/status-check admin (:id creee))]
       (testing "la tâche est in-progress : l'export continue"
         (is (nil? (check!))))
       (let [id (:id creee)
             met-en-attente (fn [job] (assoc job :status #fhir/code "on-hold"))]
         @(job-util/update-job-with-retry admin 5 id met-en-attente)
         (testing "avant la période d'interrogation : aucune lecture, aucun arrêt"
           (is (nil? (check!))))
         (testing "après : la tâche n'est plus in-progress, l'export s'arrête sur son statut"
           (Thread/sleep 150)
           (given (try (check!) (catch clojure.lang.ExceptionInfo e (ex-data e)))
             ::job/stop := "on-hold")))))))

(defn- tache-en-etat
  "Tâche d'export créée directement dans l'état `statut`, sans que l'ordonnanceur la prenne
  (`on-hold`, `cancelled` sans demande), avec un fichier partiel dans `dir`."
  [systeme dir statut]
  (let [main (:blaze.db.main/node systeme)
        _ (ecrire! main donnees)
        spec (spec-de-tache main "ndjson" [["pat" vue-patient]])
        task (-> (task/new-task (assoc spec :authored-on (OffsetDateTime/parse "2026-10-12T10:00:00Z")))
                 (assoc :status (type/code statut)))]
    (storage/write-file! dir (:token (first (:views spec))) "ndjson" #(.write ^java.io.Writer % "partiel"))
    @(js/create-job (:blaze/job-scheduler systeme) task)))

(deftest reprise-d-une-tache-mise-en-attente
  (testing "une tâche on-hold qu'on reprend sans qu'elle soit in-progress s'arrête : ni fichier, ni changement de statut"
    (sur-systeme
     (fn [systeme dir]
       (let [handler (:blaze.job/viewdefinition-export systeme)
             tache (tache-en-etat systeme dir "on-hold")]
         (given @(p/-on-resume handler tache)
           [:status :value] := "on-hold")
         (is (= [] (noms dir))))))))

(deftest reprise-d-une-tache-annulee
  (testing "une tâche annulée qu'on reprend : fin d'annulation inscrite, aucun fichier"
    (sur-systeme
     (fn [systeme dir]
       (let [handler (:blaze.job/viewdefinition-export systeme)
             tache (tache-en-etat systeme dir "cancelled")
             done @(p/-on-resume handler tache)]
         (given done
           [:status :value] := "cancelled"
           job-util/cancelled-sub-status := "finished")
         (is (= [] (noms dir))))))))

(deftest ecriture-impossible
  (testing "une panne d'écriture (dossier sous un fichier) : anomalie fault, aucun fichier"
    (sur-systeme
     (fn [systeme dir]
       (let [main (:blaze.db.main/node systeme)
             _ (ecrire! main donnees)
             spec (spec-de-tache main "ndjson" [["pat" vue-patient]])
             fichier (io/file dir "occupe")
             _ (spit fichier "x")]
         (given (job/run-export {:main-node main :dir (io/file fichier "sous-dossier") :db-sync-timeout 10000 :max-bytes 1073741824}
                                (fn []) spec)
           ::anom/category := ::anom/fault))))))

(deftest source-interruptible
  (let [lignes (reify clojure.lang.IReduceInit
                 (reduce [_ f init] (reduce f init [1 2 3 4])))
        appels (atom 0)]
    (testing "chaque ligne passe par le contrôle"
      (is (= [1 2 3 4] (into [] (job/interruptible #(swap! appels inc) lignes))))
      (is (= 4 @appels)))
    (testing "le consommateur interrompt par reduced"
      (is (= [1 2] (into [] (take 2) (job/interruptible (fn []) lignes)))))
    (testing "le contrôle interrompt en lançant"
      (is (thrown? Exception (into [] (job/interruptible (arreter-apres 3 "x") lignes)))))))

;; --- course d'annulation (F-010, T-052) -----------------------------------------------------------

(defn- requete [methode genre jeton]
  {:request-method methode :uri (str "/fhir/__export/" (name genre) "/" jeton)})

(defn- reponse
  "Statut de la réponse des routes de l'export à `requete`, 10 s au plus."
  [routes requete]
  (let [r (deref (routes requete) 10000 ::delai)]
    (if (= ::delai r) ::delai (:status r))))

(defn- stable
  "Attend que le nœud d'administration n'ait plus écrit depuis 600 ms (au plus 10 s)."
  [admin]
  (let [fin (+ (System/currentTimeMillis) 10000)]
    (loop [t (d/t (d/db admin)) depuis (System/currentTimeMillis)]
      (Thread/sleep 50)
      (let [t' (d/t (d/db admin)) now (System/currentTimeMillis)]
        (cond
          (< fin now) ::delai
          (not= t t') (recur t' now)
          (< 600 (- now depuis)) t'
          :else (recur t depuis))))))

(defn- course-observee
  "Force l'ordre « dernière sonde de statut → DELETE du lieu de suivi répondu 202 → écriture du statut
  final » : un export d'une vue d'une ligne n'est sondé qu'une fois, avant la ligne ; la fin de
  l'écriture du fichier (`storage/write-file!`, fil d'export, une fois) attend le `DELETE`. Rend les
  valeurs observées après la fin de l'export."
  []
  (avec-systeme [systeme dir]
                (let [main (:blaze.db.main/node systeme)
                      admin (:blaze.db.admin/node systeme)
                      routes (:blaze.addon.sql-on-fhir.export.routes/handler systeme)
                      ecrire-fichier storage/write-file!
                      atteint (promise) libere (promise) pris (atom false)
                      bloque (fn [& args]
                               (let [r (apply ecrire-fichier args)]
                                 (when (compare-and-set! pris false true)
                                   (deliver atteint true)
                                   (deref libere 20000 nil))
                                 r))]
                  (ecrire! main [(patient "p1")])
                  (let [spec (spec-de-tache main "ndjson" [["pat" vue-patient]])
                        {:keys [status-token result-token]} spec
                        fichier (:token (first (:views spec)))]
                    (try
                      (with-redefs [storage/write-file! bloque]
                        (creer-tache! (:blaze/job-scheduler systeme) spec)
                        (if-not (deref atteint 10000 nil)
                          {:fin-de-fichier-atteinte false}
                          (let [avant (reponse routes (requete :get :status status-token))
                                suppression (reponse routes (requete :delete :status status-token))
                                ;; le gestionnaire d'annulation supprime les fichiers : on lui laisse 3 s
                                _ (loop [n 60] (when (and (seq (noms dir)) (pos? n)) (Thread/sleep 50) (recur (dec n))))
                                fichiers-avant-fin (noms dir)]
                            (deliver libere true)
                            (let [fin (stable admin)]
                              {:fin-de-fichier-atteinte true
                               :suivi-avant-delete avant
                               :delete suppression
                               :fichiers-avant-fin (count fichiers-avant-fin)
                               :stable-apres-fin (not= ::delai fin)
                               :suivi (reponse routes (requete :get :status status-token))
                               :resultat (reponse routes (requete :get :result result-token))
                               :fichier (reponse routes (requete :get :file fichier))
                               :fichiers-restants (count (noms dir))}))))
                      (finally (deliver libere true)))))))

(deftest course-d-annulation-pendant-la-fin-de-l-export
  (testing "DELETE répondu 202 entre la dernière sonde et le statut final : l'export n'est jamais présenté comme terminé"
    (let [o (course-observee)]
      (is (true? (:fin-de-fichier-atteinte o)) (str "observé " (pr-str o)))
      (is (= 202 (:suivi-avant-delete o)) "témoin : l'export est en cours avant le DELETE")
      (is (= 202 (:delete o)) "le DELETE du lieu de suivi est accepté")
      (is (true? (:stable-apres-fin o)))
      (is (= {:suivi 404 :resultat 404 :fichier 404 :fichiers-restants 0}
             (select-keys o [:suivi :resultat :fichier :fichiers-restants]))
          (str "observé après le DELETE 202 : lieu de suivi " (:suivi o) ", URL de résultat " (:resultat o)
               ", fichier " (:fichier o) ", fichiers restants " (:fichiers-restants o)
               " (attendu 404, 404, 404, 0)")))))
