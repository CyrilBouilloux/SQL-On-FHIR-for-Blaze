(ns blaze.addon.sql-on-fhir.fhir-path.texte-test
  "T-079 (F-017) : fonctions de texte du socle (`startsWith`, `endsWith`, `contains`, `upper`,
  `lower`, `length`, `substring`, `indexOf`, `replace`) et `~`, `!~` (FHIRPath N1 §5.6, §6.1.2,
  §6.1.4) ; IB-04 et IB-15 (`=` et `!=` délégués au `-compile` d'origine)."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.function]
   [blaze.addon.sql-on-fhir.fhir-path.operator :as operator]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.fhir-path :as fp]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]])
  (:import
   [org.antlr.v4.runtime CharStreams CommonTokenStream]
   [org.cqframework.cql.gen fhirpathLexer fhirpathParser]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(defn- patient [& familles]
  {:fhir/type :fhir/Patient :id "x"
   :name (mapv (fn [f] (cond-> {:fhir/type :fhir/HumanName} f (assoc :family f))) familles)})

(def ^:private p1 (patient "Wunsch504"))
(def ^:private p3 {:fhir/type :fhir/Patient :id "x"})
(def ^:private deux-noms (patient "Wunsch504" "Autre"))

(defn- t [valeur] [{:type "System.String" :valeur valeur}])
(defn- b [valeur] [{:type "System.Boolean" :valeur valeur}])
(defn- i [valeur] [{:type "System.Integer" :valeur valeur}])

(defn- ev [expression ressource]
  (su/evaluer expression ressource))

(def ^:private f "name.first().family.")

(def ^:private table
  "Table T de F-017 : expression, valeur attendue sur P1."
  [["startsWith('Wun')" (b true)]
   ["startsWith('wun')" (b false)]
   ["endsWith('504')" (b true)]
   ["contains('sch')" (b true)]
   ["lower()" (t "wunsch504")]
   ["upper()" (t "WUNSCH504")]
   ["length()" (i 9)]
   ["substring(0,3)" (t "Wun")]
   ["substring(6)" (t "504")]
   ["indexOf('sch')" (i 3)]
   ["replace('504','')" (t "Wunsch")]])

(deftest table-sur-p1-test
  (doseq [[e attendu] table]
    (testing e
      (is (= attendu (ev (str f e) p1))))))

(deftest equivalence-sur-p1-test
  (testing "`~` ignore la casse, `!~` en est la négation"
    (is (= (b true) (ev "name.first().family ~ 'wunsch504'" p1)))
    (is (= (b false) (ev "name.first().family !~ 'WUNSCH504'" p1)))
    (is (= (b false) (ev "name.first().family ~ 'autre'" p1)))
    (is (= (b true) (ev "name.first().family !~ 'autre'" p1))))
  (testing "`~` normalise les blancs"
    (is (= (b true) (ev "'a  b' ~ ' A b '" p1))))
  (testing "`=` et `!=` inchangés"
    (is (= (b false) (ev "name.first().family = 'wunsch504'" p1)))
    (is (= (b false) (ev "name.first().family = 'Wuns*'" p1)))
    (is (= (b true) (ev "name.first().family != 'wunsch504'" p1))))
  (testing "autres types : délégation à l'égalité"
    (is (= (b true) (ev "1 ~ 1" p1)))
    (is (= (b false) (ev "1 ~ 2" p1)))
    (is (= (b true) (ev "true ~ true" p1)))
    (is (= (b false) (ev "1 ~ 'a'" p1)))))

(deftest entree-vide-test
  (testing "les 9 fonctions rendent vide sur une entrée vide"
    (doseq [[e _] table]
      (testing e
        (is (= [] (ev (str f e) p3))))))
  (testing "paramètre vide : vide"
    (is (= [] (ev (str f "startsWith(name.family)") p1)))
    (is (= [] (ev (str f "replace(name.family, 'x')") p1))))
  (testing "N1 §6.1.2 : `~` avec un côté vide est faux, `!~` vrai"
    (is (= (b false) (ev "name.first().family ~ 'wunsch504'" p3)))
    (is (= (b true) (ev "name.first().family !~ 'WUNSCH504'" p3)))
    (is (= (b false) (ev "'wunsch504' ~ name.first().family" p3))))
  (testing "N1 §6.1.2 : deux côtés vides, `~` est vrai"
    (is (= (b true) (ev "name.first().family ~ name.first().family" p3)))
    (is (= (b false) (ev "name.first().family !~ name.first().family" p3)))))

(deftest cardinalite-test
  (testing "plusieurs éléments : incorrect, nommant la fonction et la cardinalité"
    (doseq [e ["startsWith('W')" "endsWith('W')" "contains('W')" "upper()" "lower()" "length()"
               "substring(0)" "indexOf('W')" "replace('W','')"]]
      (testing e
        (given (ev (str "name.family." e) deux-noms)
          ::anom/category := ::anom/incorrect
          ::anom/message :# ".*`[A-Za-z]+`.*cardinality 2.*"))))
  (testing "le paramètre à plusieurs éléments est refusé de même"
    (given (ev "name.first().family.startsWith('a' | 'b')" deux-noms)
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*`startsWith`.*cardinality 2.*"))
  (testing "`~` : un côté à plusieurs éléments"
    (given (ev "name.family ~ 'x'" deux-noms)
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*`~`.*cardinality 2.*")
    (given (ev "name.family !~ 'x'" deux-noms)
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*`!~`.*cardinality 2.*")))

(deftest types-et-bornes-test
  (testing "entrée non chaîne : incorrect"
    (given (ev "active.upper()" (assoc p1 :active true))
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*`upper`.*"))
  (testing "paramètre du mauvais type : incorrect"
    (given (ev (str f "startsWith(1)") p1)
      ::anom/category := ::anom/incorrect)
    (given (ev (str f "substring('a')") p1)
      ::anom/category := ::anom/incorrect))
  (testing "paramètre absent : incorrect, à la compilation"
    (given (fp/compile "'a'.startsWith()")
      ::anom/category := ::anom/incorrect
      ::anom/message :# ".*`startsWith`.*"))
  (testing "substring : hors bornes, vide ; longueur"
    (is (= [] (ev (str f "substring(0 - 1)") p1)))
    (is (= [] (ev (str f "substring(10)") p1)))
    (is (= (t "") (ev (str f "substring(9)") p1)))
    (is (= (t "504") (ev (str f "substring(6, 99)") p1)))
    (is (= (t "") (ev (str f "substring(6, 0)") p1)))
    (is (= [] (ev (str f "substring(6, 0 - 1)") p1))))
  (testing "chaîne vide : startsWith vrai, indexOf 0, length 0"
    (is (= (b true) (ev (str f "startsWith('')") p1)))
    (is (= (i 0) (ev (str f "indexOf('')") p1)))
    (is (= (i 0) (ev "''.length()" p1))))
  (testing "indexOf absent : -1 ; replace d'un motif vide intercale"
    (is (= (i -1) (ev (str f "indexOf('zzz')") p1)))
    (is (= (t "-a-b-") (ev "'ab'.replace('', '-')" p1))))
  (testing "points de code, pas unités UTF-16"
    (is (= (i 2) (ev "'a😀'.length()" p1)))
    (is (= (t "😀") (ev "'a😀'.substring(1)" p1)))
    (is (= (i 1) (ev "'a😀b'.indexOf('😀')" p1)))
    (is (= (i 2) (ev "'a😀b'.indexOf('b')" p1)))))

(deftest hors-socle-test
  (testing "chaque fonction hors socle est refusée en nommant la fonction"
    (doseq [fonction ["matches" "replaceMatches" "trim" "toChars"]]
      (testing fonction
        (given (ev (str f fonction "('x')") p1)
          ::anom/category := ::anom/incorrect
          ::anom/message :# (str ".*`" fonction "`.*"))))))

;; IB-15 : le -compile d'origine d'EqualityExpressionContext est repris à l'identique.

(defn- ctx [expression]
  (let [lexer (fhirpathLexer. (CharStreams/fromString ^String expression))
        parser (fhirpathParser. (CommonTokenStream. lexer))]
    (.expression parser)))

(deftest egalite-deleguee-test
  (testing "`=` et `!=` rendent exactement ce que rend l'original (identical?)"
    (doseq [e ["a = 'x'" "a != 'x'"]]
      (let [reponse (Object.)
            appels (atom [])]
        (with-redefs [operator/original-equality-compile (fn [c] (swap! appels conj c) reponse)]
          (let [c (ctx e)]
            (is (identical? reponse (fp/-compile c)) e)
            (is (= [c] @appels) e))))))
  (testing "`~` et `!~` n'appellent pas l'original"
    (doseq [e ["a ~ 'x'" "a !~ 'x'"]]
      (let [appels (atom 0)]
        (with-redefs [operator/original-equality-compile (fn [_] (swap! appels inc) nil)]
          (is (some? (fp/-compile (ctx e))) e)
          (is (zero? @appels) e))))))
