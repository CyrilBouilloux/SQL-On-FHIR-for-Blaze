(ns blaze.addon.sql-on-fhir.fhir-path.unsupported-test
  "T-013 (FP-14) : construction de la grammaire sans implémentation → anomalie `unsupported`
  nommant la construction, jamais `fault`. IB-02 : `extend-protocol` sur des classes absentes de
  `(extenders FPCompiler)` avant chargement du module."
  (:require
   [blaze.addon.sql-on-fhir.fhir-path.unsupported]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.anomaly :as ba]
   [blaze.fhir-path :as fp]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [juxt.iota :refer [given]])
  (:import
   [org.antlr.v4.runtime CharStreams CommonTokenStream]
   [org.antlr.v4.runtime.tree Trees]
   [org.cqframework.cql.gen fhirpathLexer fhirpathParser]))

(set! *warn-on-reflection* true)

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private alternatives
  "Domaine mesuré le 2026-10-09 : [expression, contexte produit par le parseur]."
  [["active implies true" "ImpliesExpressionContext"]
   ["$index" "IndexInvocationContext"]
   ["1 in name" "MembershipExpressionContext"]
   ["-1" "PolarityExpressionContext"]
   ["5 'mg'" "QuantityLiteralContext"]
   ["@T10:00" "TimeLiteralContext"]
   ["$total" "TotalInvocationContext"]])

(defn- contextes
  "Noms simples des classes de contexte de l'arbre que le parseur de Blaze produit pour
  `expression` (racine comprise) : `$index` est un `TermExpression` dont un descendant est
  un `IndexInvocation`."
  [expression]
  (let [parseur (fhirpathParser. (CommonTokenStream. (fhirpathLexer. (CharStreams/fromString expression))))
        racine (.expression parseur)]
    (into #{} (map #(.getSimpleName (class %))) (cons racine (Trees/getDescendants racine)))))

(deftest construction-non-geree-test
  (doseq [[expression classe] alternatives]
    (testing (str classe " : `" expression "`")
      (is (contains? (contextes expression) classe))
      (given (fp/compile expression)
        ::anom/category := ::anom/unsupported
        ::anom/message :# (str ".*`" (subs classe 0 (- (count classe) (count "Context"))) "`.*")))))

(deftest absentes-avant-chargement-test
  (testing "IB-02 : aucune des 7 classes n'est implémentée par le jar (sinon elle serait remplacée)"
    (let [extenders (set (:extenders-avant (su/rapport-existants)))]
      (doseq [[_ classe] alternatives]
        (is (not (contains? extenders (str "org.cqframework.cql.gen.fhirpathParser$" classe))) classe)))))

(deftest cas-existants-intacts-test
  (testing "une expression gérée compile encore"
    (is (not (ba/anomaly? (fp/compile "name.given"))))))
