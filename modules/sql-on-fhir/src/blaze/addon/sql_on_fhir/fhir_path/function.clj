(ns blaze.addon.sql-on-fhir.fhir-path.function
  "Fonctions FHIRPath absentes de Blaze, ajoutées par `defmethod` sur la multiméthode ouverte
  `blaze.fhir-path/function-expression` (FP-01…FP-06, registre `docs/impacts-blaze.md`, IB-04).
  Chaque méthode ajoutée est un cas absent du jar : aucune méthode existante n'est redéfinie."
  (:require
   [blaze.anomaly :as ba :refer [throw-anom]]
   [blaze.fhir-path :as fp]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.fhir.spec.references :as fsr]
   [blaze.fhir.spec.type.system :as system]
   [clojure.string :as str])
  (:import
   [clojure.lang IReduceInit]
   [java.math BigDecimal BigInteger]
   [java.time LocalDateTime LocalTime OffsetDateTime YearMonth ZoneOffset]
   [java.time.format DateTimeFormatter]
   [java.util Locale]
   [org.cqframework.cql.gen fhirpathParser$ParamListContext]))

(set! *warn-on-reflection* true)

(defn- primitive-value
  "La valeur système d'un élément FHIR primitif, sinon l'élément lui-même."
  [x]
  (cond-> x (fhir-spec/primitive-val? x) :value))

(defn- items [^IReduceInit coll]
  (.reduce coll conj []))

(defn- param
  "Compile le paramètre d'indice `i` de `params-ctx`, ou rend nil s'il est absent."
  [^fhirpathParser$ParamListContext params-ctx i]
  (some-> params-ctx (.expression (int i)) fp/-compile))

(defn- singleton-param
  "La valeur du paramètre `expr` (nil si absent ou vide) évalué dans `context` sur `coll`,
  qui doit satisfaire `pred` ; sinon `msg` est l'anomalie `incorrect`."
  [expr context coll pred msg]
  (when expr
    (let [[x & more] (items (fp/-eval expr context coll))
          x (primitive-value x)]
      (when (or more (and (some? x) (not (pred x))))
        (throw-anom (ba/incorrect msg)))
      x)))

;; 5.1.1. empty() : Boolean

(deftype EmptyFunctionExpression []
  fp/Expression
  (-eval [_ _ coll]
    [(empty? (items coll))]))

(defmethod fp/function-expression "empty"
  [_ _]
  (->EmptyFunctionExpression))

;; 6.5.3. not() : Boolean

(deftype NotFunctionExpression []
  fp/Expression
  (-eval [_ _ coll]
    (case (count (items coll))
      0 []
      1 (let [x (primitive-value (first (items coll)))]
          ;; évaluation en singleton : un élément non booléen vaut vrai
          [(if (boolean? x) (not x) false)])
      (throw-anom (ba/incorrect "unable to evaluate multiple items as singleton in `not` function")))))

(defmethod fp/function-expression "not"
  [_ _]
  (->NotFunctionExpression))

;; join([separator: String]) : String

(deftype JoinFunctionExpression [separator]
  fp/Expression
  (-eval [_ context coll]
    (let [strings (mapv primitive-value (items coll))
          sep (singleton-param separator context coll string?
                               "non-string separator in `join` function")]
      (cond
        (empty? strings) []
        (every? string? strings) [(str/join (or sep "") strings)]
        :else (throw-anom (ba/incorrect "non-string item in `join` function"))))))

(defmethod fp/function-expression "join"
  [_ params-ctx]
  (->JoinFunctionExpression (param params-ctx 0)))

;; getResourceKey() : KeyType, getReferenceKey([resource: type specifier]) : KeyType
;; La clé est opaque (spécification) : « Type/id », la même pour la ressource et pour la
;; référence littérale relative qui la désigne.

(defn- key-of [type id]
  (str type "/" id))

(deftype GetResourceKeyFunctionExpression []
  fp/Expression
  (-eval [_ _ coll]
    (mapv
     (fn [{:fhir/keys [type] :keys [id]}]
       (if (and type id)
         (key-of (name type) id)
         (throw-anom (ba/incorrect "non-resource item in `getResourceKey` function"))))
     (items coll))))

(defmethod fp/function-expression "getResourceKey"
  [_ _]
  (->GetResourceKeyFunctionExpression))

(defn- reference-key
  "La clé de la ressource désignée par `reference`, vide si elle n'est pas du type `type`
  (nil : tout type) ou si ce n'est pas une référence littérale relative."
  [type reference]
  (when-not (identical? :fhir/Reference (:fhir/type reference))
    (throw-anom (ba/incorrect "non-reference item in `getReferenceKey` function")))
  (when-let [[ref-type id] (some-> reference :reference :value fsr/split-literal-ref)]
    (when (or (nil? type) (= type (keyword "fhir" ref-type)))
      [(key-of ref-type id)])))

