(ns blaze.addon.sql-on-fhir.fhir-path.operator
  "Opérateurs FHIRPath absents de Blaze : `or`, `<`, `<=`, `>`, `>=`, `*`, `/` (classes sans
  implémentation de `FPCompiler`, `extend-protocol`, IB-02) et `-` (FP-07…FP-09). `-` exige de
  remplacer le `-compile` d'`AdditiveExpressionContext` (IB-03) : `+` est repris — tout ce qui
  n'est pas numérique est délégué au `PlusExpression` de Blaze, à l'identique."
  (:require
   [blaze.anomaly :as ba :refer [throw-anom]]
   [blaze.fhir-path :as fp]
   [blaze.fhir.spec :as fhir-spec]
   [clojure.string :as str])
  (:import
   [clojure.lang IReduceInit]
   [java.math MathContext]
   [java.time.temporal TemporalAccessor]
   [java.util Locale]
   [org.antlr.v4.runtime.tree TerminalNode]
   [org.cqframework.cql.gen
    fhirpathParser$AdditiveExpressionContext
    fhirpathParser$EqualityExpressionContext
    fhirpathParser$InequalityExpressionContext
    fhirpathParser$MultiplicativeExpressionContext
    fhirpathParser$OrExpressionContext]))

(set! *warn-on-reflection* true)

(defn- items [coll]
  (.reduce ^IReduceInit coll conj []))

(defn- primitive-value [x]
  (cond-> x (fhir-spec/primitive-val? x) :value))

(defn- operand
  "La valeur système de l'unique élément de `coll`, nil si `coll` est vide ; sinon `incorrect`."
  [coll]
  (let [xs (items coll)]
    (case (count xs)
      0 nil
      1 (primitive-value (first xs))
      (throw-anom (ba/incorrect (format "unable to evaluate `%s` as singleton" (pr-str xs)))))))

(defn- numeric? [x]
  (or (instance? Integer x) (instance? BigDecimal x)))

(defn- numeric-singleton? [coll]
  (and (= 1 (count (items coll))) (numeric? (operand coll))))

(defn- decimal ^BigDecimal [x]
  (if (instance? BigDecimal x) x (BigDecimal/valueOf (long x))))

(defn- integer-result [n]
  (if (<= Integer/MIN_VALUE n Integer/MAX_VALUE)
    (int n)
    (throw-anom (ba/incorrect "integer overflow"))))

(defn- calculate [op x y]
  (if (and (instance? Integer x) (instance? Integer y))
    (let [x (long x) y (long y)]
      (integer-result (case op :add (+ x y) :subtract (- x y) :multiply (* x y))))
    (let [x (decimal x) y (decimal y)]
      (case op :add (.add x y) :subtract (.subtract x y) :multiply (.multiply x y)))))

(defn- divide
  "Toujours décimal ; vide (nil) si le diviseur est nul."
  [x y]
  (let [y (decimal y)]
    (when-not (zero? (.signum y))
      (.divide (decimal x) y MathContext/DECIMAL128))))

;; 6.6. Math : - * /

(deftype ArithmeticExpression [symbol f left right]
  fp/Expression
  (-eval [_ context coll]
    (let [x (operand (fp/-eval left context coll))
          y (operand (fp/-eval right context coll))]
      (cond
        (or (nil? x) (nil? y)) []
        (not (and (numeric? x) (numeric? y)))
        (throw-anom (ba/incorrect (format "non-numeric operand of `%s`" symbol)))
        :else (if-some [r (f x y)] [r] [])))))

(defn- arithmetic [symbol f left-ctx right-ctx]
  (->ArithmeticExpression symbol f (fp/-compile left-ctx) (fp/-compile right-ctx)))

;; 6.6.3. + (addition) : numérique ajouté ; le reste est le PlusExpression de Blaze.

(deftype AddExpression [left right]
  fp/Expression
  (-eval [_ context coll]
    (let [l (items (fp/-eval left context coll))]
      (cond
        (numeric-singleton? l)
        (let [r (items (fp/-eval right context coll))]
          (cond
            (numeric-singleton? r) [(calculate :add (operand l) (operand r))]
            (empty? r) []
            :else (fp/-eval (fp/->PlusExpression l r) context coll)))

        (empty? l)
        (let [r (items (fp/-eval right context coll))]
          (if (numeric-singleton? r)
            []
            (fp/-eval (fp/->PlusExpression l r) context coll)))

        :else (fp/-eval (fp/->PlusExpression l right) context coll)))))

(defn- symbol-of [ctx]
  (.getText (.getSymbol ^TerminalNode (.getChild ^fhirpathParser$AdditiveExpressionContext ctx 1))))

;; IB-03 : le seul remplacement d'un cas existant. `defonce` : un rechargement ne capture pas
;; notre propre fonction. Les autres fonctions de la classe sont conservées (fusion).
(defonce ^:private original-additive-compile
  (get-in fp/FPCompiler [:impls fhirpathParser$AdditiveExpressionContext :-compile]))

