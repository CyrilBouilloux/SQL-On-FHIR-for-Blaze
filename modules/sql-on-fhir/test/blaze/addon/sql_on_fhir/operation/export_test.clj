(ns blaze.addon.sql-on-fhir.operation.export-test
  "AS-01, AS-02 (IB-08, IB-11) : lancement de `$viewdefinition-export` sur deux nœuds en mémoire, l'ordonnanceur de
  Blaze et le gestionnaire de tâche du module : 202 et `Content-Location`, `Task` créée, refus sans `Task`."
  (:require
   [blaze.addon.sql-on-fhir.export.job]
   [blaze.addon.sql-on-fhir.export.resource :as resource]
   [blaze.addon.sql-on-fhir.export.systeme :as systeme]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.operation.export]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.job.util :as job-util]
   [blaze.test-util :as tu :refer [given-failed-future given-thrown]]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]
   [taoensso.timbre :as log])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)
(st/instrument)
(log/set-min-level! :info)

(test/use-fixtures :once su/registre-types)
(test/use-fixtures :each tu/fixture)

(def ^:private cle :blaze.addon.sql-on-fhir.operation.export/handler)

(def ^:private contexte (delay (fichier/contexte)))

(defn- lire [m] (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(defn- dossier-temporaire []
  (.toFile (Files/createTempDirectory "export-op" (make-array FileAttribute 0))))

(defn- sur-systeme
  "Appelle `f` avec le gestionnaire du lancement, les nœuds, l'ordonnanceur et le dossier d'export."
  [f]
  (let [dir (dossier-temporaire)]
    (try
      (let [sys (ig/init (systeme/config dir))]
        (try
          (let [main (:blaze.db.main/node sys)
                handler (ig/init-key cle {:base-url "http://h" :context-path "/fhir"
                                          :clock (:blaze.test/fixed-clock sys)})]
            @(d/transact main [[:put (lire {:resourceType "Patient" :id "p1" :active true})]
                               [:put (lire {:resourceType "Patient" :id "p2" :active true})]
                               [:put (lire {:resourceType "Group" :id "g1" :type "person" :actual true
                                            :member [{:entity {:reference "Patient/p2"}}]})]])
            (f handler main (:blaze.db.admin/node sys) (:blaze/job-scheduler sys) dir))
          (finally (ig/halt! sys))))
      (finally
        (run! #(.delete ^java.io.File %) (.listFiles dir))
        (.delete dir)))))

(defmacro ^:private avec-systeme
  {:clj-kondo/lint-as 'clojure.core/fn}
  [params & body]
  `(sur-systeme (fn ~params ~@body)))

(def ^:private vue-patient
  {:resourceType "ViewDefinition" :status "active" :resource "Patient"
   :select [{:column [{:name "id" :path "id"}]}]})

(defn- vue [nom v] {:name "view" :part [{:name "name" :valueString nom} {:name "viewResource" :resource v}]})

(defn- requete [main scheduler body & [prefer]]
  {:blaze/db (d/db main)
   :blaze/job-scheduler scheduler
   :headers (if (= prefer :aucun) {} {"prefer" "respond-async"})
   :body body})

(defn- parametres [& parametres] {:resourceType "Parameters" :parameter (vec parametres)})

(defn- taches [admin] (d/type-total (d/db admin) "Task"))

(defn- attendre-fin [admin id]
  (let [fin (+ (System/currentTimeMillis) 20000)]
    (loop []
      (let [job @(job-util/pull-job admin id)]
        (if (or (#{"completed" "failed"} (-> job :status :value)) (< fin (System/currentTimeMillis)))
          job
          (do (Thread/sleep 50) (recur)))))))

(deftest init
  (testing "configuration incomplète : refusée"
    (given-thrown (ig/init {cle {}})
      :message := "Assertion failed on key :blaze.addon.sql-on-fhir.operation.export/handler when building system")))

(deftest lancement
  (avec-systeme [handler main admin scheduler dir]
                (let [reponse @(handler (requete main scheduler
                                                 (parametres (vue "pat" vue-patient) (vue "obs" (assoc vue-patient :resource "Observation"))
                                                             {:name "clientTrackingId" :valueString "suivi-1"}
                                                             {:name "_format" :valueCode "csv"}
                                                             {:name "header" :valueBoolean false})))
                      id (-> reponse :body :parameter first :value :value)
                      tache @(job-util/pull-job admin id)
                      spec (task/spec tache)]
                  (testing "202, Content-Location = lieu de suivi = location du corps"
                    (given reponse
                      :status := 202
                      [:headers "Content-Location"] := (resource/url {:base-url "http://h" :context-path "/fhir"} :status (:status-token spec))
                      [:body :fhir/type] := :fhir/Parameters)
                    (is (= (get-in reponse [:headers "Content-Location"])
                           (->> reponse :body :parameter (some #(when (= "location" (-> % :name :value)) (-> % :value :value))))))
                    (is (= ["exportId" "status" "location" "clientTrackingId"]
                           (map (comp :value :name) (-> reponse :body :parameter))))
                    (is (= "accepted" (-> reponse :body :parameter second :value :value))))
                  (testing "la Task porte la spécification : t de la base de la requête, format, vues, suivi"
                    (given spec
                      :t := (d/t (d/db main))
                      :format := "csv"
                      :header := false
                      :client-tracking-id := "suivi-1"
                      [:views count] := 2
                      [:views 0 :name] := "pat"
                      [:views 1 :name] := "obs")
                    (testing "quatre jetons distincts : suivi, résultat, un par fichier"
                      (is (= 4 (count (distinct (into [(:status-token spec) (:result-token spec)] (map :token) (:views spec))))))))
                  (testing "la tâche aboutit : un fichier par vue"
                    (given (attendre-fin admin id) [:status :value] := "completed")
                    (is (= 2 (count (.list dir))))))))

(def ^:private url-vue "http://example.org/fhir/ViewDefinition/pat")

(defn- bibliotheque
  "Écriture de la `Library` `id` qui range `vue` sous `url-vue|version`."
  [id version vue]
  (let [vue (assoc vue :url url-vue :version version)]
    [:put (lire {:resourceType "Library" :id id :url url-vue :version version :status "active"
                 :type {:coding [{:system "http://terminology.hl7.org/CodeSystem/library-type" :code "asset-collection"}]}
                 :content [{:contentType "application/json"
                            :data (.encodeToString (java.util.Base64/getEncoder)
                                                   (.getBytes (j/write-value-as-string vue) "UTF-8"))}]})]))

(defn- reference [nom r]
  {:name "view" :part [{:name "name" :valueString nom} {:name "viewReference" :valueReference {:reference r}}]})

(defn- lignes-du-fichier [^java.io.File dir]
  (let [[fichier & autres] (.listFiles dir)]
    (assert (nil? autres))
    (slurp fichier)))

(deftest resolution-au-lancement
  (avec-systeme [handler main admin scheduler dir]
                (let [vue-active (assoc-in vue-patient [:select 0 :column] [{:name "active" :path "active"}])]
                  @(d/transact main [(bibliotheque "l1" "1.0.0" vue-patient)])
                  (testing "la Task porte la vue résolue, comme une vue en ligne : pas de référence à résoudre plus tard"
                    (let [reponse @(handler (requete main scheduler (parametres (reference "pat" (str url-vue "|1.0.0")))))
                          id (-> reponse :body :parameter first :value :value)
                          spec (task/spec @(job-util/pull-job admin id))]
                      (given spec
                        [:views 0 :name] := "pat"
                        [:views 0 :view :resourceType] := "ViewDefinition"
                        [:views 0 :view :select] := (:select vue-patient)
                        [:views 0 :view-reference] := nil)
                      (testing "la Library remplacée (même url|version) : les fichiers restent ceux de la vue d'avant"
                        @(d/transact main [(bibliotheque "l1" "1.0.0" vue-active)])
                        (given (attendre-fin admin id) [:status :value] := "completed")
                        (let [contenu (lignes-du-fichier dir)]
                          (is (re-find #"\"id\"" contenu))
                          (is (not (re-find #"active" contenu)))))))
                  (testing "url seule, sur la Library remplacée : la nouvelle vue"
                    (let [reponse @(handler (requete main scheduler (parametres (reference "pat" url-vue))))
                          spec (task/spec @(job-util/pull-job admin (-> reponse :body :parameter first :value :value)))]
                      (given spec [:views 0 :view :select 0 :column 0 :name] := "active"))))))

(deftest nom-par-reference
  (avec-systeme [handler main admin scheduler _]
                @(d/transact main [(bibliotheque "l1" "1.0.0" (assoc vue-patient :name "rangee"))])
                (let [sans-nom (fn [r] {:name "view" :part [{:name "viewReference" :valueReference {:reference r}}]})
                      noms (fn [& vues]
                             (->> @(handler (requete main scheduler (apply parametres vues)))
                                  :body :parameter first :value :value
                                  (job-util/pull-job admin) deref task/spec :views (mapv :name)))]
                  (testing "sans view.name : le name de la vue résolue ; view.name l'emporte"
                    (is (= ["rangee"] (noms (sans-nom (str url-vue "|1.0.0")))))
                    (is (= ["a"] (noms (reference "a" url-vue)))))
                  (testing "vue résolue sans name : view-<rang>"
                    @(d/transact main [(bibliotheque "l2" "2.0.0" (dissoc vue-patient :name))])
                    (is (= ["view-1"] (noms (sans-nom (str url-vue "|2.0.0"))))))
                  (testing "noms égaux après résolution : 400, aucune tâche"
                    (let [avant (taches admin)]
                      (given-failed-future (handler (requete main scheduler (parametres (sans-nom (str url-vue "|1.0.0")) (vue "rangee" vue-patient))))
                        ::anom/category := ::anom/incorrect)
                      (is (= avant (taches admin))))))))

(deftest view-reference-refus-sans-tache
  (avec-systeme [handler main admin scheduler dir]
                @(d/transact main [(bibliotheque "l1" "1.0.0" vue-patient) (bibliotheque "l2" "2.0.0" vue-patient)])
                (let [ok (vue "pat" vue-patient)
                      refus (fn [libelle categorie requete]
                              (testing libelle
                                (given-failed-future (handler requete)
                                  ::anom/category := categorie
                                  :http/status := 400)
                                (is (zero? (taches admin)))
                                (is (= [] (vec (.list dir))))))]
                  (refus "forme sans schéma" ::anom/unsupported
                         (requete main scheduler (parametres (reference "a" "Library/l1"))))
                  (refus "introuvable" ::anom/not-found
                         (requete main scheduler (parametres (reference "a" (str url-vue "|9.9.9")))))
                  (refus "plusieurs correspondances" ::anom/incorrect
                         (requete main scheduler (parametres (reference "a" url-vue))))
                  (refus "une bonne référence ne rachète pas une mauvaise" ::anom/not-found
                         (requete main scheduler (parametres ok (reference "b" (str url-vue "|9.9.9"))))))))

(deftest filtres-et-format-par-defaut
  (avec-systeme [handler main admin scheduler _]
                (let [reponse @(handler (requete main scheduler
                                                 (parametres (vue "pat" vue-patient)
                                                             {:name "patient" :valueReference {:reference "Patient/p1"}}
                                                             {:name "group" :valueReference {:reference "Group/g1"}}
                                                             {:name "_since" :valueInstant "2020-01-01T00:00:00Z"})))
                      spec (task/spec @(job-util/pull-job admin (-> reponse :body :parameter first :value :value)))]
                  (given spec
                    :format := "ndjson"
                    :header := true
                    :patient := "p1"
                    :group := "g1"
                    :since := "2020-01-01T00:00:00Z"))))

(deftest refus-sans-tache
  (avec-systeme [handler main admin scheduler dir]
                (let [ok (vue "pat" vue-patient)
                      refus (fn [libelle categorie statut requete]
                              (testing libelle
                                (given-failed-future (handler requete)
                                  ::anom/category := categorie
                                  :http/status := statut)
                                (is (zero? (taches admin)))
                                (is (= [] (vec (.list dir))))))]
                  (refus "sans Prefer: respond-async" ::anom/incorrect nil
                         (requete main scheduler (parametres ok) :aucun))
                  (refus "sans corps" ::anom/incorrect nil
                         (requete main scheduler nil))
                  (refus "aucun view" ::anom/incorrect nil
                         (requete main scheduler (parametres)))
                  (refus "view sans viewResource" ::anom/incorrect nil
                         (requete main scheduler (parametres {:name "view" :part [{:name "name" :valueString "x"}]})))
                  (refus "viewReference sans référence" ::anom/incorrect nil
                         (requete main scheduler (parametres {:name "view" :part [{:name "viewReference"}]})))
                  (refus "source" ::anom/unsupported 400
                         (requete main scheduler (parametres ok {:name "source" :valueString "s"})))
                  (refus "_format=parquet" ::anom/unsupported 400
                         (requete main scheduler (parametres ok {:name "_format" :valueCode "parquet"})))
                  (refus "vue incorrecte, parmi une vue valide" ::anom/incorrect 422
                         (requete main scheduler (parametres ok (vue "mauvaise" (assoc-in vue-patient [:select 0 :column 0 :path] "id.(")))))
                  (refus "vue non supportée" ::anom/unsupported 422
                         (requete main scheduler (parametres (vue "x" (assoc-in vue-patient [:select 0 :column 0 :path] "active implies true")))))
                  (refus "patient absent" ::anom/not-found 400
                         (requete main scheduler (parametres ok {:name "patient" :valueReference {:reference "Patient/zz"}})))
                  (refus "groupe absent" ::anom/not-found 400
                         (requete main scheduler (parametres ok {:name "group" :valueReference {:reference "Group/zz"}}))))))
