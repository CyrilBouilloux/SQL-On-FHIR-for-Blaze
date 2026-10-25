(ns blaze.addon.sql-on-fhir.fhir-path.constant
  "Constantes de vue (FP-12) : `ViewDefinition.constant.value[x]` traduit en collection FHIRPath,
  versée dans `:constants` du contexte de `-eval` (IB-05, lu par `variable.clj`). Les 18 types
  de l'IG 2.1.0-pre hors `integer64` (absent de R4, ADR 0005) ; la correspondance FHIR -> FHIRPath
  est celle de FHIR R4 (« Using FHIR types in expressions »), `date` rendu en `System.Date`."
  (:require
   [blaze.anomaly :as ba]
   [blaze.fhir.spec.type.system :as system]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]))

(set! *warn-on-reflection* true)

(defn- int-value? [x]
  (and (integer? x) (<= Integer/MIN_VALUE x Integer/MAX_VALUE)))

(def ^:private converters
  "Clé `value[x]` -> [prédicat de la valeur JSON, conversion en valeur FHIRPath]."
  (let [text [string? identity]
        integer [int-value? int]]
    {:valueString text :valueUri text :valueCode text :valueOid text :valueId text
     :valueUuid text :valueBase64Binary text :valueCanonical text :valueUrl text
     :valueBoolean [boolean? identity]
     :valueInteger integer :valuePositiveInt integer :valueUnsignedInt integer
     :valueDecimal [number? bigdec]
     :valueDate [string? system/parse-date]
     :valueDateTime [string? system/parse-date-time]
     :valueInstant [string? system/parse-date-time]
     :valueTime [string? system/parse-time]}))

(defn value-collection
  "Collection FHIRPath d'un élément, de clé `value[x]` `k` et de valeur JSON `value`, ou une
  anomalie : `unsupported` pour une clé hors des 18 types, `incorrect` pour une valeur illisible."
  [k value]
  (if-some [[pred convert] (get converters k)]
    (if (pred value)
      (let [x (convert value)]
        (if (ba/anomaly? x) (update x ::anom/message #(str "invalid " (name k) ": " %)) [x]))
      (ba/incorrect (format "invalid %s: %s" (name k) (pr-str value))))
    (ba/unsupported (format "unsupported constant type `%s`" (name k)))))

(defn- value-key [constant]
  (let [ks (filter #(str/starts-with? (name %) "value") (keys constant))]
    (when (= 1 (count ks)) (first ks))))

(defn- constant-collection [{:keys [name] :as constant}]
  (if-some [k (value-key constant)]
    (let [coll (value-collection k (get constant k))]
      (cond-> coll
        (ba/anomaly? coll)
        (update ::anom/message #(str % " in constant `" name "`"))))
    (ba/incorrect (format "constant `%s` needs exactly one value[x]" name))))

(defn constants
  "Valeur de `:constants` du contexte de `-eval` pour les constantes de vue `constants`
  (`{:name n :value<Type> v}`) : map nom -> collection FHIRPath, ou la première anomalie."
  [constants]
  (reduce (fn [m constant]
            (let [coll (constant-collection constant)]
              (if (ba/anomaly? coll)
                (reduced coll)
                (assoc m (:name constant) coll))))
          {}
          constants))