(extend fhirpathParser$AdditiveExpressionContext fp/FPCompiler
        (assoc (get-in fp/FPCompiler [:impls fhirpathParser$AdditiveExpressionContext])
               :-compile
               (fn [^fhirpathParser$AdditiveExpressionContext ctx]
                 (case (symbol-of ctx)
                   "+" (->AddExpression (fp/-compile (.expression ctx 0))
                                        (fp/-compile (.expression ctx 1)))
                   "-" (arithmetic "-" #(calculate :subtract %1 %2) (.expression ctx 0) (.expression ctx 1))
                   (original-additive-compile ctx)))))

;; IB-15 : `~` et `!~` (FHIRPath N1 §6.1.2, §6.1.4) exigent de remplacer le `-compile`
;; d'`EqualityExpressionContext`, qui n'a pas de cas par défaut. `=` et `!=` sont délégués à
;; l'original capturé, à l'identique. Public pour que les tests prouvent la délégation.

(defonce original-equality-compile
  (get-in fp/FPCompiler [:impls fhirpathParser$EqualityExpressionContext :-compile]))

(deftype Singleton [coll]
  fp/Expression
  (-eval [_ _ _] coll))

(defn- normalize-blanks [^String s]
  (-> s str/trim (str/replace #"\s+" " ") (.toLowerCase Locale/ROOT)))

(defn- equivalence-operand
  "Les éléments de `coll` ; `incorrect` nommant `symbol` et la cardinalité si plusieurs."
  [symbol coll]
  (let [xs (items coll)]
    (when (< 1 (count xs))
      (throw-anom (ba/incorrect (format "`%s` operator requires singleton operands, found cardinality %d"
                                        symbol (count xs)))))
    xs))

(deftype EquivalentExpression [symbol left right]
  fp/Expression
  (-eval [_ context coll]
    (let [l (equivalence-operand symbol (fp/-eval left context coll))
          r (equivalence-operand symbol (fp/-eval right context coll))]
      (cond
        (and (empty? l) (empty? r)) [true]
        (or (empty? l) (empty? r)) [false]
        :else
        (let [x (primitive-value (first l))
              y (primitive-value (first r))]
          (if (and (string? x) (string? y))
            [(= (normalize-blanks x) (normalize-blanks y))]
            ;; autres types : l'égalité de Blaze (précision décimale non arrondie, non mesuré)
            (fp/-eval (fp/->EqualExpression (->Singleton l) (->Singleton r)) context coll)))))))

(deftype NotEquivalentExpression [equivalent]
  fp/Expression
  (-eval [_ context coll]
    (mapv not (items (fp/-eval equivalent context coll)))))

(extend fhirpathParser$EqualityExpressionContext fp/FPCompiler
        (assoc (get-in fp/FPCompiler [:impls fhirpathParser$EqualityExpressionContext])
               :-compile
               (fn [^fhirpathParser$EqualityExpressionContext ctx]
                 (let [symbol (.getText (.getSymbol ^TerminalNode (.getChild ctx 1)))
                       equivalent #(->EquivalentExpression % (fp/-compile (.expression ctx 0))
                                                           (fp/-compile (.expression ctx 1)))]
                   (case symbol
                     "~" (equivalent "~")
                     "!~" (->NotEquivalentExpression (equivalent "!~"))
                     (original-equality-compile ctx))))))

;; IB-02 : classes sans implémentation dans le jar.

(defn- unsupported-operator [symbol]
  (throw-anom (ba/unsupported (format "unsupported operator `%s`" symbol))))

;; 6.5.2. or

(defn- boolean-operand
  "nil si vide ; sinon le booléen, un élément non booléen valant vrai (évaluation en singleton)."
  [coll]
  (when-some [x (operand coll)]
    (if (boolean? x) x true)))

(deftype OrExpression [expr-a expr-b]
  fp/Expression
  (-eval [_ context coll]
    (let [a (boolean-operand (fp/-eval expr-a context coll))]
      (if (true? a)
        [true]
        (let [b (boolean-operand (fp/-eval expr-b context coll))]
          (cond
            (true? b) [true]
            (and (false? a) (false? b)) [false]
            :else []))))))

;; 6.2. Comparison

(defn- compare-values
  "Rend le signe de la comparaison, nil si les précisions temporelles diffèrent."
  [x y]
  (cond
    (and (numeric? x) (numeric? y)) (.compareTo (decimal x) (decimal y))
    (and (string? x) (string? y)) (.compareTo ^String x ^String y)
    (and (instance? TemporalAccessor x) (instance? TemporalAccessor y))
    (when (identical? (class x) (class y))
      (.compareTo ^Comparable x y))
    :else (throw-anom (ba/incorrect "operands of a comparison have incompatible types"))))

(deftype ComparisonExpression [test left right]
  fp/Expression
  (-eval [_ context coll]
    (let [x (operand (fp/-eval left context coll))
          y (operand (fp/-eval right context coll))]
      (if-some [c (when (and (some? x) (some? y)) (compare-values x y))]
        [(test c)]
        []))))

(extend-protocol fp/FPCompiler
  fhirpathParser$OrExpressionContext
  (-compile [ctx]
    (let [symbol (.getText (.getSymbol ^TerminalNode (.getChild ctx 1)))]
      (if (= "or" symbol)
        (->OrExpression (fp/-compile (.expression ctx 0)) (fp/-compile (.expression ctx 1)))
        (unsupported-operator symbol))))

  fhirpathParser$InequalityExpressionContext
  (-compile [ctx]
    (let [test (case (.getText (.getSymbol ^TerminalNode (.getChild ctx 1)))
                 "<" neg? "<=" (complement pos?) ">" pos? ">=" (complement neg?))]
      (->ComparisonExpression test (fp/-compile (.expression ctx 0)) (fp/-compile (.expression ctx 1)))))

  fhirpathParser$MultiplicativeExpressionContext
  (-compile [ctx]
    (let [symbol (.getText (.getSymbol ^TerminalNode (.getChild ctx 1)))]
      (case symbol
        "*" (arithmetic symbol #(calculate :multiply %1 %2) (.expression ctx 0) (.expression ctx 1))
        "/" (arithmetic symbol divide (.expression ctx 0) (.expression ctx 1))
        (unsupported-operator symbol)))))
