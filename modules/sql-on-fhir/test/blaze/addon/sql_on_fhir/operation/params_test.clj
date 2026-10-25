(ns blaze.addon.sql-on-fhir.operation.params-test
  "OP-01, OP-02 : lecture des paramètres, refus de requête et de format."
  (:require
   [blaze.addon.sql-on-fhir.operation.params :as params]
   [blaze.addon.sql-on-fhir.operation.params-spec]
   [blaze.anomaly :as ba]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest testing]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]]))

(st/instrument)

(test/use-fixtures :each tu/fixture)

(def ^:private vue {:resourceType "ViewDefinition" :resource "Patient"})

(defn- body [& parametres]
  {:resourceType "Parameters"
   :parameter (into [{:name "viewResource" :resource vue}] parametres)})

(deftest valeurs-par-defaut
  (given (params/read-params (body) nil)
    :view := vue
    :format := "ndjson"
    :header := true
    :patient := nil
    :group := nil
    :since := nil
    :limit := nil))

(deftest formats
  (testing "dans le corps ou en paramètre de requête (la requête l'emporte)"
    (given (params/read-params (body {:name "_format" :valueCode "csv"}) nil)
      :format := "csv")
    (given (params/read-params (body {:name "_format" :valueCode "csv"}) "json")
      :format := "json"))
  (testing "Accept (F-018) : après `_format` de la requête et du corps, avant le défaut"
    (given (params/read-params (body) nil "csv")
      :format := "csv")
    (given (params/read-params (body {:name "_format" :valueCode "json"}) nil "csv")
      :format := "json")
    (given (params/read-params (body {:name "_format" :valueCode "json"}) "ndjson" "csv")
      :format := "ndjson")
    (given (params/read-params (body) nil nil)
      :format := "ndjson"))
  (testing "autre valeur : 400 not-supported"
    (doseq [f ["xml" "parquet" "" "text/csv"]]
      (given (params/read-params (body) f)
        ::anom/category := ::anom/unsupported
        :http/status := 400)))
  (testing "`_format` refusé l'emporte sur Accept"
    (given (params/read-params (body) "xml" "csv")
      ::anom/category := ::anom/unsupported)))

(deftest requete-refusee
  (testing "ni viewResource ni viewReference"
    (given (params/read-params {:resourceType "Parameters" :parameter []} nil)
      ::anom/category := ::anom/incorrect
      :fhir/issue := "required"))
  (testing "source, resource"
    (doseq [p [{:name "source" :valueString "s"}
               {:name "resource" :resource {:resourceType "Patient"}}]]
      (given (params/read-params {:resourceType "Parameters" :parameter [{:name "viewResource" :resource vue} p]} nil)
        ::anom/category := ::anom/unsupported
        :http/status := 400)))
  (testing "pas un Parameters"
    (given (params/read-params {:resourceType "Patient"} nil)
      ::anom/category := ::anom/incorrect)))

(deftest view-reference
  (let [sans-vue {:resourceType "Parameters"}
        avec (fn [& parametres] (assoc sans-vue :parameter (vec parametres)))
        reference {:name "viewReference" :valueReference {:reference "http://e.org/v|1.0.0"}}]
    (testing "la référence est rendue telle quelle, à résoudre dans la base de la requête"
      (given (params/read-params (avec reference) nil)
        :view-reference := "http://e.org/v|1.0.0"
        :view := nil
        :format := "ndjson"))
    (testing "viewResource et viewReference ensemble : 400 incorrect"
      (given (params/read-params (avec {:name "viewResource" :resource vue} reference) nil)
        ::anom/category := ::anom/incorrect))
    (testing "viewReference sans référence : 400 incorrect"
      (given (params/read-params (avec {:name "viewReference"}) nil)
        ::anom/category := ::anom/incorrect))
    (testing "les autres refus passent avant"
      (given (params/read-params (avec reference {:name "source" :valueString "s"}) nil)
        ::anom/category := ::anom/unsupported))))

