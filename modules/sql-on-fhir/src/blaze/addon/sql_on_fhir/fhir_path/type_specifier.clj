(ns blaze.addon.sql-on-fhir.fhir-path.type-specifier
  "Type qualifié en argument d'`ofType` : `FHIR.Quantity`. Blaze n'implémente
  `-compile-as-type-specifier` que pour un identifiant nu ; la forme qualifiée est une
  `InvocationExpressionContext`, dont seul `-compile` existe (IB-01). Le protocole est étendu
  **par fusion** : `extend` remplace la map entière de la classe, `-compile` doit rester celui
  du jar. Ce namespace se charge après `blaze.fhir-path`. `System.String` est hors périmètre."
  (:require
   [blaze.anomaly :as ba :refer [throw-anom]]
   [blaze.fhir-path :as fp])
  (:import
   [org.cqframework.cql.gen
    fhirpathParser$InvocationExpressionContext
    fhirpathParser$MemberInvocationContext]))

(set! *warn-on-reflection* true)

(defn- qualified-type-specifier [^fhirpathParser$InvocationExpressionContext ctx]
  (let [namespace (.getText (.expression ctx))
        invocation (.invocation ctx)]
    (cond
      (not= "FHIR" namespace)
      (throw-anom (ba/incorrect (format "unsupported type namespace `%s` in `%s`" namespace (.getText ctx))))

      (not (instance? fhirpathParser$MemberInvocationContext invocation))
      (throw-anom (ba/incorrect (format "invalid type specifier `%s`" (.getText ctx))))

      :else
      (fp/-compile-as-type-specifier invocation))))

(extend fhirpathParser$InvocationExpressionContext
  fp/FPCompiler
  (assoc (get-in fp/FPCompiler [:impls fhirpathParser$InvocationExpressionContext])
         :-compile-as-type-specifier qualified-type-specifier))
