(ns blaze.addon.sql-on-fhir.operation.run-test
  "IB-08 : composants de l'opération `$viewdefinition-run` (corps brut, handler, plafond)."
  (:require
   [blaze.addon.sql-on-fhir.noeud :as noeud]
   [blaze.addon.sql-on-fhir.operation.run]
   [blaze.addon.sql-on-fhir.operation.run-spec]
   [blaze.addon.sql-on-fhir.operation.sortie :as sortie]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.async.comp :as ac]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.test-util :as tu :refer [given-failed-future given-thrown]]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]
   [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)
(st/instrument)
(log/set-min-level! :trace)

(test/use-fixtures :once su/registre-types)
(test/use-fixtures :each tu/fixture)

(def ^:private cle-handler :blaze.addon.sql-on-fhir.operation.run/handler)
(def ^:private cle-corps :blaze.addon.sql-on-fhir.operation.run/raw-body)

(deftest handler-init
  (testing "plafond absent ou non entier positif"
    (doseq [cfg [{} {:max-rows 0} {:max-rows "10"}]]
      (given-thrown (ig/init {cle-handler cfg})
        :message := "Assertion failed on key :blaze.addon.sql-on-fhir.operation.run/handler when building system"))))

;; --- handler réel (T-035) sur le nœud en mémoire du jar ---------------------------------------

(def ^:private contexte (delay (fichier/contexte)))

