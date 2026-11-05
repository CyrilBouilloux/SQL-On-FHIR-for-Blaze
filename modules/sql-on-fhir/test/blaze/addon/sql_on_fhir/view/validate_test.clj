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

(deftest controles-norme-2-0-0-test
  (testing "F-041 : forEach et forEachOrNull ensemble, à toute profondeur, même identiques"
    (doseq [[titre select]
            [["niveau haut" [{:forEach "name" :forEachOrNull "identifier" :column [colonne]}]]
             ["imbriqué" [{:select [{:forEach "name" :forEachOrNull "identifier" :column [colonne]}]}]]
             ["expressions identiques" [{:forEach "name" :forEachOrNull "name" :column [colonne]}]]]]
      (testing titre
        (given (validate/validate (vue :select select))
          ::anom/category := ::anom/incorrect
          ::anom/message :# ".*forEach.*forEachOrNull.*"))))
  (testing "F-041 : l'un des deux seul reste admis"
    (is (nil? (validate/validate (vue :select [{:forEach "name" :column [colonne]}]))))
    (is (nil? (validate/validate (vue :select [{:forEachOrNull "name" :column [colonne]}])))))
  (testing "F-042 : nom de vue et de constante hors motif"
    (doseq [[titre v nom fragment]
            [["vue, chiffre" (vue :name "1vue") "1vue" "name"]
             ["vue, souligné" (vue :name "_vue") "_vue" "name"]
             ["vue, tiret" (vue :name "vue-a") "vue-a" "name"]
             ["constante, chiffre" (vue :constant [{:name "1c" :valueString "x"}]) "1c" "constant"]
             ["constante, souligné" (vue :constant [{:name "_c" :valueString "x"}]) "_c" "constant"]
             ["constante, tiret" (vue :constant [{:name "c-d" :valueString "x"}]) "c-d" "constant"]]]
      (testing titre
        (given (validate/validate v)
          ::anom/category := ::anom/incorrect
          ::anom/message :# (str ".*" nom ".*" fragment ".*|.*" fragment ".*" nom ".*")))))
  (testing "F-042 : noms valides, ou nom de vue absent"
    (is (nil? (validate/validate (vue :name "vue_1" :constant [{:name "c_1" :valueString "x"}]))))
    (is (nil? (validate/validate (vue)))))
  (testing "F-044 : types déclarés différents à une même position d'unionAll"
    (let [i {:column [{:name "v" :path "id" :type "id"}]}
          bo {:column [{:name "v" :path "active" :type "boolean"}]}
          nt {:column [{:name "v" :path "active"}]}
          u (fn [& branches] (vue :select [{:unionAll (vec branches)}]))]
      (doseq [[titre v] [["directes" (u i bo)]
                         ["imbriquée" (u {:select [i]} bo)]
                         ["trois branches, non consécutives" (u i nt bo)]
                         ["trois branches imbriquées" (u {:select [i]} {:select [nt]} {:select [bo]})]]]
        (testing titre
          (given (validate/validate v)
            ::anom/category := ::anom/incorrect
            ::anom/message :# #"(?s).*column `v`.*\"id\".*\"boolean\".*")))
      (is (nil? (validate/validate (u i nt i))))
      (is (nil? (validate/validate (u i i))))
      (is (nil? (validate/validate (u i nt))))))
  (testing "F-044 : unionAll sans branche, à plat et imbriqué, n'est pas refusé"
    (is (nil? (validate/validate (vue :select [{:unionAll []}]))))
    (is (nil? (validate/validate (vue :select [{:select [{:unionAll []}]}]))))
    (is (nil? (validate/validate (vue :select [{:unionAll [{:unionAll []}]}]))))
    (is (nil? (validate/validate (vue :select [{:unionAll [{}]}]))))
    (is (nil? (validate/validate (vue :select [{:unionAll [{} {}]}]))))
    (is (nil? (validate/validate (vue :constant []))))))

(deftest status-test
  (testing "F-043 : valeur hors publication-status, partout"
    (doseq [v ["brouillon" "" "Active" 1]]
      (given (validate/validate (vue :status v))
        ::anom/category := ::anom/incorrect
        ::anom/message :# (str ".*status.*" (pr-str v) ".*"))))
  (testing "F-043 : les quatre valeurs de la norme"
    (doseq [v ["draft" "active" "retired" "unknown"]]
      (is (nil? (validate/validate (vue :status v))))
      (is (nil? (validate/require-status (vue :status v))))))
  (testing "F-043 : absence tolérée par le moteur, refusée par `require-status`"
    (is (nil? (validate/validate (vue))))
    (given (validate/require-status (vue))
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*status.*"))
  (testing "F-043 : `require-status` ne juge pas une vue qui n'est pas un objet"
    (is (nil? (validate/require-status [])))))