(deftype GetReferenceKeyFunctionExpression [type]
  fp/Expression
  (-eval [_ _ coll]
    (into [] (mapcat #(reference-key type %)) (items coll))))

(defmethod fp/function-expression "getReferenceKey"
  [_ ^fhirpathParser$ParamListContext params-ctx]
  (->GetReferenceKeyFunctionExpression
   (some-> params-ctx (.expression 0) fp/-compile-as-type-specifier)))

;; lowBoundary([precision: Integer]), highBoundary([precision: Integer]) (FHIRPath v3.0.0-ballot).
;; System.Date : précision 4 (année), 6 (mois) ou 8 (jour) ; absente : 8.
;; Sans argument seulement : System.Decimal (précision = échelle lue), System.DateTime et
;; System.Time ; la forme avec précision y reste `unsupported`. Les bornes de date-heure et
;; d'heure sont rendues en texte ISO 8601 à la milliseconde : les types système de Blaze
;; (OffsetDateTime, LocalTime) n'écrivent pas les millisecondes nulles.

(defn- date-boundary [high? precision date]
  (let [[year month day] (mapv #(Integer/parseInt ^String %) (str/split (str date) #"-"))
        month (or month (if high? 12 1))
        day (or day (if high? (.lengthOfMonth (YearMonth/of (int year) (int month))) 1))]
    (case (long precision)
      4 (system/date year)
      6 (system/date year month)
      8 (system/date year month day)
      (throw-anom (ba/incorrect (format "unsupported precision `%d` in boundary function, expected 4, 6 or 8" precision))))))

(defn- decimal-boundary
  "Demi-unité de la dernière décimale lue, retranchée (bas) ou ajoutée (haut)."
  [high? ^BigDecimal x]
  (let [half (BigDecimal. (BigInteger/valueOf 5) (inc (.scale x)))]
    (if high? (.add x half) (.subtract x half))))

(def ^:private ^DateTimeFormatter date-time-format
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))

(def ^:private ^DateTimeFormatter time-format
  (DateTimeFormatter/ofPattern "HH:mm:ss.SSS"))

(defn- millis
  "Fin (haut) ou début (bas) de la milliseconde si les millisecondes ne sont pas lues (zéro),
  sinon la valeur lue tronquée à la milliseconde."
  [high? nano]
  (cond (pos? (rem nano 1000000)) (* 1000000 (quot nano 1000000))
        (and high? (zero? nano)) 999000000
        :else nano))

(defn- partial-date-time
  "Début (bas) ou fin (haut) de la période d'une date-heure partielle (année, mois, jour)."
  [high? x]
  (let [[year month day] (mapv #(Integer/parseInt ^String %) (str/split (str x) #"-"))
        month (or month (if high? 12 1))
        day (or day (if high? (.lengthOfMonth (YearMonth/of (int year) (int month))) 1))]
    (LocalDateTime/of (int year) (int month) (int day)
                      (if high? 23 0) (if high? 59 0) (if high? 59 0) (if high? 999000000 0))))

(defn- date-time-boundary
  "Date-heure partielle ou sans fuseau : début à +14:00, fin à -12:00 (fuseaux extrêmes) ;
  complète : son fuseau, à la milliseconde."
  [high? x]
  (let [offset? (instance? OffsetDateTime x)
        full? (or offset? (instance? LocalDateTime x))
        ^LocalDateTime local (cond offset? (.toLocalDateTime ^OffsetDateTime x)
                                   full? x
                                   :else (partial-date-time high? x))
        local (cond-> local full? (.withNano (int (millis high? (.getNano local)))))
        zone (if offset? (.getOffset ^OffsetDateTime x) (ZoneOffset/ofHours (if high? -12 14)))]
    (.format date-time-format (OffsetDateTime/of local zone))))

(defn- boundary [high? precision x]
  (let [x (primitive-value x)]
    (cond
      (and (some? precision) (or (system/decimal? x) (system/date-time? x) (system/time? x)))
      (throw-anom (ba/unsupported "boundary function with a precision is only supported on a date"))
      (system/date? x) (date-boundary high? (or precision 8) x)
      (system/decimal? x) (decimal-boundary high? x)
      (system/date-time? x) (date-time-boundary high? x)
      (system/time? x) (.format time-format (.withNano ^LocalTime x (int (millis high? (.getNano ^LocalTime x)))))
      :else (throw-anom (ba/incorrect "non-date item in boundary function")))))

(deftype BoundaryFunctionExpression [high? precision]
  fp/Expression
  (-eval [_ context coll]
    (let [p (singleton-param precision context coll int?
                             "non-integer precision in boundary function")]
      (mapv #(boundary high? p %) (items coll)))))

(defmethod fp/function-expression "lowBoundary"
  [_ params-ctx]
  (->BoundaryFunctionExpression false (param params-ctx 0)))

(defmethod fp/function-expression "highBoundary"
  [_ params-ctx]
  (->BoundaryFunctionExpression true (param params-ctx 0)))

;; Fonctions de texte du socle de F-017 (FHIRPath N1 §5.6) : startsWith, endsWith, contains,
;; upper, lower, length, substring, indexOf, replace. Entrée vide ou paramètre vide : vide. Plusieurs
;; éléments, en entrée ou en paramètre : `incorrect` nommant la fonction et la cardinalité (le
;; refus remplace l'évaluation en singleton). Longueurs et positions en points de code Unicode.
;; Hors socle (`matches`, `replaceMatches`, `trim`, `toChars`) : `unknown function`, inchangé.

(defn- singleton
  "L'unique valeur système de `coll` (déjà réduite en vecteur), nil si vide ; `incorrect` nommant
  `fname` et la cardinalité si plusieurs, ou si la valeur ne satisfait pas `pred`."
  [fname what coll pred]
  (let [n (count coll)]
    (when (< 1 n)
      (throw-anom (ba/incorrect (format "`%s` function requires a singleton %s, found cardinality %d"
                                        fname what n))))
    (when-some [x (some-> (first coll) primitive-value)]
      (if (pred x)
        x
        (throw-anom (ba/incorrect (format "non-%s %s in `%s` function"
                                          (if (= string? pred) "string" "integer") what fname)))))))

(defn- code-points ^long [^String s]
  (.codePointCount s 0 (.length s)))

(defn- offset ^long [^String s ^long code-point-index]
  (.offsetByCodePoints s 0 (int code-point-index)))

(defn- substring [^String s start length]
  (let [n (code-points s)]
    (when (and (<= 0 start n) (or (nil? length) (<= 0 length)))
      (let [from (offset s start)
            to (if length (offset s (min n (+ start length))) (.length s))]
        (.substring s (int from) (int to))))))

(defn- index-of [^String s ^String sub]
  (let [i (.indexOf s sub)]
    (if (neg? i) -1 (.codePointCount s 0 i))))

(def ^:private text-functions
  "Nom -> {:params [prédicat…], :f (fn [entrée & paramètres]) → valeur ou nil (vide)}."
  {"startsWith" {:params [string?] :f (fn [^String s ^String p] (.startsWith s p))}
   "endsWith" {:params [string?] :f (fn [^String s ^String p] (.endsWith s p))}
   "contains" {:params [string?] :f (fn [^String s ^String p] (.contains s p))}
   "upper" {:params [] :f (fn [^String s] (.toUpperCase s Locale/ROOT))}
   "lower" {:params [] :f (fn [^String s] (.toLowerCase s Locale/ROOT))}
   "length" {:params [] :f (fn [s] (int (code-points s)))}
   "substring" {:params [int? int?] :optional 1
                :f (fn [s start & [length]] (substring s start length))}
   "indexOf" {:params [string?] :f (fn [s p] (int (index-of s p)))}
   "replace" {:params [string? string?]
              :f (fn [^String s ^String pattern ^String substitution]
                   (.replace s pattern substitution))}})

(deftype TextFunctionExpression [fname f param-preds params]
  fp/Expression
  (-eval [_ context coll]
    (if-some [s (singleton fname "input" (items coll) string?)]
      (let [values (mapv (fn [expr pred]
                           (singleton fname "parameter" (items (fp/-eval expr context coll)) pred))
                         params param-preds)]
        ;; un paramètre obligatoire vide rend vide ; un facultatif absent n'est pas passé
        (if (some nil? values)
          []
          (if-some [r (apply f s values)] [r] [])))
      [])))

(doseq [[fname {:keys [params f optional] :or {optional 0}}] text-functions]
  (defmethod fp/function-expression fname
    [_ ^fhirpathParser$ParamListContext params-ctx]
    (let [n (count (some-> params-ctx .expression))
          required (- (count params) optional)]
      (when-not (<= required n (count params))
        (throw-anom (ba/incorrect (format "`%s` function expects %d parameter(s), found %d"
                                          fname (count params) n))))
      (->TextFunctionExpression fname f (subvec params 0 n)
                                (mapv #(param params-ctx %) (range n))))))