(defn- lire [m] (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(defn- patient [id] [:put (lire {:resourceType "Patient" :id id :active true})])

(defn- groupe [id & membres]
  [:put (lire {:resourceType "Group" :id id :type "person" :actual true
               :member (mapv (fn [r] {:entity {:reference r}}) membres)})])

(def ^:private vue-patient
  {:resourceType "ViewDefinition" :status "active" :resource "Patient"
   :select [{:column [{:name "id" :path "id"}]}]})

(defn- requete
  "Requête vue par le handler : `Parameters` avec `vue` et `parametres`, base `db`."
  [db vue parametres & [format]]
  (cond-> {:blaze/db db
           :body {:resourceType "Parameters"
                  :parameter (into [{:name "viewResource" :resource vue}] parametres)}}
    format (assoc ::sortie/format format)))

(defn- avec-handler
  "Appelle `f` avec le handler de plafond `max-rows` et la base du nœud en mémoire après `txs`."
  [max-rows txs f]
  (let [systeme (noeud/demarrer)
        node (:blaze.db/node systeme)
        handler (ig/init-key cle-handler {:max-rows max-rows})]
    (try
      (run! #(noeud/ecrire! node %) txs)
      (f handler (d/db node))
      (finally (ig/halt! systeme)))))

(def ^:private trois-patients [[(patient "p1") (patient "p2") (patient "p3")]])

(defn- lignes-ndjson [reponse]
  (->> (str/split-lines (::sortie/raw reponse)) (remove str/blank?) (map #(j/read-value % j/keyword-keys-object-mapper)) (map :id) sort vec))

(deftest handler-formats
  (avec-handler 10 trois-patients
                (fn [handler db]
                  (testing "NDJSON par défaut"
                    (given @(handler (requete db vue-patient []))
                      :status := 200
                      :body := nil
                      ::sortie/content-type := "application/x-ndjson"
                      lignes-ndjson := ["p1" "p2" "p3"]))
                  (testing "JSON"
                    (given @(handler (requete db vue-patient [] "json"))
                      ::sortie/content-type := "application/json"
                      #(count (j/read-value (::sortie/raw %))) := 3))
                  (testing "CSV, avec puis sans en-tête"
                    (given @(handler (requete db vue-patient [] "csv"))
                      ::sortie/content-type := "text/csv"
                      ::sortie/raw := "id\r\np1\r\np2\r\np3\r\n")
                    (given @(handler (requete db vue-patient [{:name "header" :valueBoolean false}] "csv"))
                      ::sortie/raw := "p1\r\np2\r\np3\r\n"))
                  (testing "format refusé : 400 not-supported"
                    (given-failed-future (handler (requete db vue-patient [] "xml"))
                      ::anom/category := ::anom/unsupported
                      :http/status := 400)))))

(deftest handler-requete-refusee
  (avec-handler 10 trois-patients
                (fn [handler db]
                  (given-failed-future (handler {:blaze/db db :body {:resourceType "Parameters"}})
                    ::anom/category := ::anom/incorrect
                    :fhir/issue := "required")
                  (given-failed-future (handler (requete db vue-patient [{:name "source" :valueString "s"}]))
                    ::anom/category := ::anom/unsupported
                    :http/status := 400))))

(def ^:private url-vue "http://example.org/fhir/ViewDefinition/pat")

(defn- bibliotheque
  "Écriture de la `Library` `id` qui range `vue` (url et version de la vue)."
  [id version vue]
  (let [vue (assoc vue :url url-vue :version version)]
    [:put (lire {:resourceType "Library" :id id :url url-vue :version version :status "active"
                 :type {:coding [{:system "http://terminology.hl7.org/CodeSystem/library-type" :code "asset-collection"}]}
                 :content [{:contentType "application/json"
                            :data (.encodeToString (java.util.Base64/getEncoder)
                                                   (.getBytes (j/write-value-as-string vue) "UTF-8"))}]})]))

(defn- requete-reference [db reference]
  {:blaze/db db
   :body {:resourceType "Parameters"
          :parameter [{:name "viewReference" :valueReference {:reference reference}}]}})

(deftest handler-view-reference
  (avec-handler 10 [[(patient "p1") (patient "p2") (bibliotheque "l1" "1.0.0" vue-patient)]]
                (fn [handler db]
                  (testing "url|version, puis url seule : les lignes de la vue en ligne"
                    (doseq [reference [(str url-vue "|1.0.0") url-vue]]
                      (given @(handler (requete-reference db reference))
                        :status := 200
                        lignes-ndjson := ["p1" "p2"])))
                  (testing "forme sans schéma : 400 not-supported, même pour la Library rangée"
                    (doseq [reference ["ViewDefinition/l1" "Library/l1"]]
                      (given-failed-future (handler (requete-reference db reference))
                        ::anom/category := ::anom/unsupported
                        :http/status := 400)))
                  (testing "introuvable : 400 not-found"
                    (doseq [reference [(str url-vue "|9.9.9") "http://example.org/autre"]]
                      (given-failed-future (handler (requete-reference db reference))
                        ::anom/category := ::anom/not-found
                        :http/status := 400))))))

(deftest handler-view-reference-plusieurs
  (avec-handler 10 [[(patient "p1") (bibliotheque "l1" "1.0.0" vue-patient) (bibliotheque "l2" "2.0.0" vue-patient)]]
                (fn [handler db]
                  (given-failed-future (handler (requete-reference db url-vue))
                    :fhir/issue := "multiple-matches"
                    :http/status := 400)
                  (given @(handler (requete-reference db (str url-vue "|2.0.0")))
                    lignes-ndjson := ["p1"]))))

(deftest handler-vue-refusee
  (avec-handler 10 trois-patients
                (fn [handler db]
                  (testing "incorrecte : 422 invalid"
                    (given-failed-future (handler (requete db (assoc-in vue-patient [:select 0 :column 0 :path] "id.(") []))
                      ::anom/category := ::anom/incorrect
                      :http/status := 422))
                  (testing "non supportée : 422 not-supported"
                    (given-failed-future (handler (requete db (assoc-in vue-patient [:select 0 :column 0 :path] "active implies true") []))
                      ::anom/category := ::anom/unsupported
                      :http/status := 422)))))

(deftest handler-filtres
  (avec-handler 10 [[(patient "p1") (patient "p2") (patient "p3") (groupe "g1" "Patient/p2" "Patient/p3")]]
                (fn [handler db]
                  (given @(handler (requete db vue-patient [{:name "patient" :valueReference {:reference "Patient/p1"}}]))
                    lignes-ndjson := ["p1"])
                  (given @(handler (requete db vue-patient [{:name "group" :valueReference {:reference "Group/g1"}}]))
                    lignes-ndjson := ["p2" "p3"])
                  (given @(handler (requete db vue-patient [{:name "_since" :valueInstant "1969-12-31T00:00:00Z"}]))
                    lignes-ndjson := ["p1" "p2" "p3"])
                  (testing "_since exclut l'instant même"
                    (given @(handler (requete db vue-patient [{:name "_since" :valueInstant (str (:blaze.db.tx/instant (d/tx db 1)))}]))
                      lignes-ndjson := []))
                  (given @(handler (requete db vue-patient [{:name "_limit" :valueInteger 2}]))
                    #(count (str/split-lines (::sortie/raw %))) := 2)
                  (testing "absent : 400 not-found"
                    (doseq [p [{:name "patient" :valueReference {:reference "Patient/absent"}}
                               {:name "group" :valueReference {:reference "Group/absent"}}]]
                      (given-failed-future (handler (requete db vue-patient [p]))
                        ::anom/category := ::anom/not-found
                        :http/status := 400)))
                  (testing "_since invalide : 400 invalid"
                    (given-failed-future (handler (requete db vue-patient [{:name "_since" :valueInstant "hier"}]))
                      ::anom/category := ::anom/incorrect
                      :http/status := nil)))))

(deftest handler-plafond
  (testing "n lignes : 200"
    (avec-handler 3 trois-patients
                  (fn [handler db]
                    (given @(handler (requete db vue-patient []))
                      :status := 200
                      lignes-ndjson := ["p1" "p2" "p3"]))))
  (testing "n + 1 lignes : 422 too-costly, aucune ligne"
    (avec-handler 2 trois-patients
                  (fn [handler db]
                    (given-failed-future (handler (requete db vue-patient []))
                      :http/status := 422
                      :fhir/issue := "too-costly")
                    (testing "_limit ≤ n sur n + 1 lignes : 200"
                      (given @(handler (requete db vue-patient [{:name "_limit" :valueInteger 2}]))
                        :status := 200
                        #(count (str/split-lines (::sortie/raw %))) := 2))))))

(defn- corps [s]
  (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8")))

(deftest corps-brut
  (let [{:keys [wrap name]} (ig/init-key cle-corps {})
        handler (wrap (fn [request] (ac/completed-future request)))]
    (is (= cle-corps name))
    (testing "le JSON est lu tel quel, viewResource comprise, propriétés inconnues conservées"
      (given @(handler {:body (corps "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"viewResource\",\"resource\":{\"resourceType\":\"ViewDefinition\",\"inconnue\":1}}]}")})
        [:body :resourceType] := "Parameters"
        [:body :parameter 0 :resource :resourceType] := "ViewDefinition"
        [:body :parameter 0 :resource :inconnue] := 1))
    (testing "corps absent"
      (given-failed-future (handler {})
        ::anom/category := ::anom/incorrect))
    (testing "JSON invalide"
      (given-failed-future (handler {:body (corps "{")})
        ::anom/category := ::anom/incorrect))))
