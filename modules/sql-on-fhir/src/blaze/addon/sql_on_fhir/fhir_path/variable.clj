(ns blaze.addon.sql-on-fhir.fhir-path.variable
  "Variables FHIRPath : `$this` (FP-10), `%nom` (FP-11) et `%rowIndex` (FP-13), sur des classes sans
  implémentation de `FPCompiler` (`extend-protocol`, IB-02). Les valeurs viennent du contexte de
  `-eval` (IB-05, forme interne à Blaze, non garantie) : `{:resolver … :constants {nom collection}
  ::row-index n}`. `eval` n'est pas touché : l'appelant invoque `-eval`."
  (:require
   [blaze.anomaly :as ba :refer [throw-anom]]
   [blaze.fhir-path :as fp])
  (:import
   [org.antlr.v4.runtime.tree TerminalNode]
   [org.cqframework.cql.gen
    fhirpathParser$ExternalConstantTermContext
    fhirpathParser$ThisInvocationContext]))

(set! *warn-on-reflection* true)

;; 3.1. $this : la collection d'entrée, comme la définit `where` (une collection d'un élément).

(deftype ThisExpression []
  fp/Expression
  (-eval [_ _ coll]
    coll))

;; 9. %nom : valeur fournie par le contexte, sinon anomalie `incorrect` nommant la constante.

(deftype ConstantExpression [name]
  fp/Expression
  (-eval [_ context _]
    (if-some [value (get-in context [:constants name])]
      value
      (throw-anom (ba/incorrect (format "undefined constant `%%%s`" name))))))

;; %rowIndex : réservé, 0 hors itération.

(deftype RowIndexExpression []
  fp/Expression
  (-eval [_ context _]
    [(int (get context ::row-index 0))]))

(defn- constant-name [ctx]
  (let [constant (.externalConstant ^fhirpathParser$ExternalConstantTermContext ctx)]
    (if-some [string (.STRING constant)]
      (let [text (.getText (.getSymbol ^TerminalNode string))]
        (subs text 1 (dec (count text))))
      (fp/-compile (.identifier constant)))))

(extend-protocol fp/FPCompiler
  fhirpathParser$ThisInvocationContext
  (-compile [_]
    (->ThisExpression))

  fhirpathParser$ExternalConstantTermContext
  (-compile [ctx]
    (let [name (constant-name ctx)]
      (if (= "rowIndex" name)
        (->RowIndexExpression)
        (->ConstantExpression name)))))
