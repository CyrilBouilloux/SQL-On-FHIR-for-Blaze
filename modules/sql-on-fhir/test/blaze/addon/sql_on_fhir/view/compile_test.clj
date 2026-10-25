(ns blaze.addon.sql-on-fhir.view.compile-test
  "T-022 (VE-03, VE-13) : la vue est validée et ses expressions compilées une fois ; une vue
  employant une construction non gérée rend `unsupported`, une expression illisible `incorrect`."
  (:refer-clojure :exclude [compile])
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.function]
   [blaze.addon.sql-on-fhir.fhir-path.operator]
   [blaze.addon.sql-on-fhir.fhir-path.unsupported]
   [blaze.addon.sql-on-fhir.fhir-path.variable]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.compile :refer [compile]]
   [blaze.addon.sql-on-fhir.view.compile-spec]
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(defn- vue [& {:as m}]
  (merge {:resource "Patient" :select [{:column [{:name "id" :path "id"}]}]} m))

(deftest vue-valide-test
  (given (compile (vue :constant [{:name "c" :valueString "x"}]
                       :where [{:path "name.family = %c"}]))
    :resource := "Patient"
    [:schema 0 :name] := "id"
    [:constants "c" 0] := "x"
    [:where 0 :path] := "name.family = %c"
    [:select 0 :columns 0 :name] := "id"))

(deftest colonnes-sans-type-test
  (testing "le type est facultatif"
    (is (not (ba/anomaly? (compile (vue))))))
  (testing "%rowIndex est connue sans déclaration"
    (is (not (ba/anomaly? (compile (vue :where [{:path "%rowIndex = 0"}])))))))

(deftest vue-invalide-test
  (testing "structure"
    (given (compile {})
      ::anom/category := ::anom/incorrect))
  (testing "expression illisible : incorrect, jamais fault"
    (given (compile (vue :select [{:forEach "@@"}]))
      ::anom/category := ::anom/incorrect))
  (testing "constante non déclarée"
    (given (compile (vue :where [{:path "name.where(use = %wrong)"}]))
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*%wrong.*"))
  (testing "constante dans une chaîne littérale : ignorée"
    (is (not (ba/anomaly? (compile (vue :where [{:path "name.family = 'a %b'"}]))))))
  (testing "constante nommée par une chaîne"
    (given (compile (vue :where [{:path "name.family = %'a b'"}]))
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*a b.*"))
  (testing "constante sans valeur"
    (given (compile (vue :constant [{:name "c"}]))
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*c.*")))

(deftest construction-non-geree-test
  (testing "FP-14 : unsupported, nommée"
    (given (compile (vue :select [{:column [{:name "c" :path "$index"}]}]))
      ::anom/category := ::anom/unsupported
      ::anom/message :# ".*IndexInvocation.*")))

(deftest iteration-compilee-test
  (testing "forEach, forEachOrNull, repeat, unionAll : compilés, plus unsupported"
    (doseq [[cle valeur] [[:forEach "name"] [:forEachOrNull "name"] [:repeat ["item"]]
                          [:unionAll [{:column [{:name "id" :path "id"}]}]]]]
      (testing (name cle)
        (is (not (ba/anomaly? (compile (vue :select [(assoc {:column []} cle valeur)])))))))))

(deftest compilation-unique-test
  (testing "une compilation par expression, à la construction"
    (let [n (atom 0)
          orig fp/compile]
      (with-redefs [fp/compile (fn [& args] (swap! n inc) (apply orig args))]
        (compile (vue :where [{:path "active"}]
                      :select [{:column [{:name "a" :path "id"} {:name "b" :path "id"}]}])))
      (is (= 3 @n)))))
