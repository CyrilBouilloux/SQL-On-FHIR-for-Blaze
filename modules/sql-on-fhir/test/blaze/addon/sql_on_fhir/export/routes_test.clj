(ns blaze.addon.sql-on-fhir.export.routes-test
  "AS-06, AS-07, AS-09 (IB-13) : routes `/__export/{status,result,file}/<jeton>`, sur un nœud en mémoire."
  (:require
   [blaze.addon.sql-on-fhir.export.routes-spec]
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [blaze.addon.sql-on-fhir.export.task :as task]
   [blaze.addon.sql-on-fhir.export.token :as token]
   [blaze.addon.sql-on-fhir.noeud :as noeud]
   [blaze.anomaly :as ba]
   [blaze.async.comp :as ac]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.spec.type :as type]
   [blaze.fhir.structure-definition-repo]
   [blaze.fhir.test-util :refer [structure-definition-repo]]
   [blaze.fhir.writing-context]
   [blaze.job-scheduler :as js]
   [blaze.job.util :as job-util]
   [blaze.test-util :as tu :refer [given-thrown]]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time OffsetDateTime]))

(set! *warn-on-reflection* true)
(st/instrument)

(test/use-fixtures :each tu/fixture)

(def ^:private vue {:resourceType "ViewDefinition" :status "active" :resource "Patient"})

(defn- spec-de-tache []
  {:t 1 :format "ndjson" :header true
   :views [{:name "pat" :token (token/new-token) :view vue}]
   :client-tracking-id "suivi-1"
   :status-token (token/new-token) :result-token (token/new-token)})

(defn- tache [id spec statut]
  (-> (task/new-task (assoc spec :authored-on (OffsetDateTime/parse "2026-10-12T10:00:00Z")))
      (assoc :id id :status (type/code statut))))

(defn- dossier-temporaire []
  (.toFile (Files/createTempDirectory "export-routes" (make-array FileAttribute 0))))

