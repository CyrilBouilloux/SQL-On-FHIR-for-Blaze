(ns blaze.addon.sql-on-fhir.serializer-test
  "SR-01 (NDJSON), SR-02 (JSON), SR-03 (CSV, RFC 4180)."
  (:require
   [blaze.addon.sql-on-fhir.serializer :as serializer]
   [blaze.addon.sql-on-fhir.serializer-spec]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [juxt.iota :refer [given]]))

(st/instrument)

(test/use-fixtures :each tu/fixture)

(def ^:private schema [{:name "id"} {:name "name"}])
(def ^:private rows [{:id "1" :name "a"} {:id "2" :name nil}])

(deftest ndjson
  (given (serializer/serialize "ndjson" schema rows true)
    :body := "{\"id\":\"1\",\"name\":\"a\"}\n{\"id\":\"2\",\"name\":null}\n"
    :content-type := "application/x-ndjson")
  (given (serializer/serialize "ndjson" schema [] true)
    :body := ""))

(deftest json
  (given (serializer/serialize "json" schema rows true)
    :body := "[{\"id\":\"1\",\"name\":\"a\"},{\"id\":\"2\",\"name\":null}]"
    :content-type := "application/json")
  (given (serializer/serialize "json" schema [] true)
    :body := "[]"))

(deftest csv
  (testing "en-tête, CRLF, nil vide"
    (given (serializer/serialize "csv" schema rows true)
      :body := "id,name\r\n1,a\r\n2,\r\n"
      :content-type := "text/csv"))
  (testing "header=false"
    (given (serializer/serialize "csv" schema rows false)
      :body := "1,a\r\n2,\r\n"))
  (testing "aucune ligne : l'en-tête reste"
    (given (serializer/serialize "csv" schema [] true)
      :body := "id,name\r\n"))
  (testing "guillemets, virgules et sauts de ligne échappés"
    (given (serializer/serialize "csv" schema [{:id "a,\"b\"" :name "x\ny"}] false)
      :body := "\"a,\"\"b\"\"\",\"x\ny\"\r\n"))
  (testing "nombres, booléens, collections"
    (given (serializer/serialize "csv" schema [{:id 1 :name true} {:id ["a" "b"] :name 1.5}] false)
      :body := "1,true\r\n\"[\"\"a\"\",\"\"b\"\"]\",1.5\r\n")))

(deftest encodeur
  (testing "SR-05 : cadrage (tête, queue) et ligne i de chaque format ; `serialize` en est la concaténation"
    (let [{:keys [head row tail content-type]} (serializer/encoder "ndjson" schema true)]
      (is (nil? head))
      (is (nil? tail))
      (is (= "application/x-ndjson" content-type))
      (is (= "{\"id\":\"1\",\"name\":\"a\"}\n" (row 0 {:id "1" :name "a"}))))
    (let [{:keys [head row tail]} (serializer/encoder "json" schema true)]
      (is (= "[" head))
      (is (= "]" tail))
      (is (= "{\"id\":\"1\"}" (row 0 {:id "1"})))
      (is (= ",{\"id\":\"2\"}" (row 1 {:id "2"}))))
    (let [{:keys [head row]} (serializer/encoder "csv" schema true)]
      (is (= "id,name\r\n" head))
      (is (= "1,a\r\n" (row 0 {:id "1" :name "a"}))))
    (is (nil? (:head (serializer/encoder "csv" schema false))))))

(deftest retours-conformes
  (testing "les `:ret` des spécifications sont satisfaits (non vérifiés par `st/instrument`)"
    (doseq [fmt ["ndjson" "json" "csv"]]
      (is (su/ret-conforme? `serializer/serialize (serializer/serialize fmt schema rows true)))
      (is (su/ret-conforme? `serializer/encoder (serializer/encoder fmt schema true))))))