(deftest filtres
  (given (params/read-params
          (body {:name "patient" :valueReference {:reference "Patient/p1"}}
                {:name "group" :valueReference {:reference "Group/g1"}}
                {:name "_since" :valueInstant "2020-01-01T01:00:00+01:00"}
                {:name "_limit" :valueInteger 5}
                {:name "header" :valueBoolean false})
          nil)
    :patient := "p1"
    :group := "g1"
    :since := "2020-01-01T00:00:00Z"
    :limit := 5
    :header := false)
  (testing "référence d'un autre type, ou id invalide"
    (doseq [p [{:name "patient" :valueReference {:reference "Group/g1"}}
               {:name "group" :valueReference {:reference "Patient/p1"}}
               {:name "patient" :valueString "a b"}]]
      (given (params/read-params (body p) nil)
        ::anom/category := ::anom/incorrect)))
  (testing "_since n'est pas un instant FHIR"
    (doseq [s ["hier" "2020-01-01" "2020-02-30T00:00:00Z" "2020-01-01T00:00:00"]]
      (given (params/read-params (body {:name "_since" :valueInstant s}) nil)
        ::anom/category := ::anom/incorrect)))
  (testing "_limit positif"
    (doseq [n [0 -1]]
      (given (params/read-params (body {:name "_limit" :valueInteger n}) nil)
        ::anom/category := ::anom/incorrect))))

;; --- $viewdefinition-export (T-048, AS-01) -------------------------------------------------

(defn- vue-nommee [nom & parts]
  {:name "view" :part (cond-> (vec parts) nom (conj {:name "name" :valueString nom}))})

(defn- avec-vue [nom] (vue-nommee nom {:name "viewResource" :resource vue}))

(defn- export-body [& parametres]
  {:resourceType "Parameters" :parameter (vec parametres)})

(deftest export-valeurs-par-defaut
  (given (params/read-export-params (export-body (avec-vue "pat")))
    :views := [{:name "pat" :rank 1 :view vue}]
    :format := "ndjson"
    :header := true
    :client-tracking-id := nil
    :patient := nil
    :group := nil
    :since := nil))

(deftest export-view-reference
  (let [reference (fn [nom r] (vue-nommee nom {:name "viewReference" :valueReference {:reference r}}))]
    (testing "la référence est rendue telle quelle, avec `view.name` s'il est donné et son rang"
      (given (params/read-export-params (export-body (reference "a" "http://e.org/v|1") (reference nil "http://e.org/w")))
        [:views 0 :name] := "a"
        [:views 0 :view-reference] := "http://e.org/v|1"
        [:views 0 :view] := nil
        [:views 1 :name] := nil
        [:views 1 :rank] := 2
        [:views 1 :view-reference] := "http://e.org/w"))
    (testing "viewResource et viewReference dans le même view : 400 incorrect"
      (given (params/read-export-params
              (export-body (vue-nommee "a" {:name "viewResource" :resource vue}
                                       {:name "viewReference" :valueReference {:reference "http://e.org/v"}})))
        ::anom/category := ::anom/incorrect))
    (testing "viewReference sans référence : 400 incorrect"
      (given (params/read-export-params (export-body (vue-nommee "a" {:name "viewReference"})))
        ::anom/category := ::anom/incorrect))))

