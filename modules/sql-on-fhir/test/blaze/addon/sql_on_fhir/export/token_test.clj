(ns blaze.addon.sql-on-fhir.export.token-test
  "AS-09 : jetons de 192 bits, base64url sans bourrage (32 caractères de `A-Za-z0-9_-`)."
  (:require
   [blaze.addon.sql-on-fhir.export.token :as token]
   [blaze.addon.sql-on-fhir.export.token-spec]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]))

(st/instrument)

(test/use-fixtures :each tu/fixture)

(deftest forme
  (let [t (token/new-token)]
    (is (string? t))
    (is (= 32 (count t)))
    (is (re-matches #"[A-Za-z0-9_-]{32}" t))
    (testing "au moins 128 bits : longueur x 6 bits par caractère de l'alphabet"
      (is (<= 128 (* 6 (count t)))))))

(deftest distincts
  (is (= 10000 (count (set (repeatedly 10000 token/new-token))))))

(deftest reconnaissance
  (is (true? (token/token? (token/new-token))))
  (doseq [x [nil 1 "" "abc" (subs (token/new-token) 1) (str (token/new-token) "a")
             (str "+" (subs (token/new-token) 1)) (str (subs (token/new-token) 1) ".")]]
    (testing (pr-str x)
      (is (false? (token/token? x))))))
