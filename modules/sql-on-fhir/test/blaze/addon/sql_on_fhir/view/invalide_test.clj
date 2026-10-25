(ns blaze.addon.sql-on-fhir.view.invalide-test
  "T-021 (F-005, critère « Vues invalides ») : les 11 cas `expectError` de la suite copiée `S`
  rendent chacun une anomalie `incorrect` en valeur de retour ; une vue employant l'une des 7
  constructions `unsupported` de F-004 rend une anomalie `unsupported` qui la nomme ; 0 exception
  levée, 0 `fault`. Contre l'API cible de T-020 : `view.compile/compile`, `view.run/run`
  (livrée par T-022). La règle de jugement (`ecarts`) a son témoin
  rouge : `temoin-*-test`."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.unsupported]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.anomaly :as ba]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [blaze.test-util :as tu]
   [clojure.java.io :as io]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private suite "conformance/sql-on-fhir-js/tests")

(def ^:private mapper (j/object-mapper {:decode-key-fn true :bigdecimals true}))

(def ^:private contexte
  (delay (ig/init-key :blaze.fhir/parsing-context
                      {:structure-definition-repo
                       (ig/init-key :blaze.fhir/structure-definition-repo {})})))

(defn- lire [m]
  (let [r (fhir-spec/parse-json @contexte (j/write-value-as-string m))]
    (assert (not (ba/anomaly? r)) (pr-str r))
    r))

(defn- api [sym]
  (try
    (requiring-resolve sym)
    (catch java.io.FileNotFoundException _ nil)))

