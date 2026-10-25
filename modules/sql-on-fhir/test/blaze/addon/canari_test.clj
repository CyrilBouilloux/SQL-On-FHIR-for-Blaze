(ns blaze.addon.canari-test
  "Canari du poste : prouve que les tests s'executent CONTRE le jar de l'image publiee
  (ADR 0003) et non contre les sources de Blaze."
  (:require
   [blaze.fhir-path :as fp]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest blaze-vient-du-jar-publie
  (let [url (str (io/resource "blaze/fhir_path.clj"))]
    (is (str/includes? url "blaze-standalone.jar!/blaze/fhir_path.clj") url)))

(deftest point-d-extension-present
  (is (instance? clojure.lang.MultiFn fp/function-expression)))
