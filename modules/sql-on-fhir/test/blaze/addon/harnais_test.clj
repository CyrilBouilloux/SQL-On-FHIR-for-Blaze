(ns blaze.addon.harnais-test
  (:require
   [blaze.addon.harnais :as harnais]
   [blaze.fhir-path :as fp]
   [clojure.java.io :as io]
   [clojure.test :as test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(defn- module-factice [ns-sym]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "module" (make-array java.nio.file.attribute.FileAttribute 0)))
        f (io/file dir "blaze/addon/factice.clj")]
    (io/make-parents f)
    (spit f (str "(ns " ns-sym ")\n(def charge true)\n"))
    dir))

(deftest namespaces-module-test
  (testing "le module vide ne charge rien"
    (is (= [] (harnais/namespaces-module (.toFile (java.nio.file.Files/createTempDirectory
                                                   "vide" (make-array java.nio.file.attribute.FileAttribute 0)))))))
  (testing "chaque namespace de src/ est decouvert"
    (is (= '[blaze.addon.factice] (harnais/namespaces-module (module-factice 'blaze.addon.factice))))))

(deftest charger-module-test
  ;; un namespace du classpath pas encore charge tient lieu de namespace du module
  (let [dir (module-factice 'clojure.xml)]
    (is (nil? (find-ns 'clojure.xml)))
    (is (= :plan (harnais/charger-module :plan dir)))
    (is (some? (find-ns 'clojure.xml)))))

(deftest indexation-inchangee-test
  (harnais/charger-module nil)
  (testing "l'arite de `eval` n'est pas touchee (IB-05)"
    (is (= '([resolver expr value]) (:arglists (meta #'fp/eval))))))

(defn- rapports [delai-ms test-fn]
  (let [rapports (atom [])]
    (binding [test/report #(swap! rapports conj %)]
      ((harnais/avec-delai delai-ms) test-fn))
    @rapports))

(deftest avec-delai-test
  (testing "un test qui finit dans le delai n'est pas signale"
    (is (= [] (rapports 5000 #(Thread/sleep 10)))))
  (testing "un test bloque est interrompu et compte pour un echec"
    (let [interrompu (promise)
          [rapport :as r] (rapports 100 #(try (Thread/sleep 60000)
                                              (catch InterruptedException _ (deliver interrompu true))))]
      (is (= 1 (count r)))
      (is (= :fail (:type rapport)))
      (is (true? (deref interrompu 5000 false))))))

(deftest delai-par-test-test
  (let [plan {:kaocha.test-plan/tests
              [{:kaocha.testable/type :kaocha.type/ns
                :kaocha.test-plan/tests
                [{:kaocha.testable/type :kaocha.type/var
                  :kaocha.testable/wrap [identity]}
                 {:kaocha.testable/type :kaocha.type/var}]}
               {:kaocha.testable/type :kaocha.type/ns}]}
        vars (-> (harnais/delai-par-test plan)
                 :kaocha.test-plan/tests first :kaocha.test-plan/tests)]
    (testing "chaque var recoit une enveloppe de plus"
      (is (= [2 1] (map (comp count :kaocha.testable/wrap) vars))))
    (testing "l'enveloppe rend un test qui s'execute"
      (let [appele (atom false)
            enveloppe (last (:kaocha.testable/wrap (first vars)))]
        ((enveloppe (fn [] (reset! appele true))))
        (is (true? @appele))))))