(defn- sur-routes
  "Appelle `f` avec `route` (méthode, URI -> réponse ou nil), le dossier d'export et le nœud d'administration."
  [f]
  (let [systeme (noeud/demarrer)
        admin (:blaze.db/node systeme)
        dir (dossier-temporaire)
        handler (ig/init-key :blaze.addon.sql-on-fhir.export.routes/handler
                             {:admin-node admin :dir (str dir) :job-scheduler {:context {:node admin}}
                              :writing-context (ig/init-key :blaze.fhir/writing-context
                                                            {:structure-definition-repo structure-definition-repo})
                              :base-url "http://h" :context-path "/fhir"})]
    (try
      (f (fn [method uri] (some-> (handler {:request-method method :uri uri}) deref)) dir admin)
      (finally
        (run! #(.delete ^java.io.File %) (.listFiles dir))
        (.delete dir)
        (ig/halt! systeme)))))

(defmacro ^:private avec-routes
  {:clj-kondo/lint-as 'clojure.core/fn}
  [params & body]
  `(sur-routes (fn ~params ~@body)))

(defn- corps [reponse] (j/read-value (:body reponse) j/keyword-keys-object-mapper))

(defn- param [reponse nom]
  (first (filter #(= nom (:name %)) (:parameter (corps reponse)))))

(defn- ecrire! [admin & taches] (noeud/ecrire! admin (mapv #(vector :create %) taches)))

(defn- url [kind jeton] (str "/fhir/__export/" kind "/" jeton))

(deftest autres-routes
  (avec-routes [route _ _]
               (is (nil? (route :get "/fhir/metadata")))
               (is (nil? (route :get "/fhir/__export/inconnu/abc")))
               (is (nil? (route :get "/ailleurs/__export/status/abc")))))

(deftest jeton-inconnu
  (avec-routes [route _ admin]
               (let [spec (spec-de-tache)]
                 (ecrire! admin (tache "t1" spec "completed"))
                 (doseq [kind ["status" "result" "file"]
                         jeton [(token/new-token) "court" (subs (:status-token spec) 1) (str (:status-token spec) "x")]]
                   (testing (str kind " " jeton)
                     (let [r (route :get (url kind jeton))]
                       (given r :status := 404)
                       (given (corps r)
                         :resourceType := "OperationOutcome"
                         [:issue 0 :code] := "not-found"))))
                 (testing "le jeton d'une autre sorte d'URL ne sert pas"
                   (given (route :get (url "result" (:status-token spec))) :status := 404)
                   (given (route :get (url "status" (:result-token spec))) :status := 404)
                   (given (route :get (url "file" (:result-token spec))) :status := 404)))))

(deftest suivi
  (avec-routes [route _ admin]
               (doseq [[statut vu] {"ready" "accepted" "in-progress" "in-progress" "on-hold" "in-progress"}]
                 (testing statut
                   (let [spec (spec-de-tache)]
                     (ecrire! admin (tache (str "t-" statut) spec statut))
                     (let [r (route :get (url "status" (:status-token spec)))]
                       (given r
                         :status := 202
                         [:headers "Content-Type"] := "application/fhir+json;charset=utf-8")
                       (given (param r "status") :valueCode := vu)
                       (given (param r "exportId") :valueString := (str "t-" statut))
                       (given (param r "clientTrackingId") :valueString := "suivi-1")))))
               (doseq [statut ["completed" "failed"]]
                 (testing statut
                   (let [spec (spec-de-tache)]
                     (ecrire! admin (tache (str "t-" statut) spec statut))
                     (given (route :get (url "status" (:status-token spec)))
                       :status := 303
                       [:headers "Location"] := (str "http://h/fhir/__export/result/" (:result-token spec))))))
               (testing "annulée : 404"
                 (let [spec (spec-de-tache)]
                   (ecrire! admin (tache "t-annulee" spec "cancelled"))
                   (given (route :get (url "status" (:status-token spec))) :status := 404)))))

(deftest resultat
  (avec-routes [route _ admin]
               (testing "terminée : un output par vue"
                 (let [spec (spec-de-tache)
                       fichier (:token (first (:views spec)))]
                   (ecrire! admin (tache "t1" spec "completed"))
                   (let [r (route :get (url "result" (:result-token spec)))]
                     (given r :status := 200)
                     (given (param r "status") :valueCode := "completed")
                     (given (param r "exportId") :valueString := "t1")
                     (given (param r "output")
                       [:part 0 :valueString] := "pat"
                       [:part 1 :valueUri] := (str "http://h/fhir/__export/file/" fichier)))))
               (testing "échouée : 500 et OperationOutcome"
                 (let [spec (spec-de-tache)]
                   (ecrire! admin (job-util/fail-job (tache "t2" spec "failed") (ba/incorrect "vue illisible")))
                   (let [r (route :get (url "result" (:result-token spec)))]
                     (given r :status := 500)
                     (given (corps r)
                       :resourceType := "OperationOutcome"
                       [:issue 0 :diagnostics] := "vue illisible"))))
               (testing "pas terminée, ou annulée : 404"
                 (doseq [statut ["ready" "in-progress" "on-hold" "cancelled"]]
                   (let [spec (spec-de-tache)]
                     (ecrire! admin (tache (str "t-" statut) spec statut))
                     (given (route :get (url "result" (:result-token spec))) :status := 404))))))

(deftest fichier
  (avec-routes [route dir admin]
               (let [spec (spec-de-tache)
                     jeton (:token (first (:views spec)))]
                 (ecrire! admin (tache "t1" spec "completed"))
                 (testing "fichier absent du dossier : 404, jamais une location à 404 sous une tâche terminée sans que le module le sache"
                   (given (route :get (url "file" jeton)) :status := 404))
                 (storage/write-file! dir jeton "ndjson" #(.write ^java.io.Writer % "{\"id\":\"p1\"}\n"))
                 (let [r (route :get (url "file" jeton))]
                   (given r
                     :status := 200
                     [:headers "Content-Type"] := "application/x-ndjson"
                     [:headers "Content-Length"] := "12"
                     [:body slurp] := "{\"id\":\"p1\"}\n")))
               (testing "tâche pas terminée : 404 même si le fichier existe"
                 (let [spec (spec-de-tache)
                       jeton (:token (first (:views spec)))]
                   (ecrire! admin (tache "t2" spec "in-progress"))
                   (storage/write-file! dir jeton "ndjson" #(.write ^java.io.Writer % "x"))
                   (given (route :get (url "file" jeton)) :status := 404)))))

(deftest methodes
  (avec-routes [route _ admin]
               (let [spec (spec-de-tache)]
                 (ecrire! admin (tache "t1" spec "completed"))
                 (doseq [methode [:post :put :delete :head]
                         kind ["status" "result" "file"]
                         :when (not (and (= :delete methode) (= "status" kind)))]
                   (given (route methode (url kind (:status-token spec))) :status := 405))
                 (testing "DELETE du lieu de suivi est servi (voir `annulation`)"
                   (given (route :delete (url "status" (:status-token spec))) :status := 202)))))

(defn- pas-de-tache [admin]
  (empty? (into [] (d/type-list (d/db admin) "Task"))))

(deftest annulation
  (testing "tâche en cours : 202, annulée ; le lieu de suivi rend ensuite 404"
    (doseq [statut ["ready" "in-progress" "on-hold"]]
      (testing statut
        (avec-routes [route _ admin]
                     (let [spec (spec-de-tache)]
                       (ecrire! admin (tache "t1" spec statut))
                       (let [r (route :delete (url "status" (:status-token spec)))]
                         (given r :status := 202)
                         (is (nil? (:body r))))
                       (given @(job-util/pull-job admin "t1")
                         [:status :value] := "cancelled"
                         job-util/cancelled-sub-status := "requested")
                       (given (route :get (url "status" (:status-token spec))) :status := 404)
                       (given (route :get (url "result" (:result-token spec))) :status := 404))))))
  (testing "tâche terminée ou échouée : 202, la tâche et ses fichiers sont supprimés, tout rend ensuite 404"
    (doseq [statut ["completed" "failed"]]
      (testing statut
        (avec-routes [route dir admin]
                     (let [spec (spec-de-tache)
                           jeton (:token (first (:views spec)))]
                       (ecrire! admin (tache "t1" spec statut))
                       (storage/write-file! dir jeton "ndjson" #(.write ^java.io.Writer % "x"))
                       (given (route :delete (url "status" (:status-token spec))) :status := 202)
                       (is (pas-de-tache admin))
                       (is (= [] (vec (.list dir))))
                       (doseq [[kind jeton] [["status" (:status-token spec)] ["result" (:result-token spec)] ["file" jeton]]]
                         (given (route :get (url kind jeton)) :status := 404)))))))
  (testing "déjà annulée, jeton inconnu, jeton altéré : 404, rien n'est supprimé"
    (avec-routes [route _ admin]
                 (let [spec (spec-de-tache)]
                   (ecrire! admin (tache "t1" spec "cancelled"))
                   (doseq [jeton [(:status-token spec) (token/new-token) "court" (subs (:status-token spec) 1)]]
                     (given (route :delete (url "status" jeton))
                       :status := 404))
                   (is (not (pas-de-tache admin))))))
  (testing "elle se termine entre la lecture et l'annulation : 404"
    (avec-routes [route _ admin]
                 (let [spec (spec-de-tache)]
                   (ecrire! admin (tache "t1" spec "in-progress"))
                   (with-redefs [js/cancel-job (fn [_ _] (ac/completed-future (ba/conflict "terminée" :job/status "completed")))]
                     (given (route :delete (url "status" (:status-token spec))) :status := 404))))))

(deftest async-status-ferme
  (avec-routes [route _ admin]
               (let [spec (spec-de-tache)
                     autre (-> (tache "autre" (spec-de-tache) "in-progress")
                               (assoc :code (job-util/type-codeable-concept "re-index" "Re-Index")))]
                 (ecrire! admin (tache "t1" spec "in-progress") autre)
                 (testing "R2 : GET et DELETE pour une tâche d'export : 404 not-found ; la tâche n'est pas touchée"
                   (doseq [methode [:get :delete]]
                     (let [r (route methode "/fhir/__async-status/t1")]
                       (given r :status := 404)
                       (given (corps r)
                         :resourceType := "OperationOutcome"
                         [:issue 0 :code] := "not-found")))
                   (given @(job-util/pull-job admin "t1") [:status :value] := "in-progress"))
                 (testing "autre tâche, tâche inconnue, autre méthode, autre contexte : la requête passe à Blaze"
                   (doseq [[methode uri] [[:get "/fhir/__async-status/autre"] [:delete "/fhir/__async-status/autre"]
                                          [:get "/fhir/__async-status/inconnue"]
                                          [:post "/fhir/__async-status/t1"] [:get "/ailleurs/__async-status/t1"]
                                          [:get "/fhir/__async-status/"] [:get "/fhir/__async-status/a/b"]]]
                     (is (nil? (route methode uri)) (str methode " " uri)))
                   (given @(job-util/pull-job admin "autre") [:status :value] := "in-progress")))))

(deftest echec-sans-message
  (avec-routes [route _ admin]
               (let [spec (spec-de-tache)]
                 (ecrire! admin (tache "t1" spec "failed"))
                 (let [r (route :get (url "result" (:result-token spec)))]
                   (given r :status := 500)
                   (given (corps r) [:issue 0 :diagnostics] := "The export failed.")))))

(deftest panne-de-la-base
  (avec-routes [route _ _]
               (testing "la recherche échoue : 500 sans détail interne, jamais une exception vers le serveur"
                 (with-redefs [d/type-query (fn [& _] (ac/completed-future (ba/fault "secret interne")))]
                   (let [r (route :get (url "status" (token/new-token)))]
                     (given r :status := 500)
                     (given (corps r)
                       :resourceType := "OperationOutcome"
                       [:issue 0 :code] := "exception"
                       [:issue 0 :diagnostics] := "Internal error."))))
               (testing "l'encodage échoue aussi : le future échoue, l'enveloppe lève"
                 (with-redefs [fhir-spec/write-json-as-string (fn [& _] (ba/fault "encodage"))]
                   (is (thrown? java.util.concurrent.ExecutionException
                                (route :get (url "status" (token/new-token)))))))))

(deftest init
  (testing "configuration incomplète : refusée au démarrage"
    (given-thrown (ig/init {:blaze.addon.sql-on-fhir.export.routes/handler {}})
      :message := "Assertion failed on key :blaze.addon.sql-on-fhir.export.routes/handler when building system")))
