(ns blaze.addon.sql-on-fhir.fhir-path.unsupported
  "Constructions de la grammaire FHIRPath qu'aucune implémentation ne couvre (FP-14) : anomalie
  `unsupported` nommant la construction, à la place du `fault` du jar. `extend` sur des classes
  **absentes** de `(extenders FPCompiler)` (IB-02) ; ce namespace se charge après `blaze.fhir-path`."
  (:require
   [blaze.anomaly :as ba :refer [throw-anom]]
   [blaze.fhir-path :as fp]
   [clojure.string :as str])
  (:import
   [org.cqframework.cql.gen
    fhirpathParser$ImpliesExpressionContext
    fhirpathParser$IndexInvocationContext
    fhirpathParser$MembershipExpressionContext
    fhirpathParser$PolarityExpressionContext
    fhirpathParser$QuantityLiteralContext
    fhirpathParser$TimeLiteralContext
    fhirpathParser$TotalInvocationContext]))

(set! *warn-on-reflection* true)

(defn- unsupported [ctx]
  (throw-anom
   (ba/unsupported
    (format "unsupported FHIRPath construction `%s`"
            (str/replace (.getSimpleName (class ctx)) #"Context$" "")))))

(doseq [cls [fhirpathParser$ImpliesExpressionContext
             fhirpathParser$IndexInvocationContext
             fhirpathParser$MembershipExpressionContext
             fhirpathParser$PolarityExpressionContext
             fhirpathParser$QuantityLiteralContext
             fhirpathParser$TimeLiteralContext
             fhirpathParser$TotalInvocationContext]]
  (extend cls fp/FPCompiler {:-compile unsupported}))
