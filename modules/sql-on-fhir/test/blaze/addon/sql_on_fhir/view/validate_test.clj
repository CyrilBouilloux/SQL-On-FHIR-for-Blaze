(ns blaze.addon.sql-on-fhir.view.validate-test
  "T-022 (VE-01, VE-02, VE-15) : une vue mal formée donne une anomalie `incorrect`, nommant le
  défaut ; une vue bien formée donne `nil`."
  (:require
   [blaze.addon.sql-on-fhir.view.validate :as validate]
   [blaze.addon.sql-on-fhir.view.validate-spec]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :each tu/fixture)

(def ^:private colonne {:name "id" :path "id" :type "id"})

(defn- vue [& {:as m}]
  (merge {:resource "Patient" :select [{:column [colonne]}]} m))

(deftest valide-test
  (testing "vue minimale, sans status"
    (is (nil? (validate/validate (vue)))))
  (testing "toutes les clauses"
    (is (nil? (validate/validate
               (vue :status "active"
                    :constant [{:name "c" :valueString "x"}]
                    :where [{:path "active"}]
                    :select [{:column [colonne {:name "n" :path "name.family" :collection true}]
                              :select [{:forEach "name" :column [{:name "f" :path "family"}]}
                                       {:forEachOrNull "name" :column [{:name "g" :path "given"}]}
                                       {:repeat ["item"] :column [{:name "h" :path "linkId"}]}
                                       {:unionAll [{:column [{:name "u" :path "id"}]}
                                                   {:column [{:name "u" :path "id"}]}]}]}]))))))

(deftest invalide-test
  (doseq [[titre v fragment]
          [["vue vide" {} "resource"]
           ["pas un objet" [] "object"]
           ["resource absent" (dissoc (vue) :resource) "resource"]
           ["resource vide" (vue :resource " ") "resource"]
           ["select absent" (dissoc (vue) :select) "select"]
           ["select vide" (vue :select []) "select"]
           ["select pas un vecteur" (vue :select {:column [colonne]}) "select"]
           ["élément de select pas un objet" (vue :select ["x"]) "select"]
           ["forEach pas une chaîne" (vue :select [{:forEach 1}]) "forEach"]
           ["forEachOrNull pas une chaîne" (vue :select [{:forEachOrNull 1}]) "forEachOrNull"]
           ["repeat pas un vecteur de chaînes" (vue :select [{:repeat [1]}]) "repeat"]
           ["colonne pas un objet" (vue :select [{:column ["x"]}]) "column"]
           ["colonne sans nom" (vue :select [{:column [{:path "id"}]}]) "name"]
           ["nom de colonne illisible" (vue :select [{:column [{:name "1 a" :path "id"}]}]) "name"]
           ["colonne sans path" (vue :select [{:column [{:name "a"}]}]) "path"]
           ["collection pas un booléen" (vue :select [{:column [{:name "a" :path "id" :collection "oui"}]}]) "collection"]
           ["type pas une chaîne" (vue :select [{:column [{:name "a" :path "id" :type 1}]}]) "type"]
           ["where pas un vecteur" (vue :where {:path "id"}) "where"]
           ["where sans path" (vue :where [{}]) "path"]
           ["constante sans nom" (vue :constant [{:valueString "x"}]) "constant"]
           ["unionAll pas un vecteur" (vue :select [{:unionAll {}}]) "unionAll"]
           ["noms de colonnes en double" (vue :select [{:column [colonne colonne]}]) "duplicate"]
           ["unionAll à colonnes différentes"
            (vue :select [{:unionAll [{:column [{:name "a" :path "id"}]} {:column [{:name "b" :path "id"}]}]}])
            "unionAll"]
           ["unionAll à colonnes dans un autre ordre"
            (vue :select [{:unionAll [{:column [{:name "a" :path "id"} {:name "b" :path "id"}]}
                                      {:column [{:name "b" :path "id"} {:name "a" :path "id"}]}]}])
            "unionAll"]]]
    (testing titre
      (given (validate/validate v)
        ::anom/category := ::anom/incorrect
        ::anom/message :# (str ".*" fragment ".*")))))
