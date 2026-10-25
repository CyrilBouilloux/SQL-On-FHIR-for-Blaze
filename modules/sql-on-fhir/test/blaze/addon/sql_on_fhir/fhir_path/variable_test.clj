(ns blaze.addon.sql-on-fhir.fhir-path.variable-test
  "T-010 (FP-10, FP-11, FP-13) : `$this`, `%nom`, `%rowIndex` contre les attendus de
  `attendus.edn` ; IB-02 (`ThisInvocation`, `ExternalConstantTerm`) et IB-05 (contexte de
  `-eval` : `:constants`, `::variable/row-index`)."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.variable :as variable]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private cas
  (delay (filter #(#{"this" "constante" "rowIndex"} (namespace (:id %))) (:cas @su/attendus))))

(defn- constantes
  "Traduit `:constantes` de l'oracle (seul `valueInteger` en jeu ici ; les 18 types : FP-12)
  en `{nom collection}`, forme du contexte de `-eval`."
  [constantes]
  (into {}
        (map (fn [{:keys [name cle valeur]}]
               (assert (= "valueInteger" cle))
               [name [(int valeur)]]))
        constantes))

(defn- contexte
  "Traduit `:contexte {:row-index n}` (forme abstraite de l'oracle) et `:constantes` en contexte
  de `-eval`."
  [cas]
  (cond-> {:constants (constantes (:constantes cas))}
    (some? (get-in cas [:contexte :row-index]))
    (assoc ::variable/row-index (get-in cas [:contexte :row-index]))))

(deftest valeurs-justes-test
  (testing "les 6 cas de attendus.edn pour ces 3 expressions"
    (is (= 6 (count @cas))))
  (doseq [{:keys [id expression entree attendu] :as c} @cas]
    (testing (str id)
      (let [obtenu (su/evaluer-avec expression (su/sonde entree) (contexte c))]
        (if-let [{:keys [categorie message-contient]} (:anomalie attendu)]
          (given obtenu
            ::anom/category := (keyword "cognitect.anomalies" (name categorie))
            ::anom/message :# (str ".*" (first message-contient) ".*"))
          (is (= (:collection attendu) obtenu)))))))

(deftest constante-absente-test
  (testing "aucune constante fournie : incorrect, nomme la constante, jamais fault"
    (given (su/evaluer-avec "%absente" (su/sonde :pat-vide) {})
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*absente.*"))
  (testing "constante nommée par une chaîne : %`nom`"
    (given (su/evaluer-avec "%'a b'" (su/sonde :pat-vide) {:constants {"a b" [(int 1)]}})
      [0 :valeur] := 1)))

(deftest this-test
  (testing "$this seul rend l'élément courant"
    (given (su/evaluer "$this.id" (su/sonde :pat-complet))
      [0 :valeur] := "pt1")))

(deftest row-index-test
  (testing "sans contexte : 0 ; avec : la valeur du contexte"
    (is (= [{:type "System.Integer" :valeur 0}]
           (su/evaluer-avec "%rowIndex" (su/sonde :pat-vide) {})))
    (is (= [{:type "System.Integer" :valeur 3}]
           (su/evaluer-avec "%rowIndex" (su/sonde :pat-vide) {::variable/row-index 3})))))
