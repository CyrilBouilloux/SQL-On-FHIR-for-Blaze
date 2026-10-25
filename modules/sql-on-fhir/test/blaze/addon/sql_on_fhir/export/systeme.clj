(ns blaze.addon.sql-on-fhir.export.systeme
  "Système de test de l'export : deux nœuds en mémoire du jar (principal et administration), comme
  le serveur, l'ordonnanceur de tâches de Blaze et le gestionnaire de tâche du module. Configuration
  recopiée de `blaze.job-scheduler-test` (amont, tests de `modules/job-scheduler`)."
  (:require
   [blaze.addon.sql-on-fhir.export.routes]
   [blaze.db.kv :as kv]
   [blaze.db.kv.mem]
   [blaze.db.node :as node]
   [blaze.db.resource-store :as rs]
   [blaze.db.resource-store.kv :as rs-kv]
   [blaze.db.search-param-registry]
   [blaze.db.tx-cache]
   [blaze.db.tx-log :as tx-log]
   [blaze.db.tx-log.local]
   [blaze.fhir.parsing-context]
   [blaze.fhir.test-util :refer [structure-definition-repo]]
   [blaze.fhir.writing-context]
   [blaze.job-scheduler]
   [blaze.scheduler]
   [blaze.terminology-service :as-alias ts]
   [blaze.terminology-service.not-available]
   [integrant.core :as ig]
   [java-time.api :as time]))

(derive :blaze.db.main/node :blaze.db/node)
(derive :blaze.db.admin/node :blaze.db/node)

(def ^:private index-kv-store
  {:column-families
   {:search-param-value-index nil
    :resource-value-index nil
    :compartment-search-param-value-index nil
    :compartment-resource-type-index nil
    :active-search-params nil
    :tx-success-index {:reverse-comparator? true}
    :tx-error-index nil
    :t-by-instant-index {:reverse-comparator? true}
    :resource-as-of-index nil
    :type-as-of-index nil
    :system-as-of-index nil
    :patient-last-change-index nil
    :type-stats-index nil
    :system-stats-index nil}})

(defn- noeud
  "Parties du nœud `nom` (`main` ou `admin`) : sa configuration (`:config`) et ses composants (`:autres`)."
  [nom]
  (let [k #(keyword (str "blaze.db." nom) %)
        index (k "index-kv-store")
        tx-kv (k "transaction-kv-store")]
    {:config {:tx-log (ig/ref [::tx-log/local (k "tx-log")])
              :tx-cache (ig/ref [:blaze.db/tx-cache (k "tx-cache")])
              :resource-cache (ig/ref :blaze.db/resource-cache)
              :resource-store (ig/ref :blaze.db/resource-store)
              :kv-store (ig/ref [::kv/mem index])
              :resource-indexer (ig/ref [::node/resource-indexer (k "resource-indexer")])
              :search-param-registry (ig/ref :blaze.db/search-param-registry)
              :scheduler (ig/ref :blaze/scheduler)
              :poll-timeout (time/millis 10)}
     :autres {[::tx-log/local (k "tx-log")] {:kv-store (ig/ref [::kv/mem tx-kv]) :clock (ig/ref :blaze.test/fixed-clock)}
              [::kv/mem tx-kv] {:column-families {}}
              [:blaze.db/tx-cache (k "tx-cache")] {:kv-store (ig/ref [::kv/mem index])}
              [::kv/mem index] index-kv-store
              [::node/resource-indexer (k "resource-indexer")]
              {:kv-store (ig/ref [::kv/mem index])
               :resource-store (ig/ref :blaze.db/resource-store)
               :search-param-registry (ig/ref :blaze.db/search-param-registry)
               :executor (ig/ref [:blaze.db.node.resource-indexer/executor (k "resource-indexer-executor")])}
              [:blaze.db.node.resource-indexer/executor (k "resource-indexer-executor")] {}}}))

(defn config
  "Configuration du système : nœuds `:blaze.db.main/node` et `:blaze.db.admin/node`, ordonnanceur
  `:blaze/job-scheduler`, gestionnaire `:blaze.job/viewdefinition-export` qui écrit sous `dir`
  (plafond `:max-bytes`, 1 Gio par défaut) et routes `:blaze.addon.sql-on-fhir.export.routes/handler`."
  [dir & [{:keys [max-bytes] :or {max-bytes 1073741824}}]]
  (-> {:blaze.db.main/node (:config (noeud "main"))
       :blaze.db.admin/node (:config (noeud "admin"))
       :blaze/job-scheduler
       {:node (ig/ref :blaze.db.admin/node)
        :handlers {:blaze.job/viewdefinition-export (ig/ref :blaze.job/viewdefinition-export)}
        :clock (ig/ref :blaze.test/fixed-clock)
        :rng-fn (ig/ref :blaze.test/incrementing-rng-fn)}
       :blaze.job/viewdefinition-export
       {:main-node (ig/ref :blaze.db.main/node)
        :admin-node (ig/ref :blaze.db.admin/node)
        :db-sync-timeout 10000
        :dir (str dir)
        :max-bytes max-bytes
        :executor (ig/ref :blaze.addon.sql-on-fhir.export.job/executor)}
       :blaze.addon.sql-on-fhir.export.routes/handler
       {:admin-node (ig/ref :blaze.db.admin/node)
        :job-scheduler (ig/ref :blaze/job-scheduler)
        :dir (str dir)
        :writing-context (ig/ref :blaze.fhir/writing-context)
        :base-url "http://h"
        :context-path "/fhir"}
       :blaze.addon.sql-on-fhir.export.job/executor {}
       :blaze.db/resource-cache {:resource-store (ig/ref :blaze.db/resource-store)}
       ::rs/kv {:kv-store (ig/ref :blaze.db/resource-kv-store)
                :parsing-context (ig/ref :blaze.fhir.parsing-context/resource-store)
                :writing-context (ig/ref :blaze.fhir/writing-context)
                :executor (ig/ref ::rs-kv/executor)}
       [::kv/mem :blaze.db/resource-kv-store] {:column-families {}}
       ::rs-kv/executor {}
       :blaze.db/search-param-registry {:structure-definition-repo structure-definition-repo
                                        :terminology-service (ig/ref ::ts/not-available)}
       ::ts/not-available {}
       [:blaze.fhir/parsing-context :blaze.fhir.parsing-context/resource-store]
       {:structure-definition-repo structure-definition-repo
        :fail-on-unknown-property false
        :include-summary-only true
        :use-regex false}
       :blaze.fhir/writing-context {:structure-definition-repo structure-definition-repo}
       :blaze/scheduler {}
       :blaze.test/fixed-clock {}
       :blaze.test/incrementing-rng-fn {}}
      (merge (:autres (noeud "main")) (:autres (noeud "admin")))))
