(ns blaze.addon.sql-on-fhir.operation.sortie-test
  "IB-12 : enrobage de `:blaze.handler/app`, entrée (`_format` rangé, `Accept` fixé) et sortie
  (lignes remises en `:body`) pour la seule route `$viewdefinition-run`."
  (:require
   [blaze.addon.sql-on-fhir.operation.sortie :as sortie]
   [blaze.anomaly :as ba]
   [blaze.async.comp :as ac]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [juxt.iota :refer [given]]))

(st/instrument)

(test/use-fixtures :each tu/fixture)

(defn- appel
  "Appelle `app` enrobée avec `request` ; rend [requête vue par app, réponse]."
  [app-reponse request]
  (let [vue (promise)
        p (promise)
        app (fn [req respond _] (deliver vue req) (respond app-reponse))]
    ((ig/init-key :blaze.addon.sql-on-fhir.operation.sortie/app {:app app})
     request (partial deliver p) #(deliver p %))
    [@vue @p]))

(def ^:private route
  {:request-method :post :uri "/fhir/$viewdefinition-run" :query-string "_format=csv&_limit=2"
   :headers {"accept" "*/*"}})

(deftest entree
  (let [[vue _] (appel {:status 200 :body nil} route)]
    (given vue
      :query-string := "_limit=2"
      [:headers "accept"] := "application/fhir+json"
      ::sortie/format := "csv"))
  (testing "pas de _format"
    (given (first (appel {:status 200} (dissoc route :query-string)))
      :query-string := nil
      ::sortie/format := nil)))

(deftest accept-d-origine
  (testing "table M : type unique, paramètres de type ignorés, casse ignorée"
    (doseq [[accept fmt] [["application/json" "json"] ["application/x-ndjson" "ndjson"]
                          ["text/csv" "csv"] ["text/csv;header=absent" "csv"] ["text/csv;q=0.5" "csv"] ["text/csv;q=0.001" "csv"] ["text/csv;q=1" "csv"] ["text/csv;q=0" nil] ["text/csv;q=0.0" nil]
                          ["text/csv; q=0.000" nil] ["application/json;charset=utf-8;Q=0" nil] ["text/csv;q=0;header=absent" nil]
                          [" Text/CSV ; q=1" "csv"]
                          ["*/*" nil] ["application/fhir+json" nil] ["application/octet-stream" nil]
                          ["application/xml" nil] ["foo/bar" nil] ["text/csv, */*;q=0.1" nil]
                          ["text/csv;q=0.5, application/json;q=0.4" nil] ["text/csv;q=0, application/json" nil]
                          ["application/json;q=0.5, text/csv;q=0.3" nil]
                          ["" nil]]]
      (testing accept
        (is (= fmt (::sortie/accept-format (first (appel {:status 200} (assoc-in route [:headers "accept"] accept)))))))))
  (testing "Accept absent"
    (is (nil? (::sortie/accept-format (first (appel {:status 200} (dissoc route :headers)))))))
  (testing "l'Accept posé pour le middleware de Blaze reste application/fhir+json"
    (given (first (appel {:status 200} (assoc-in route [:headers "accept"] "text/csv")))
      [:headers "accept"] := "application/fhir+json")))

(deftest sortie-brute
  (given (second (appel {:status 200 :body nil ::sortie/raw "a,b\r\n" ::sortie/content-type "text/csv"}
                        route))
    :body := "a,b\r\n"
    [:headers "Content-Type"] := "text/csv"
    ::sortie/raw := nil
    ::sortie/content-type := nil))

(deftest autres-routes-intactes
  (doseq [r [(assoc route :uri "/fhir/metadata")
             (assoc route :request-method :get)]]
    (let [[vue reponse] (appel {:status 200 :body {:a 1}} r)]
      (is (= r vue))
      (is (= {:status 200 :body {:a 1}} reponse)))))

(deftest reponse-sans-lignes
  (is (= {:status 422 :body {:x 1}}
         (second (appel {:status 422 :body {:x 1}} route)))))

;; --- routes de l'export (T-048, IB-13) ----------------------------------------------------

(defn- appel-export
  "Appelle l'enveloppe munie de `export-routes` ; rend [requête vue par app (nil si app non appelée), réponse]."
  [export-routes request]
  (let [vue (promise)
        p (promise)
        app (fn [req respond _] (deliver vue req) (respond {:status 200 :body :app}))]
    ((ig/init-key :blaze.addon.sql-on-fhir.operation.sortie/app {:app app :export-routes export-routes})
     request (partial deliver p) #(deliver p {:raise %}))
    [(deref vue 0 nil) @p]))

(deftest routes-de-l-export
  (let [export-routes (fn [{:keys [uri]}]
                        (case uri
                          "/fhir/__export/status/x" (ac/completed-future {:status 202})
                          "/fhir/__export/status/panne" (ac/completed-future (ba/fault "panne"))
                          "/fhir/__async-status/x" (ac/completed-future nil)
                          nil))
        requete {:request-method :get :uri "/fhir/__export/status/x"}]
    (testing "une route de l'export est servie par les routes, l'application n'est pas appelée"
      (let [[vue reponse] (appel-export export-routes requete)]
        (is (nil? vue))
        (is (= {:status 202} reponse))))
    (testing "une panne des routes est levée"
      (let [[vue reponse] (appel-export export-routes (assoc requete :uri "/fhir/__export/status/panne"))]
        (is (nil? vue))
        (given (ba/anomaly (:raise reponse)) ::anom/message := "panne")))
    (testing "des routes qui répondent « pas pour moi » (future de nil) : la requête passe à l'application, intacte"
      (let [r (assoc requete :uri "/fhir/__async-status/x")
            [vue reponse] (appel-export export-routes r)]
        (is (= r vue))
        (is (= {:status 200 :body :app} reponse))))
    (testing "toute autre requête passe à l'application, intacte"
      (let [r (assoc requete :uri "/fhir/Patient")
            [vue reponse] (appel-export export-routes r)]
        (is (= r vue))
        (is (= {:status 200 :body :app} reponse))))
    (testing "sans routes (opération éteinte) : l'application sert tout"
      (let [[vue reponse] (appel-export nil requete)]
        (is (= requete vue))
        (is (= {:status 200 :body :app} reponse))))
    (testing "la route de `run` garde son traitement"
      (let [[vue _] (appel-export export-routes route)]
        (given vue ::sortie/format := "csv")))))

;; --- `$sqlquery-run` (T-116, SQ-03) : même enveloppe ----------------------------------------

(deftest route-sqlquery-run
  (let [r (assoc route :uri "/fhir/$sqlquery-run" :headers {"accept" "text/csv"})]
    (testing "entrée : `_format` rangé, `Accept` fixé, format d'`Accept` relevé"
      (given (first (appel {:status 200 :body nil} r))
        :query-string := "_limit=2"
        [:headers "accept"] := "application/fhir+json"
        ::sortie/format := "csv"
        ::sortie/accept-format := "csv"))
    (testing "sortie : les lignes brutes remises en corps"
      (given (second (appel {:status 200 :body nil ::sortie/raw "a\r\n" ::sortie/content-type "text/csv"} r))
        :body := "a\r\n"
        [:headers "Content-Type"] := "text/csv"))
    (testing "la route de `$viewdefinition-run` est inchangée, une autre route du même suffixe n'est pas prise"
      (given (first (appel {:status 200} route)) ::sortie/format := "csv")
      (let [autre (assoc r :uri "/fhir/$sqlquery-runner")]
        (is (= autre (first (appel {:status 200} autre))))))))
