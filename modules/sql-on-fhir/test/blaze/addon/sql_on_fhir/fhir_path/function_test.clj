(ns blaze.addon.sql-on-fhir.fhir-path.function-test
  "T-008 (FP-01…FP-06) : `empty`, `not`, `join`, `getResourceKey`, `getReferenceKey`,
  `lowBoundary`, `highBoundary`, contre les attendus de `attendus.edn` (écrits par `testeur`)."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.function]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.fhir.spec.type.system :as system]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]])
  (:import
   [java.time LocalTime]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private fonctions
  #{"empty" "not" "join" "getResourceKey" "getReferenceKey" "lowBoundary" "highBoundary"})

(def ^:private cas
  (delay (filter #(fonctions (namespace (:id %))) (:cas @su/attendus))))

(defn- evaluer-cas [{:keys [expression entree]}]
  (su/evaluer expression (su/sonde entree)))

(defn- primitif-seul? [coll]
  (and (= 1 (count coll)) (not (ba/anomaly? coll))))

(defn- verifier-relation [{:keys [id] :as cas} {:keys [collection-non-vide-a-un-element-primitif egal-a different-de]}]
  (let [obtenu (evaluer-cas cas)]
    (when collection-non-vide-a-un-element-primitif
      (is (primitif-seul? obtenu) (str id)))
    (when egal-a
      (is (= obtenu (evaluer-cas egal-a)) (str id)))
    (when different-de
      (is (not= obtenu (evaluer-cas different-de)) (str id)))))

(deftest valeurs-justes-test
  (testing "les 14 cas de attendus.edn pour ces 7 fonctions"
    (is (= 14 (count @cas))))
  (doseq [{:keys [id attendu] :as c} @cas]
    (testing (str id)
      (if-let [relation (:relation attendu)]
        (verifier-relation c relation)
        (is (= (:collection attendu) (evaluer-cas c)))))))

(deftest formes-sans-argument-test
  (testing "lowBoundary() et highBoundary() compilent sans anomalie, valeur non jugée"
    (doseq [e ["birthDate.lowBoundary()" "birthDate.highBoundary()"]]
      (is (not (ba/anomaly? (fp/compile e))) e))))

(deftest empty-test
  (testing "plusieurs éléments"
    (is (= [{:type "System.Boolean" :valeur false}]
           (su/evaluer "name.given.empty()" (su/sonde :pat-complet))))))

(deftest not-test
  (testing "plusieurs éléments : anomalie incorrect"
    (given (su/evaluer "name.given.not()" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect))
  (testing "un élément non booléen vaut vrai (évaluation en singleton, FHIRPath §3.1.2)"
    (is (= [{:type "System.Boolean" :valeur false}]
           (su/evaluer "id.not()" (su/sonde :pat-complet))))))

(deftest join-test
  (testing "séparateur absent : chaîne vide"
    (is (= [{:type "System.String" :valeur "AnnMarie"}]
           (su/evaluer "name.given.join()" (su/sonde :pat-complet)))))
  (testing "élément non chaîne : anomalie incorrect"
    (given (su/evaluer "active.join(',')" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect))
  (testing "séparateur non chaîne : anomalie incorrect"
    (given (su/evaluer "name.given.join(1)" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect)))

(deftest get-resource-key-test
  (testing "un élément qui n'est pas une ressource : anomalie incorrect"
    (given (su/evaluer "name.getResourceKey()" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect)))

(deftest get-reference-key-test
  (testing "sans type : la clé de toute référence littérale"
    (is (= (su/evaluer "subject.getReferenceKey(Patient)" (su/sonde :obs-patient))
           (su/evaluer "subject.getReferenceKey()" (su/sonde :obs-patient))))
    (is (= 1 (count (su/evaluer "subject.getReferenceKey()" (su/sonde :obs-groupe))))))
  (testing "un élément qui n'est pas une référence : anomalie incorrect"
    (given (su/evaluer "code.getReferenceKey()" (su/sonde :obs-patient))
      ::anom/category := ::anom/incorrect))
  (testing "type inconnu : anomalie incorrect"
    (given (su/evaluer "subject.getReferenceKey(Inconnu)" (su/sonde :obs-patient))
      ::anom/category := ::anom/incorrect))
  (testing "référence sans littéral exploitable : vide"
    (is (= [] (su/evaluer "subject.getReferenceKey(Patient)" (su/sonde :pat-vide))))))

(deftest boundary-test
  (testing "précision 4, 6 et 8 sur une année"
    (is (= [{:type "System.Date" :valeur "2014"}]
           (su/evaluer "birthDate.lowBoundary(4)" (su/sonde :pat-complet))))
    (is (= [{:type "System.Date" :valeur "2014-01-01"}]
           (su/evaluer "birthDate.lowBoundary(8)" (su/sonde :pat-complet))))
    (is (= [{:type "System.Date" :valeur "2014-12-31"}]
           (su/evaluer "birthDate.highBoundary(8)" (su/sonde :pat-complet)))))
  (testing "sans argument : précision maximale, valeur non jugée mais un seul élément"
    (is (= 1 (count (su/evaluer "birthDate.lowBoundary()" (su/sonde :pat-complet))))))
  (testing "fin de mois bissextile et date complète"
    (let [patient #(assoc (su/sonde :pat-complet) :birthDate %)
          date #(system/parse-date %)]
      (is (= [{:type "System.Date" :valeur "2024-02-29"}]
             (su/evaluer "birthDate.highBoundary(8)" (patient (date "2024-02")))))
      (is (= [{:type "System.Date" :valeur "2024-03-05"}]
             (su/evaluer "birthDate.highBoundary(8)" (patient (date "2024-03-05")))))))
  (testing "précision non gérée : anomalie incorrect"
    (given (su/evaluer "birthDate.lowBoundary(5)" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect))
  (testing "précision non entière : anomalie incorrect"
    (given (su/evaluer "birthDate.lowBoundary('a')" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect))
  (testing "élément qui n'est pas une date : anomalie incorrect"
    (given (su/evaluer "active.lowBoundary(6)" (su/sonde :pat-complet))
      ::anom/category := ::anom/incorrect))
  (testing "date-heure, décimal, heure avec précision : anomalie unsupported (forme hors F-005)"
    (doseq [e ["@2014-01-02T03:04:05Z.lowBoundary(6)" "1.5.highBoundary(2)" "@T12:34:00.lowBoundary(3)"]]
      (given (su/evaluer e (su/sonde :pat-vide))
        ::anom/category := ::anom/unsupported)))
  (testing "date-heure sans argument, partielle ou complète (bornes d'une période, fuseaux extrêmes)"
    (doseq [[e bas haut]
            [["@2014T" "2014-01-01T00:00:00.000+14:00" "2014-12-31T23:59:59.999-12:00"]
             ["@2024-02T" "2024-02-01T00:00:00.000+14:00" "2024-02-29T23:59:59.999-12:00"]
             ["@2014-01-02T03:04:05" "2014-01-02T03:04:05.000+14:00" "2014-01-02T03:04:05.999-12:00"]
             ["@2014-01-02T03:04:05Z" "2014-01-02T03:04:05.000Z" "2014-01-02T03:04:05.999Z"]
             ["@2014-01-02T03:04:05.120+02:00" "2014-01-02T03:04:05.120+02:00" "2014-01-02T03:04:05.120+02:00"]]]
      (is (= [bas] (map :valeur (su/evaluer (str "(" e ").lowBoundary()") (su/sonde :pat-vide)))) e)
      (is (= [haut] (map :valeur (su/evaluer (str "(" e ").highBoundary()") (su/sonde :pat-vide)))) e)))
  (testing "heure sans argument : millisecondes données conservées"
    (let [obs {:fhir/type :fhir/Observation :value (LocalTime/parse "12:34:00.500")}]
      (is (= ["12:34:00.500" "12:34:00.500"]
             (map :valeur (concat (su/evaluer "value.lowBoundary()" obs)
                                  (su/evaluer "value.highBoundary()" obs)))))))
  (testing "décimal sans argument : la précision lue (échelle du BigDecimal)"
    (is (= [0.995M 1.005M]
           (map :valeur (concat (su/evaluer "1.00.lowBoundary()" (su/sonde :pat-vide))
                                (su/evaluer "1.00.highBoundary()" (su/sonde :pat-vide))))))
    (is (= [0.095M 0.105M]
           (map :valeur (concat (su/evaluer "0.10.lowBoundary()" (su/sonde :pat-vide))
                                (su/evaluer "0.10.highBoundary()" (su/sonde :pat-vide))))))))
