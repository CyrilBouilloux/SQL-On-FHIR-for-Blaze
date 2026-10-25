(ns blaze.addon.sql-on-fhir.noeud
  "Nœud Blaze en mémoire du jar publié (IB-10), pour les tests de `data_source`. Configuration
  recopiée de `blaze.db.test-util` (amont, tests de `modules/db`), horloge à pas d'une seconde :
  la transaction `n` porte l'instant `1970-01-01T00:00:<n>Z`."
  (:require
   [blaze.db.api :as d]
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
   [blaze.scheduler]
   [blaze.terminology-service :as-alias ts]
   [blaze.terminology-service.not-available]
   [integrant.core :as ig]
   [java-time.api :as time]))

(set! *warn-on-reflection* true)

(def config
  {:blaze.db/node
   {:tx-log (ig/ref ::tx-log/local)
    :tx-cache (ig/ref :blaze.db/tx-cache)
    :resource-cache (ig/ref :blaze.db/resource-cache)
    :resource-store (ig/ref ::rs/kv)
    :kv-store (ig/ref :blaze.db/index-kv-store)
    :resource-indexer (ig/ref ::node/resource-indexer)
    :search-param-registry (ig/ref :blaze.db/search-param-registry)
    :scheduler (ig/ref :blaze/scheduler)
    :poll-timeout (time/millis 10)}
   :blaze.db/resource-cache {:resource-store (ig/ref ::rs/kv)}
   ::tx-log/local {:kv-store (ig/ref :blaze.db/transaction-kv-store)
                   :clock (ig/ref :blaze.test/step-clock)}
   [::kv/mem :blaze.db/transaction-kv-store] {:column-families {}}
   :blaze.test/step-clock {}
   :blaze.db/tx-cache {:kv-store (ig/ref :blaze.db/index-kv-store)}
   [::kv/mem :blaze.db/index-kv-store]
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
     :system-stats-index nil}}
   ::rs/kv {:kv-store (ig/ref :blaze.db/resource-kv-store)
            :parsing-context (ig/ref :blaze.fhir/parsing-context)
            :writing-context (ig/ref :blaze.fhir/writing-context)
            :executor (ig/ref ::rs-kv/executor)}
   [::kv/mem :blaze.db/resource-kv-store] {:column-families {}}
   ::rs-kv/executor {}
   ::node/resource-indexer {:kv-store (ig/ref :blaze.db/index-kv-store)
                            :resource-store (ig/ref ::rs/kv)
                            :search-param-registry (ig/ref :blaze.db/search-param-registry)
                            :executor (ig/ref :blaze.db.node.resource-indexer/executor)}
   :blaze.db.node.resource-indexer/executor {}
   :blaze/scheduler {}
   :blaze.db/search-param-registry {:structure-definition-repo structure-definition-repo
                                    :terminology-service (ig/ref ::ts/not-available)}
   ::ts/not-available {}
   :blaze.fhir/parsing-context {:structure-definition-repo structure-definition-repo
                                :fail-on-unknown-property false
                                :include-summary-only true
                                :use-regex false}
   :blaze.fhir/writing-context {:structure-definition-repo structure-definition-repo}})

(defn demarrer
  "Système démarré (`ig/init`) ; le nœud est sous `:blaze.db/node`. L'appelant fait `ig/halt!`."
  []
  (ig/init config))

(defn ecrire!
  "Transaction `tx-ops` sur `node`, attendue ; rend la base résultante."
  [node tx-ops]
  @(d/transact node tx-ops)
  (d/db node))
