(ns blaze.addon.harnais
  "Harnais des suites amont jouees contre jar + module (tests-amont.edn, FP-15).
  Le module n'a pas de point d'entree unique : il est charge en entier, comme le fera
  l'image, en requerant tous les namespaces de `src/`."
  (:require
   [clojure.java.io :as io]
   [clojure.test :as test]))

(set! *warn-on-reflection* true)

(defn namespaces-module
  "Namespaces sous `dir` (`$MODULE_SRC`, sinon `src`), tries."
  ([] (namespaces-module (or (System/getenv "MODULE_SRC") "src")))
  ([dir]
   (->> (file-seq (io/file dir))
        (filter #(re-find #"\.cljc?$" (str %)))
        (keep #(second (re-find #"(?m)^\(ns\s+(?:\^\S+\s+)?([^\s()]+)" (slurp %))))
        (map symbol)
        (sort-by str)
        vec)))

(defn charger-module
  "Hook kaocha `pre-load` : charge le module, rend le plan inchange."
  ([test-plan] (charger-module test-plan (or (System/getenv "MODULE_SRC") "src")))
  ([test-plan dir]
   (doseq [ns-sym (namespaces-module dir)]
     (require ns-sym))
   test-plan))

(def delai-par-test-ms
  "Au-dela, un test amont est tenu pour bloque (voir `delai-par-test`)."
  60000)

(defn avec-delai
  "Fixture `:each` : un test qui ne rend pas la main dans `delai-ms` est interrompu et compte
  pour un echec. Les suites amont attendent sans fin quand `with-redefs` n'atteint pas les
  appels internes du jar (compile en direct-linking, amont build.clj:99)."
  [delai-ms]
  (fn [test-fn]
    (let [f (future (test-fn))]
      (when (identical? ::delai (deref f delai-ms ::delai))
        (future-cancel f)
        (test/do-report {:type :fail
                         :message (str "bloque, interrompu apres " delai-ms " ms")
                         :expected :termine
                         :actual :bloque})))))

(defn- avec-delai-sur-vars [testable]
  (if (= :kaocha.type/var (:kaocha.testable/type testable))
    (update testable :kaocha.testable/wrap (fnil conj [])
            (fn [t] #((avec-delai delai-par-test-ms) t)))
    (cond-> testable
      (:kaocha.test-plan/tests testable)
      (update :kaocha.test-plan/tests #(mapv avec-delai-sur-vars %)))))

(defn delai-par-test
  "Hook kaocha `post-load` : pose `avec-delai` autour de chaque var de test chargee."
  [test-plan]
  (avec-delai-sur-vars test-plan))
