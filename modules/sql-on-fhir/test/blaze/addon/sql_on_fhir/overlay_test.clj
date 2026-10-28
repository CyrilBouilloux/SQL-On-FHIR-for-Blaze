(ns blaze.addon.sql-on-fhir.overlay-test
  "IB-08, IB-11, IB-13 : le `blaze.edn` du module (overlay) fusionné par `blaze.system/merge-features`
  selon l'environnement : interrupteurs de `$viewdefinition-run` et `$viewdefinition-export`
  indépendants, un composant du module derrière chaque clé ajoutée."
  (:require
   [blaze.addon.sql-on-fhir.export.job]
   [blaze.addon.sql-on-fhir.export.lifecycle]
   [blaze.addon.sql-on-fhir.export.routes]
   [blaze.addon.sql-on-fhir.operation.export]
   [blaze.addon.sql-on-fhir.operation.run]
   [blaze.addon.sql-on-fhir.operation.sortie]
   [blaze.addon.sql-on-fhir.operation.sqlquery]
   [blaze.system :as system]
   [blaze.test-util :as tu]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [integrant.core :as ig]
   [juxt.iota :refer [given]])
  (:import
   [java.io PushbackReader]))

(test/use-fixtures :each tu/fixture)

(def ^:private overlay
  (with-open [rdr (PushbackReader. (io/reader (io/resource "blaze.edn")))]
    (edn/read {:readers {'blaze/ref (fn [k] {:ref k})
                         'blaze/var (fn [k] {:var k})
                         'blaze/ref-map (fn [k] {:ref-map k})
                         'blaze/cfg (fn [v] {:cfg v})}}
              rdr)))

(defn- config [env] (system/merge-features overlay env))

(defn- operations
  "Codes des opérations du module (`viewdefinition-*`) déclarées dans `config`."
  [config]
  (into #{}
        (comp (map :blaze.rest-api.operation/code) (filter #(str/starts-with? % "viewdefinition")))
        (:blaze.rest-api/operations config)))

(def ^:private cles-export
  [:blaze.addon.sql-on-fhir.operation.export/handler
   :blaze.job/viewdefinition-export
   :blaze.addon.sql-on-fhir.export.job/executor
   :blaze.addon.sql-on-fhir.export.routes/handler
   :blaze.addon.sql-on-fhir.export.lifecycle/manager])

(def ^:private cle-run :blaze.addon.sql-on-fhir.operation.run/handler)

(deftest interrupteur-de-l-export
  (testing "variable absente ou `false` : l'export est servi"
    (doseq [env [{} {"DISABLE_VIEWDEFINITION_EXPORT" "false"}]]
      (let [c (config env)]
        (is (contains? (operations c) "viewdefinition-export"))
        (is (every? #(contains? c %) cles-export)))))
  (testing "`true` : opération et composants absents ; `$viewdefinition-run` inchangée"
    (let [c (config {"DISABLE_VIEWDEFINITION_EXPORT" "true"})]
      (is (not (contains? (operations c) "viewdefinition-export")))
      (is (not-any? #(contains? c %) cles-export))
      (is (contains? (operations c) "viewdefinition-run"))
      (is (contains? c cle-run)))))

(deftest interrupteurs-independants
  (testing "run éteinte, export allumée : l'enveloppe de sortie reste, avec les routes de l'export"
    (let [c (config {"DISABLE_VIEWDEFINITION_RUN" "true"})]
      (is (= #{"viewdefinition-export"} (operations c)))
      (is (not (contains? c cle-run)))
      (given c
        [:blaze.addon.sql-on-fhir.operation.sortie/app :export-routes]
        := {:ref :blaze.addon.sql-on-fhir.export.routes/handler}
        [:blaze/server :handler] := {:ref :blaze.addon.sql-on-fhir.operation.sortie/app})))
  (testing "run allumée, export éteinte : l'enveloppe sans routes"
    (let [c (config {"DISABLE_VIEWDEFINITION_EXPORT" "true"})]
      (is (not (contains? (:blaze.addon.sql-on-fhir.operation.sortie/app c) :export-routes)))
      (is (= {:ref :blaze.addon.sql-on-fhir.operation.sortie/app} (get-in c [:blaze/server :handler])))))
  (testing "les deux allumées : deux opérations, une seule enveloppe"
    (let [c (config {})]
      (is (= #{"viewdefinition-run" "viewdefinition-export"} (operations c)))
      (given (:blaze.addon.sql-on-fhir.operation.sortie/app c)
        :app := {:ref :blaze.handler/app}
        :export-routes := {:ref :blaze.addon.sql-on-fhir.export.routes/handler})))
  (testing "les deux éteintes : le serveur d'origine"
    (let [c (config {"DISABLE_VIEWDEFINITION_RUN" "true" "DISABLE_VIEWDEFINITION_EXPORT" "true" "DISABLE_SQLQUERY_RUN" "true"})]
      (is (= #{} (operations c)))
      (is (not (contains? c :blaze.addon.sql-on-fhir.operation.sortie/app))))))

(def ^:private cle-sqlquery :blaze.addon.sql-on-fhir.operation.sqlquery/handler)

(defn- operations-sql [config]
  (into #{}
        (comp (map :blaze.rest-api.operation/code) (filter #(= "sqlquery-run" %)))
        (:blaze.rest-api/operations config)))

(deftest interrupteur-de-sqlquery-run
  (testing "variable absente ou `false` : `$sqlquery-run` est déclarée et servie (allumée par défaut)"
    (doseq [env [{} {"DISABLE_SQLQUERY_RUN" "false"}]]
      (let [c (config env)]
        (is (= #{"sqlquery-run"} (operations-sql c)))
        (is (contains? c cle-sqlquery))
        (given (:blaze.rest-api/operations c)
          [#(some (fn [o] (when (= "sqlquery-run" (:blaze.rest-api.operation/code o)) o)) %) :blaze.rest-api.operation/system-handler]
          := {:ref cle-sqlquery}))))
  (testing "`true` : opération et composant absents ; les opérations de vue sont inchangées"
    (let [c (config {"DISABLE_SQLQUERY_RUN" "true"})]
      (is (= #{} (operations-sql c)))
      (is (not (contains? c cle-sqlquery)))
      (is (= #{"viewdefinition-run" "viewdefinition-export"} (operations c)))))
  (testing "seule allumée : l'enveloppe de sortie et le corps brut sont là, sans routes d'export"
    (let [c (config {"DISABLE_VIEWDEFINITION_RUN" "true" "DISABLE_VIEWDEFINITION_EXPORT" "true"})]
      (is (= #{"sqlquery-run"} (operations-sql c)))
      (is (= {:ref :blaze.addon.sql-on-fhir.operation.sortie/app} (get-in c [:blaze/server :handler])))
      (is (contains? c :blaze.addon.sql-on-fhir.operation.run/raw-body))
      (is (not (contains? (:blaze.addon.sql-on-fhir.operation.sortie/app c) :export-routes))))))

(deftest sqlquery-run-parametres
  (testing "noms, types et défauts des quatre réglages (ADR 0014, point 5) ; le plafond de lignes est celui de `$viewdefinition-run`"
    (given (config {})
      [cle-sqlquery :max-rows] := {:cfg ["VIEWDEFINITION_RUN_MAX_ROWS" 'pos-int? 10000]}
      [cle-sqlquery :timeout-seconds] := {:cfg ["SQLQUERY_RUN_TIMEOUT_SECONDS" 'pos-int? 30]}
      [cle-sqlquery :max-memory] := {:cfg ["SQLQUERY_RUN_MAX_MEMORY" 'string? "256MiB"]}
      [cle-sqlquery :max-concurrent] := {:cfg ["SQLQUERY_RUN_MAX_CONCURRENT" 'pos-int? 2]}))
  (testing "la documentation de metadata dit ce que l'opération accepte"
    (let [doc (->> (:blaze.rest-api/operations (config {}))
                   (some #(when (= "sqlquery-run" (:blaze.rest-api.operation/code %)) (:blaze.rest-api.operation/documentation %))))]
      (is (re-find #"SELECT" doc))
      (is (re-find #"queryResource" doc)))))

(deftest un-composant-derriere-chaque-cle
  (testing "chaque clé ajoutée par l'overlay a une implémentation de `ig/init-key` dans le module"
    (let [defaut (get-method ig/init-key :default)]
      (doseq [k (concat cles-export [cle-sqlquery cle-run :blaze.addon.sql-on-fhir.operation.run/raw-body
                                     :blaze.addon.sql-on-fhir.operation.sortie/app])]
        (testing k
          (is (not= defaut (get-method ig/init-key k)))))))
  (testing "le gestionnaire de tâche se range sous `:blaze.job/handler`, que lit l'ordonnanceur"
    (is (isa? :blaze.job/viewdefinition-export :blaze.job/handler))))

(deftest l-export-exige-son-dossier
  (testing "le dossier vient de VIEWDEFINITION_EXPORT_DIR, sans valeur par défaut"
    (given (config {})
      [:blaze.job/viewdefinition-export :dir] := {:cfg ["VIEWDEFINITION_EXPORT_DIR" 'string?]}
      [:blaze.addon.sql-on-fhir.export.routes/handler :dir] := {:cfg ["VIEWDEFINITION_EXPORT_DIR" 'string?]})))

;; --- variables d'environnement : exigées et typées (ADR 0008, point 7) ---------------------------
;; Le fichier est lu comme le lit Blaze (`#blaze/cfg` → `Cfg`), résolu par `blaze.system/resolve-config`
;; sur un environnement, puis chaque valeur scalaire est confrontée à la spec que vérifie
;; `m/pre-init-spec` au démarrage du composant : ce qu'un pod verrait, sans pod.

(def ^:private overlay-lu-comme-blaze
  (with-open [rdr (PushbackReader. (io/reader (io/resource "blaze.edn")))]
    (edn/read {:readers {'blaze/ref ig/ref
                         'blaze/var ig/var
                         'blaze/ref-map #(system/->RefMap %)
                         'blaze/cfg (fn [[env-var spec default]]
                                      (system/->Cfg env-var (if (symbol? spec) (var-get (resolve spec)) spec) default))}}
              rdr)))

(def ^:private specs-des-composants
  {:blaze.addon.sql-on-fhir.operation.sqlquery/handler "blaze.addon.sql-on-fhir.operation.sqlquery"
   :blaze.job/viewdefinition-export "blaze.addon.sql-on-fhir.export.job"
   :blaze.addon.sql-on-fhir.export.routes/handler "blaze.addon.sql-on-fhir.export.routes"
   :blaze.addon.sql-on-fhir.export.lifecycle/manager "blaze.addon.sql-on-fhir.export.lifecycle"})

(def ^:private env-valide
  {"VIEWDEFINITION_EXPORT_DIR" "/app/export"
   "VIEWDEFINITION_EXPORT_MAX_BYTES" "8589934592"
   "VIEWDEFINITION_EXPORT_RETENTION_SECONDS" "86400"
   "VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS" "300"})

(defn- refuses
  "Entrées `[composant clé]` dont la valeur, lue de `env`, ne satisfait pas la spec du composant."
  [env]
  (let [config (-> (system/merge-features overlay-lu-comme-blaze env)
                   (system/resolve-config env {}))]
    (into #{}
          (for [[k spec-ns] specs-des-composants
                [kk v] (get config k)
                :when (not (record? v))
                :when (not (s/valid? (keyword spec-ns (name kk)) v))]
            [k kk]))))

(deftest variables-exigees-et-typees
  (testing "témoin : les valeurs par défaut du chart sont acceptées"
    (is (= #{} (refuses env-valide))))
  (testing "chacune des quatre, absente : démarrage refusé, aucun repli"
    (doseq [nom (keys env-valide)]
      (is (seq (refuses (dissoc env-valide nom))) (str nom " absente"))))
  (testing "entier ≥ 1 : 0, négatif, décimal, texte, vide sont refusés"
    (doseq [nom ["VIEWDEFINITION_EXPORT_MAX_BYTES" "VIEWDEFINITION_EXPORT_RETENTION_SECONDS"
                 "VIEWDEFINITION_EXPORT_CLEANUP_PERIOD_SECONDS"]
            v ["0" "-1" "1.5" "abc" "" "8 Gio"]]
      (is (seq (refuses (assoc env-valide nom v))) (str nom "=" (pr-str v)))))
  (testing "chemin absolu : un chemin relatif est refusé, par chaque composant qui le lit"
    (doseq [v ["export" "./export" "app/export" "~/export"]]
      (is (= #{[:blaze.job/viewdefinition-export :dir]
               [:blaze.addon.sql-on-fhir.export.routes/handler :dir]
               [:blaze.addon.sql-on-fhir.export.lifecycle/manager :dir]}
             (refuses (assoc env-valide "VIEWDEFINITION_EXPORT_DIR" v)))
          v)))
  (testing "l'export éteinte : rien n'est exigé"
    (is (= #{} (refuses {"DISABLE_VIEWDEFINITION_EXPORT" "true"}))))
  (testing "`$sqlquery-run` : entier ≥ 1 pour la durée, la concurrence et le plafond de lignes ; taille pour la mémoire"
    (doseq [[nom v] (concat (for [nom ["SQLQUERY_RUN_TIMEOUT_SECONDS" "SQLQUERY_RUN_MAX_CONCURRENT" "VIEWDEFINITION_RUN_MAX_ROWS"]
                                  v ["0" "-1" "1.5" "abc"]]
                              [nom v])
                            (for [v ["256" "0MiB" "256 Mo" "1'; DROP" "MiB"]] ["SQLQUERY_RUN_MAX_MEMORY" v]))]
      (is (= #{[cle-sqlquery (case nom
                               "SQLQUERY_RUN_TIMEOUT_SECONDS" :timeout-seconds
                               "SQLQUERY_RUN_MAX_CONCURRENT" :max-concurrent
                               "VIEWDEFINITION_RUN_MAX_ROWS" :max-rows
                               "SQLQUERY_RUN_MAX_MEMORY" :max-memory)]}
             (refuses (assoc env-valide nom v)))
          (str nom "=" (pr-str v)))))
  (testing "une variable vide vaut variable absente : le défaut s'applique"
    (is (= #{} (refuses (assoc env-valide "SQLQUERY_RUN_TIMEOUT_SECONDS" "" "SQLQUERY_RUN_MAX_MEMORY" "")))))
  (testing "valeurs valides acceptées"
    (is (= #{} (refuses (assoc env-valide "SQLQUERY_RUN_MAX_MEMORY" "1GB" "SQLQUERY_RUN_TIMEOUT_SECONDS" "5"
                               "SQLQUERY_RUN_MAX_CONCURRENT" "8" "VIEWDEFINITION_RUN_MAX_ROWS" "50"))))))

;; --- F-011 : format de `viewReference` documenté (HL7 : « SHALL document ») ---------------------

(deftest format-de-view-reference-documente
  (testing "chaque opération nomme la canonique comme seul format de `viewReference` (rendu dans `rest.operation.documentation` de metadata)"
    (doseq [code ["viewdefinition-run" "viewdefinition-export"]]
      (let [doc (->> (:blaze.rest-api/operations (config {}))
                     (some #(when (= code (:blaze.rest-api.operation/code %)) (:blaze.rest-api.operation/documentation %))))]
        (testing code
          (is (string? doc))
          (is (re-find #"viewReference" doc))
          (is (re-find #"canonical" doc))
          (is (re-find #"only" doc)))))))