(deftest export-noms-des-vues
  (let [lire #(params/name-views (:views (params/read-export-params (apply export-body %&))))
        reference (fn [nom] (vue-nommee nom {:name "viewReference" :valueReference {:reference "http://e.org/v"}}))]
    (testing "nom absent : le nom de la vue, sinon view-<rang>"
      (given (lire (avec-vue nil)
                   (vue-nommee nil {:name "viewResource" :resource (assoc vue :name "nommee")})
                   (avec-vue nil))
        [0 :name] := "view-1"
        [1 :name] := "nommee"
        [2 :name] := "view-3"
        [0 :rank] := nil))
    (testing "vue par référence : `view.name` l'emporte, sinon le `name` de la vue résolue, sinon view-<rang>"
      (let [[a b c] (:views (params/read-export-params (export-body (reference "a") (reference nil) (reference nil))))
            nommees (params/name-views [(assoc a :view {:name "rangee"})
                                        (assoc b :view {:name "rangee"})
                                        (assoc c :view {})])]
        (given nommees
          [0 :name] := "a"
          [1 :name] := "rangee"
          [2 :name] := "view-3")))
    (testing "deux vues de même nom, après résolution : 400 incorrect"
      (given (params/name-views [{:name "a" :rank 1 :view {}} {:rank 2 :view {:name "a"}}])
        ::anom/category := ::anom/incorrect)
      (given (lire (avec-vue "a") (avec-vue "a"))
        ::anom/category := ::anom/incorrect))))

(deftest export-parametres
  (given (params/read-export-params
          (export-body (avec-vue "a")
                       {:name "_format" :valueCode "csv"}
                       {:name "header" :valueBoolean false}
                       {:name "clientTrackingId" :valueString "suivi-1"}
                       {:name "patient" :valueReference {:reference "Patient/p1"}}
                       {:name "group" :valueReference {:reference "Group/g1"}}
                       {:name "_since" :valueInstant "2026-10-12T10:00:00+02:00"}))
    :format := "csv"
    :header := false
    :client-tracking-id := "suivi-1"
    :patient := "p1"
    :group := "g1"
    :since := "2026-10-12T08:00:00Z"))

(deftest export-requete-refusee
  (testing "aucun view : required"
    (given (params/read-export-params (export-body))
      ::anom/category := ::anom/incorrect
      :fhir/issue := "required"))
  (testing "view sans viewResource : required"
    (given (params/read-export-params (export-body (vue-nommee "a")))
      ::anom/category := ::anom/incorrect
      :fhir/issue := "required"))
  (testing "source : 400 not-supported"
    (given (params/read-export-params (export-body (avec-vue "a") {:name "source" :valueString "s"}))
      ::anom/category := ::anom/unsupported
      :http/status := 400))
  (testing "_format hors json, ndjson, csv : 400 not-supported"
    (doseq [f ["parquet" "xml" ""]]
      (given (params/read-export-params (export-body (avec-vue "a") {:name "_format" :valueCode f}))
        ::anom/category := ::anom/unsupported
        :http/status := 400)))
  (testing "plusieurs patient ou group (0..* dans l'IG) : 400 not-supported, un seul est servi"
    (doseq [nom ["patient" "group"]]
      (given (params/read-export-params
              (export-body (avec-vue "a")
                           {:name nom :valueReference {:reference "X/a"}}
                           {:name nom :valueReference {:reference "X/b"}}))
        ::anom/category := ::anom/unsupported
        :http/status := 400)))
  (testing "filtres mal formés : incorrect"
    (given (params/read-export-params (export-body (avec-vue "a") {:name "_since" :valueInstant "hier"}))
      ::anom/category := ::anom/incorrect)
    (given (params/read-export-params (export-body (avec-vue "a") {:name "patient" :valueString "a b"}))
      ::anom/category := ::anom/incorrect))
  (testing "pas un Parameters"
    (given (params/read-export-params nil)
      ::anom/category := ::anom/incorrect)))

(deftest refus-de-traitement
  (testing "vue refusée par le moteur : 422 ; patient ou groupe absent : 400 ; autre anomalie inchangée"
    (given (params/unprocessable (ba/incorrect "x")) :http/status := 422)
    (given (params/unprocessable (ba/unsupported "x")) :http/status := 422)
    (given (params/unprocessable (ba/not-found "x")) :http/status := 400)
    (given (params/unprocessable (ba/fault "x")) :http/status := nil)))
