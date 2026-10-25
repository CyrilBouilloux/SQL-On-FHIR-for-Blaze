(ns blaze.addon.sql-on-fhir.fhir-path.operator-test
  "T-009 (FP-07…FP-09) : `or`, `<`, `<=`, `>`, `>=`, `+`, `-`, `*`, `/` contre les attendus de
  `attendus.edn` (écrits par `testeur`) ; IB-02 (classes sans implémentation) et IB-03
  (`-compile` d'`AdditiveExpressionContext` remplacé, `+` repris)."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.operator]
   [blaze.addon.sql-on-fhir.test-util :as su]
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

(def ^:private operateurs
  #{"or" "inf" "infeg" "sup" "supeg" "plus" "moins" "fois" "div"})

(def ^:private cas
  (delay (filter #(operateurs (namespace (:id %))) (:cas @su/attendus))))

(defn- ev [expression]
  (su/evaluer expression (su/sonde :pat-vide)))

(deftest valeurs-justes-test
  (testing "les 18 cas de attendus.edn pour ces 9 opérateurs"
    (is (= 18 (count @cas))))
  (doseq [{:keys [id expression entree attendu]} @cas]
    (testing (str id)
      (is (= (:collection attendu) (su/evaluer expression (su/sonde entree)))))))

(deftest logique-a-trois-valeurs-test
  (testing "les 9 couples de la table de FHIRPath N1 §6.5.2"
    (is (= 9 (count (:table-or @su/attendus)))))
  (doseq [{:keys [id expression attendu]} (:table-or @su/attendus)]
    (testing (str id)
      (is (= (:collection attendu) (ev expression))))))

(deftest or-test
  (testing "un opérande non booléen vaut vrai (évaluation en singleton)"
    (is (= [{:type "System.Boolean" :valeur true}] (ev "'a' or false"))))
  (testing "plusieurs éléments : anomalie incorrect"
    (given (su/evaluer "name.given or true" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect))
  (testing "xor n'est pas géré : anomalie unsupported nommant l'opérateur"
    (given (ev "true xor false")
      ::anom/category := ::anom/unsupported
      ::anom/message :# ".*xor.*")))

(deftest comparaison-test
  (testing "chaînes, décimaux, entier contre décimal"
    (is (= [{:type "System.Boolean" :valeur true}] (ev "'a' < 'b'")))
    (is (= [{:type "System.Boolean" :valeur false}] (ev "1.5 >= 2")))
    (is (= [{:type "System.Boolean" :valeur true}] (ev "2 > 1.5"))))
  (testing "dates de même précision ; précisions différentes : vide"
    (is (= [{:type "System.Boolean" :valeur true}] (ev "@2014-01-01 < @2014-01-02")))
    (is (= [] (ev "@2014 < @2014-01-02"))))
  (testing "types incomparables : anomalie incorrect"
    (given (ev "'a' < 1")
      ::anom/category := ::anom/incorrect)
    (given (su/evaluer "name < 1" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect)
    (given (ev "true < false")
      ::anom/category := ::anom/incorrect))
  (testing "plusieurs éléments : anomalie incorrect"
    (given (su/evaluer "name.given < 'b'" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect)))

(deftest arithmetique-test
  (testing "entier contre décimal, décimal contre décimal"
    (is (= [{:type "System.Decimal" :valeur 3.5M}] (ev "1 + 2.5")))
    (is (= [{:type "System.Decimal" :valeur 0.5M}] (ev "2.5 - 2")))
    (is (= [{:type "System.Decimal" :valeur 7.5M}] (ev "3 * 2.5"))))
  (testing "la division rend toujours un décimal, arrondi à la précision de MathContext"
    (is (= "System.Decimal" (:type (first (ev "1 / 3")))))
    (is (= 0.3333333333333333333333333333333333M (:valeur (first (ev "1 / 3"))))))
  (testing "division par zéro : vide, des deux côtés"
    (is (= [] (ev "1.5 / 0")))
    (is (= [] (ev "{} / 0"))))
  (testing "débordement d'entier : anomalie incorrect"
    (given (ev "2147483647 + 1")
      ::anom/category := ::anom/incorrect)
    (given (ev "2147483647 * 2")
      ::anom/category := ::anom/incorrect))
  (testing "opérande non numérique : anomalie incorrect"
    (given (ev "'a' - 1")
      ::anom/category := ::anom/incorrect)
    (given (ev "1 * 'a'")
      ::anom/category := ::anom/incorrect))
  (testing "vide à droite"
    (is (= [] (ev "1 + {}")))
    (is (= [] (ev "{} + 1")))
    (is (= [] (ev "1 - {}"))))
  (testing "div et mod ne sont pas gérés : anomalie unsupported nommant l'opérateur"
    (given (ev "5 div 2")
      ::anom/category := ::anom/unsupported
      ::anom/message :# ".*div.*")
    (given (ev "5 mod 2")
      ::anom/category := ::anom/unsupported
      ::anom/message :# ".*mod.*")))

;; IB-03 : `+` repris à l'identique pour tout ce qui n'est pas numérique : même résultat, même
;; anomalie (catégorie et message) que le `PlusExpression` de Blaze sur les mêmes collections.

(defn- issue [resultat]
  (if (ba/anomaly? resultat)
    (select-keys resultat [::anom/category ::anom/message])
    resultat))

(deftest plus-repris-test
  (doseq [[expression gauche droite]
          [["'a' + 'b'" ["a"] ["b"]]
           ["{} + 'b'" [] ["b"]]
           ["'a' + {}" ["a"] []]
           ["{} + {}" [] []]
           ["('a' | 'b') + 'c'" ["a" "b"] ["c"]]
           ["'a' + ('b' | 'c')" ["a"] ["b" "c"]]
           ["true + 'b'" [true] ["b"]]
           ["'a' + true" ["a"] [true]]
           ["name + 'b'" nil ["b"]]]]
    (testing expression
      (let [obtenu (su/evaluer expression (su/sonde :pat-complet))
            attendu (when gauche
                      (ba/try-anomaly
                       (fp/-eval (fp/->PlusExpression gauche droite) {} [])))]
        (if gauche
          (is (= (issue (if (ba/anomaly? attendu) attendu (mapv su/valeur-systeme attendu)))
                 (issue obtenu)))
          (given obtenu
            ::anom/category := ::anom/incorrect))))))

(deftest operateur-additif-non-gere-test
  (testing "`&` reste délégué à Blaze, qui le refuse comme avant (fault, aucun cas correspondant)"
    (given (ev "'a' & 'b'")
      ::anom/category := ::anom/fault
      ::anom/message :# ".*No matching clause: &.*")))
