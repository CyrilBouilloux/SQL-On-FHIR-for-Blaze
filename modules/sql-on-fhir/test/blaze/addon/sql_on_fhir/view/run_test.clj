(ns blaze.addon.sql-on-fhir.view.run-test
  "T-022 (VE-04…VE-08, VE-13, VE-16, VE-18, VE-19) et T-023 (VE-09…VE-12, VE-14) : lignes d'une vue sur des ressources, sans base.
  Valeurs simples (types Blaze déballés), colonnes dans l'ordre de la vue, erreurs en anomalies."
  (:refer-clojure :exclude [compile])
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.function]
   [blaze.addon.sql-on-fhir.fhir-path.operator]
   [blaze.addon.sql-on-fhir.fhir-path.variable]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.compile :refer [compile]]
   [blaze.addon.sql-on-fhir.view.run :as run]
   [blaze.addon.sql-on-fhir.view.run-spec]
   [blaze.anomaly :as ba]
   [blaze.fhir.parsing-context]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.structure-definition-repo]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private contexte
  (delay (ig/init-key :blaze.fhir/parsing-context
                      {:structure-definition-repo
                       (ig/init-key :blaze.fhir/structure-definition-repo {})})))

(defn- lire [m]
  (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(def ^:private p1
  (lire {:resourceType "Patient" :id "p1" :active true :birthDate "1970-06"
         :name [{:use "official" :family "f1" :given ["g1" "g2"]} {:family "f2"}]}))

(def ^:private p2 (lire {:resourceType "Patient" :id "p2" :active false}))

(def ^:private o1
  (lire {:resourceType "Observation" :id "o1" :status "final" :code {:text "c"}
         :valueQuantity {:value 1.5}}))

(def ^:private qr1
  (lire {:resourceType "QuestionnaireResponse" :id "qr1" :status "completed"
         :item [{:linkId "1" :item [{:linkId "1.1"} {:linkId "1.2" :item [{:linkId "1.2.1"}]}]}
                {:linkId "2"}]}))

(defn- lignes [vue ressources]
  (let [c (compile vue)]
    (assert (not (ba/anomaly? c)) (pr-str c))
    (run/run c ressources)))

(defn- vue [& {:as m}]
  (merge {:resource "Patient" :select [{:column [{:name "id" :path "id"}]}]} m))

(deftest colonnes-test
  (testing "types déballés : chaîne, booléen, date en chaîne, absence en nil"
    (is (= [{"id" "p1" "active" true "birth" "1970-06" "family" nil}
            {"id" "p2" "active" false "birth" nil "family" nil}]
           (map #(update-keys % name)
                (lignes (vue :select [{:column [{:name "id" :path "id"}
                                                {:name "active" :path "active"}
                                                {:name "birth" :path "birthDate"}
                                                {:name "family" :path "name.where(use = 'x').family"}]}])
                        [p1 p2])))))
  (testing "décimal : valeur"
    (is (= [{:v 1.5M :w 3.0M}]
           (lignes (vue :resource "Observation"
                        :select [{:column [{:name "v" :path "value.ofType(Quantity).value"}
                                           {:name "w" :path "value.ofType(Quantity).value * 2"}]}])
                   [o1])))))

(deftest ordre-des-colonnes-test
  (testing "plus de 8 colonnes : l'ordre de la vue est conservé"
    (let [noms (mapv #(str "c" %) (range 12))
          rows (lignes (vue :select [{:column (mapv #(hash-map :name % :path "id") noms)}]) [p1])]
      (is (= noms (mapv name (keys (first rows))))))))

(deftest collection-test
  (testing "collection true : vecteur, vide si rien"
    (is (= [{:given ["g1" "g2"]} {:given []}]
           (lignes (vue :select [{:column [{:name "given" :path "name.given" :collection true}]}])
                   [p1 p2]))))
  (testing "plusieurs valeurs sans collection : incorrect, nomme la colonne"
    (given (lignes (vue :select [{:column [{:name "family" :path "name.family"}]}]) [p1])
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*family.*")))

(deftest valeur-non-primitive-test
  (given (lignes (vue :select [{:column [{:name "name" :path "name"}]}]) [p1])
    ::anom/category := ::anom/incorrect
    ::anom/message :# ".*name.*"))

(deftest where-test
  (testing "vrai garde, faux et vide excluent"
    (is (= [{:id "p1"}]
           (lignes (vue :where [{:path "active"}]) [p1 p2])))
    (is (= [{:id "p2"}]
           (lignes (vue :where [{:path "active.not()"} {:path "id = 'p2'"}]) [p1 p2])))
    (is (= [] (lignes (vue :where [{:path "birthDate.empty().not() and false"}]) [p1 p2])))
    (is (= [{:id "p1"}] (lignes (vue :where [{:path "birthDate.exists()"}]) [p1 p2])))
    (is (= [] (lignes (vue :where [{:path "active"}]) [])))))

(deftest where-non-booleen-test
  (given (lignes (vue :where [{:path "name.family"}]) [p1])
    ::anom/category := ::anom/incorrect
    ::anom/message :# ".*name\\.family.*boolean.*")
  (testing "plusieurs valeurs"
    (given (lignes (vue :where [{:path "true | false"}]) [p1])
      ::anom/category := ::anom/incorrect)))

(deftest type-de-ressource-test
  (is (= [{:id "p1"} {:id "p2"}] (lignes (vue) [p1 o1 p2])))
  (is (= [{:id "o1"}] (lignes (vue :resource "Observation") [p1 o1 p2])))
  (is (= [] (lignes (vue) []))))

(deftest select-test
  (testing "colonnes, sous-select et select voisin : colonnes dans l'ordre, une ligne par ressource"
    (is (= [{:id "p1" :a "x" :b "y"}]
           (lignes {:resource "Patient"
                    :constant [{:name "k" :valueString "y"}]
                    :select [{:column [{:name "id" :path "id"}]
                              :select [{:column [{:name "a" :path "'x'"}]}]}
                             {:column [{:name "b" :path "%k"}]}]}
                   [p1]))))
  (testing "sans colonne : une ligne vide par ressource retenue"
    (is (= [{} {}] (lignes (vue :select [{:column []}]) [p1 p2])))))

(deftest constantes-test
  (is (= [{:c "official"}]
         (lignes (vue :constant [{:name "u" :valueString "official"}]
                      :select [{:column [{:name "c" :path "name.where(use = %u).use"}]}])
                 [p1])))
  (testing "décimal lu en JSON avec :bigdecimals : zéros finals conservés (IB-05)"
    (let [json "{\"constant\":[{\"name\":\"d\",\"valueDecimal\":1.10}]}"
          valeur (fn [mapper]
                   (-> (lignes (merge (vue :select [{:column [{:name "d" :path "%d"}]}])
                                      (j/read-value json mapper))
                               [p1])
                       first :d str))]
      (is (= "1.10" (valeur (j/object-mapper {:decode-key-fn true :bigdecimals true}))))
      (testing "témoin : sans :bigdecimals, 1.1"
        (is (= "1.1" (valeur (j/object-mapper {:decode-key-fn true}))))))))

(deftest erreurs-en-anomalies-test
  (testing "erreur d'évaluation sur une ressource : anomalie, pas d'exception"
    (given (lignes (vue :select [{:column [{:name "x" :path "name.family + 1"}]}]) [p1])
      ::anom/category := ::anom/incorrect))
  (testing "ressource sans type"
    (given (run/run (compile (vue)) [{:id "x"}])
      ::anom/category := ::anom/incorrect)))

;; T-023 (VE-09…VE-12, VE-14) : forEach, forEachOrNull, repeat, unionAll, %rowIndex.

(defn- iter [cle valeur & {:as m}]
  (vue :select [{:column [{:name "id" :path "id"}]}
                (merge {cle valeur :column [{:name "family" :path "family"}]} m)]))

(deftest for-each-test
  (testing "une ligne par élément, les ressources sans élément n'en donnent pas"
    (is (= [{:id "p1" :family "f1"} {:id "p1" :family "f2"}]
           (lignes (iter :forEach "name") [p1 p2]))))
  (testing "forEachOrNull : une ligne à valeurs nulles quand il n'y a rien"
    (is (= [{:id "p1" :family "f1"} {:id "p1" :family "f2"} {:id "p2" :family nil}]
           (lignes (iter :forEachOrNull "name") [p1 p2]))))
  (testing "deux forEach voisins : produit croisé, vide si l'un est vide"
    (is (= [{:a "g1" :b "f1"} {:a "g1" :b "f2"} {:a "g2" :b "f1"} {:a "g2" :b "f2"}]
           (lignes (vue :select [{:forEach "name.given" :column [{:name "a" :path "$this"}]}
                                 {:forEach "name" :column [{:name "b" :path "family"}]}])
                   [p1])))
    (is (= [] (lignes (vue :select [{:forEach "name" :column [{:name "a" :path "family"}]}
                                    {:forEach "contact" :column [{:name "b" :path "id"}]}])
                      [p1]))))
  (testing "forEach imbriqué"
    (is (= [{:g "g1"} {:g "g2"}]
           (lignes (vue :select [{:forEach "name" :select [{:forEach "given"
                                                            :column [{:name "g" :path "$this"}]}]}])
                   [p1])))))

(deftest repeat-test
  (testing "parcours en profondeur, la racine exclue"
    (is (= ["1" "1.1" "1.2" "1.2.1" "2"]
           (map :l (lignes (vue :resource "QuestionnaireResponse"
                                :select [{:repeat ["item"] :column [{:name "l" :path "linkId"}]}])
                           [qr1])))))
  (testing "un cycle arrête le parcours (`$this` rend l'élément lui-même)"
    (is (= [] (lignes (vue :select [{:repeat ["$this"] :column [{:name "id" :path "id"}]}]) [p1])))
    (is (= ["f1" "f2"]
           (map :f (lignes (vue :select [{:repeat ["name" "$this"] :column [{:name "f" :path "family"}]}])
                           [p1]))))))

(deftest union-all-test
  (testing "concaténation des branches, doublons conservés, branche vide ignorée"
    (is (= [{:id "p1" :v "f1"} {:id "p1" :v "f2"} {:id "p1" :v "f1"} {:id "p1" :v "f2"}]
           (lignes (vue :select [{:column [{:name "id" :path "id"}]
                                  :unionAll [{:forEach "name" :column [{:name "v" :path "family"}]}
                                             {:forEach "name" :column [{:name "v" :path "family"}]}
                                             {:forEach "contact" :column [{:name "v" :path "id"}]}]}])
                   [p1]))))
  (testing "branche forEachOrNull : une ligne nulle"
    (is (= [{:id "p2" :v nil}]
           (lignes (vue :select [{:column [{:name "id" :path "id"}]
                                  :unionAll [{:forEachOrNull "name" :column [{:name "v" :path "family"}]}]}])
                   [p2]))))
  (testing "colonnes différentes : incorrect"
    (given (compile (vue :select [{:unionAll [{:column [{:name "a" :path "id"}]}
                                              {:column [{:name "b" :path "id"}]}]}]))
      ::anom/category := ::anom/incorrect)))

(deftest row-index-test
  (testing "0 hors itération, position dans forEach, forEachOrNull vide = 0"
    (is (= [{:i 0}] (lignes (vue :select [{:column [{:name "i" :path "%rowIndex"}]}]) [p1])))
    (is (= [{:i 0 :f "f1"} {:i 1 :f "f2"}]
           (lignes (vue :select [{:forEach "name" :column [{:name "i" :path "%rowIndex"}
                                                           {:name "f" :path "family"}]}])
                   [p1])))
    (is (= [{:i 0 :f nil}]
           (lignes (vue :select [{:forEachOrNull "name" :column [{:name "i" :path "%rowIndex"}
                                                                 {:name "f" :path "family"}]}])
                   [p2]))))
  (testing "repeat : position dans le parcours"
    (is (= [0 1 2 3 4]
           (map :i (lignes (vue :resource "QuestionnaireResponse"
                                :select [{:repeat ["item"] :column [{:name "i" :path "%rowIndex"}]}])
                           [qr1])))))
  (testing "un select sans itération hérite de l'indice de l'itération englobante"
    (is (= [{:i 0} {:i 1}]
           (lignes (vue :select [{:forEach "name" :select [{:column [{:name "i" :path "%rowIndex"}]}]}])
                   [p1]))))
  (testing "chaque itération a son indice"
    (is (= [{:i 0 :j 0 :g "g1"} {:i 0 :j 1 :g "g2"} {:i 1 :j 0 :g nil}]
           (lignes (vue :select [{:forEach "name" :column [{:name "i" :path "%rowIndex"}]
                                  :select [{:forEachOrNull "given"
                                            :column [{:name "j" :path "%rowIndex"}
                                                     {:name "g" :path "$this"}]}]}])
                   [p1])))))
