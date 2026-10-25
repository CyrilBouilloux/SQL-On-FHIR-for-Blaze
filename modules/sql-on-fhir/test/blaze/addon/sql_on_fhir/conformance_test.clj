(ns blaze.addon.sql-on-fhir.conformance-test
  "T-022 (INF-02) : le banc distingue un moteur absent (NON-BRANCHE) d'un moteur présent mais non
  chargeable (ECHEC, code 8) — §3.6 règle 5. T-027 : témoin rouge de `expectColumns`."
  (:require
   [blaze.addon.sql-on-fhir.conformance :as conformance]
   [blaze.test-util :as tu]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [jsonista.core :as j]))

(use-fixtures :each tu/fixture)

(deftest api-test
  (testing "namespace absent du classpath : nil"
    (is (nil? (conformance/api 'blaze.addon.sql-on-fhir.fixture.inexistant/f))))
  (testing "namespace présent : la var"
    (is (= #'conformance/juger (conformance/api 'blaze.addon.sql-on-fhir.conformance/juger))))
  (testing "namespace présent mais non chargeable : l'erreur se propage"
    (is (thrown? Exception (conformance/api 'blaze.addon.sql-on-fhir.fixture.casse/f)))))

(deftest moteur-non-chargeable-test
  (let [moteur (conformance/charger 'blaze.addon.sql-on-fhir.fixture.casse/f
                                    'blaze.addon.sql-on-fhir.fixture.casse/f)
        cas {:title "t" :view {} :expect []}]
    (testing "le moteur porte l'erreur, nommée"
      (is (re-find #"CompilerException|FileNotFound" (:erreur moteur))))
    (testing "ECHEC et non NON-BRANCHE"
      (is (= :echec (first (conformance/juger moteur cas [])))))
    (testing "témoin : moteur absent, NON-BRANCHE"
      (is (= :non-branche
             (first (conformance/juger (conformance/charger 'blaze.addon.sql-on-fhir.fixture.inexistant/f
                                                            'blaze.addon.sql-on-fhir.fixture.inexistant/g)
                                       cas [])))))))

(defn- cas-basic [titre]
  (->> (j/read-value (java.io.File. "conformance/sql-on-fhir-js/tests/basic.json")
                     (j/object-mapper {:decode-key-fn true :bigdecimals true}))
       :tests
       (some #(when (= titre (:title %)) %))))

(defn- moteur-rendant
  "Moteur factice : rend `lignes` telles quelles."
  [lignes]
  {:compile (fn [_] :compiled) :run (fn [_ _] lignes)})

(defn- en-ordre
  "Les lignes attendues, clés dans l'ordre `cles`."
  [lignes cles]
  (mapv (fn [row] (apply array-map (mapcat (fn [k] [k (get row k)]) cles))) lignes))

(deftest expect-columns-test
  (let [cas (cas-basic "column ordering")
        attendues (mapv keyword (:expectColumns cas))]
    (testing "le cas existe et porte au moins deux colonnes"
      (is (< 1 (count attendues))))
    (testing "colonnes dans l'ordre attendu : REUSSIS"
      (is (= [:reussi] (conformance/juger (moteur-rendant (en-ordre (:expect cas) attendues)) cas []))))
    (testing "témoin rouge : colonnes permutées (mêmes valeurs) : ECHEC, message littéral"
      (is (= [:echec (str "colonnes : attendu " (pr-str (:expectColumns cas))
                          ", obtenu " (pr-str (mapv name (reverse attendues))))]
             (conformance/juger (moteur-rendant (en-ordre (:expect cas) (reverse attendues))) cas []))))
    (testing "limite : colonnes lues sur la première ligne seule ; résultat vide : ECHEC"
      (is (= [:echec (str "colonnes : attendu " (pr-str (:expectColumns cas)) ", obtenu []")]
             (conformance/juger (moteur-rendant [])
                                (assoc cas :expect []) []))))))