(defn- moteur []
  {:compile (api 'blaze.addon.sql-on-fhir.view.compile/compile)
   :run (api 'blaze.addon.sql-on-fhir.view.run/run)})

;; --- règle de jugement (pure) -------------------------------------------------------

(defn- refus
  "Ce que le moteur rend pour la vue : `[:anomalie a]` (à la compilation, sinon à l'exécution),
  `[:lignes rows]` si la vue est acceptée, `[:exception e]` si une exception est levée."
  [{:keys [compile run]} vue ressources]
  (try
    (let [compilee (compile vue)]
      (if (ba/anomaly? compilee)
        [:anomalie compilee]
        (let [rows (run compilee ressources)]
          (if (ba/anomaly? rows) [:anomalie rows] [:lignes rows]))))
    (catch Throwable e
      [:exception e])))

(defn ecarts
  "Écarts (vecteur de chaînes, vide si conforme) entre ce que le moteur rend pour `vue` et
  l'attendu : anomalie de catégorie `categorie`, dont le message contient `fragment` si donné."
  [moteur vue ressources categorie fragment]
  (let [[genre x] (refus moteur vue ressources)]
    (case genre
      :exception [(str "exception levée " (.getName (class x)) " : " (ex-message x))]
      :lignes [(str "vue acceptée, lignes obtenues " (pr-str x))]
      :anomalie
      (let [cat (::anom/category x)
            msg (::anom/message x)]
        (cond-> []
          (= ::anom/fault cat) (conj (str "fault : " msg))
          (not= categorie cat) (conj (str "catégorie attendue " categorie ", obtenue " cat))
          (and fragment (not (str/includes? (str msg) fragment)))
          (conj (str "message sans « " fragment " » : " (pr-str msg))))))))

(defn- exige-moteur []
  (let [m (moteur)]
    (is (and (:compile m) (:run m))
        "API absente : brancher blaze.addon.sql-on-fhir.view.compile/compile et view.run/run (T-022, T-023 ; docs/couverture.md §3)")
    m))

;; --- les 11 cas expectError de S ------------------------------------------------------

(def ^:private attendu-par-fichier
  {"validate.json" 5 "constant.json" 2 "union.json" 2 "collection.json" 1 "view_resource.json" 1})

(defn- cas-en-erreur
  "Cas `expectError` de S : `[fichier titre vue ressources]`."
  []
  (vec (for [f (sort (map #(.getName ^java.io.File %) (.listFiles (io/file suite))))
             :when (str/ends-with? f ".json")
             :let [{:keys [resources tests]} (j/read-value (io/file suite f) mapper)]
             t tests
             :when (:expectError t)]
         [f (:title t) (:view t) (mapv lire resources)])))

(deftest suite-test
  (testing "le domaine : 11 cas expectError, répartis comme le critère l'écrit"
    (let [cas (cas-en-erreur)]
      (is (= 11 (count cas)))
      (is (= attendu-par-fichier (frequencies (map first cas)))))))

(deftest vues-invalides-test
  (let [m (exige-moteur)]
    (when (and (:compile m) (:run m))
      (doseq [[fichier titre vue ressources] (cas-en-erreur)]
        (testing (str fichier " « " titre " »")
          (is (= [] (ecarts m vue ressources ::anom/incorrect nil))))))))

;; --- les 7 constructions unsupported de F-004 -----------------------------------------

(def ^:private constructions
  "Une vue par alternative de F-004 « Construction non gérée » : [nom, expression de colonne]."
  [["ImpliesExpression" "active implies true"]
   ["IndexInvocation" "$index"]
   ["MembershipExpression" "1 in name"]
   ["PolarityExpression" "-1"]
   ["QuantityLiteral" "5 'mg'"]
   ["TimeLiteral" "@T10:00"]
   ["TotalInvocation" "$total"]])

(defn- vue-de [expression]
  {:resource "Patient" :status "active"
   :select [{:column [{:name "c" :path expression}]}]})

(def ^:private patient
  (delay (lire {:resourceType "Patient" :id "p1" :active true})))

(deftest constructions-non-gerees-test
  (is (= 7 (count constructions)))
  (let [m (exige-moteur)]
    (when (and (:compile m) (:run m))
      (doseq [[nom expression] constructions]
        (testing (str nom " : `" expression "`")
          (is (= [] (ecarts m (vue-de expression) [@patient] ::anom/unsupported nom))))))))

;; --- témoins rouges de la règle `ecarts` ----------------------------------------------

(defn- factice [compile-fn run-fn] {:compile compile-fn :run run-fn})

(deftest temoin-rouge-ecarts-test
  (let [vue {} ressources []
        incorrect (ba/incorrect "refusée par le faux")
        unsupported (ba/unsupported "unsupported FHIRPath construction `TimeLiteral`")]
    (testing "témoin vert : l'anomalie attendue ne laisse aucun écart"
      (is (= [] (ecarts (factice (constantly incorrect) nil) vue ressources ::anom/incorrect nil)))
      (is (= [] (ecarts (factice (constantly unsupported) nil) vue ressources
                        ::anom/unsupported "TimeLiteral"))))
    (testing "mauvaise catégorie"
      (is (= ["catégorie attendue :cognitect.anomalies/incorrect, obtenue :cognitect.anomalies/unsupported"]
             (ecarts (factice (constantly unsupported) nil) vue ressources ::anom/incorrect nil))))
    (testing "construction non nommée"
      (is (= ["message sans « IndexInvocation » : \"unsupported FHIRPath construction `TimeLiteral`\""]
             (ecarts (factice (constantly unsupported) nil) vue ressources
                     ::anom/unsupported "IndexInvocation"))))
    (testing "fault"
      (is (= ["fault : boum"
              "catégorie attendue :cognitect.anomalies/incorrect, obtenue :cognitect.anomalies/fault"]
             (ecarts (factice (constantly (ba/fault "boum")) nil) vue ressources ::anom/incorrect nil))))
    (testing "exception levée"
      (is (= ["exception levée clojure.lang.ExceptionInfo : non"]
             (ecarts (factice (fn [_] (throw (ex-info "non" {}))) nil) vue ressources
                     ::anom/incorrect nil))))
    (testing "vue acceptée à tort"
      (is (= ["vue acceptée, lignes obtenues [{:id \"p1\"}]"]
             (ecarts (factice (constantly :ok) (fn [_ _] [{:id "p1"}])) vue ressources
                     ::anom/incorrect nil))))
    (testing "refus à l'exécution compte"
      (is (= [] (ecarts (factice (constantly :ok) (fn [_ _] incorrect)) vue ressources
                        ::anom/incorrect nil))))))
