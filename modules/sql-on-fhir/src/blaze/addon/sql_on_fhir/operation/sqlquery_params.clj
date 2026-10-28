(ns blaze.addon.sql-on-fhir.operation.sqlquery-params
  "SQ-03 : lecture des entrées de `$sqlquery-run` (IG 2.1.0-pre, `OperationDefinition-SQLQueryRun`) dans
  le `Parameters` JSON brut (clés en mots-clés) : fonction pure, qui rend une map ou l'anomalie 400."
  (:require
   [blaze.anomaly :as ba]))

(def ^:private formats #{"json" "ndjson" "csv"})

(def ^:private inputs
  #{"_format" "header" "queryReference" "queryResource" "parameters" "source" "_limit"})

(defn- refused [msg] (ba/incorrect msg :http/status 400))

(defn read-params
  "Entrées lues de `body` (`_format` de la requête `query-format`, puis celui du corps, puis
  `accept-format`, F-018) : `{:format :header :parameters}` et `:library` (`queryResource`) ou
  `:reference` (`queryReference`), `:limit` s'il est donné ; ou l'anomalie 400 : corps qui n'est pas un
  `Parameters`, entrée inconnue ou répétée, `source`, ni `queryResource` ni `queryReference` (ou les
  deux), format hors `json`, `ndjson`, `csv`, `_limit` qui n'est pas un entier positif."
  [body query-format accept-format]
  (if-not (= "Parameters" (:resourceType body))
    (refused "The request body must be a Parameters resource.")
    (let [by-name (group-by :name (:parameter body))
          one (fn [name] (first (get by-name name)))
          fmt (or query-format (:valueCode (one "_format")) (:valueString (one "_format")) accept-format "ndjson")
          library (:resource (one "queryResource"))
          reference-param (one "queryReference")
          reference (when reference-param
                      (or (get-in reference-param [:valueReference :reference]) (:valueString reference-param)))
          limit (one "_limit")]
      (cond
        (some (complement inputs) (keys by-name)) (refused "The request has an input that this operation does not know.")
        (some next (vals by-name)) (refused "An input is given more than once.")
        (one "source") (ba/unsupported "`source` is not supported." :http/status 400)
        (not (formats fmt)) (ba/unsupported (format "Format `%s` is not supported, use json, ndjson or csv." fmt) :http/status 400)
        (and library reference-param) (refused "Only one of `queryResource` and `queryReference` is allowed.")
        (not (or library reference-param)) (ba/incorrect "Missing `queryResource` or `queryReference`." :fhir/issue "required" :http/status 400)
        (and reference-param (not (string? reference))) (refused "`queryReference` must be a reference.")
        (and limit (not (pos-int? (:valueInteger limit)))) (refused "`_limit` must be a positive integer.")
        :else (cond-> {:format fmt
                       :header (not (false? (:valueBoolean (one "header"))))
                       :parameters (:resource (one "parameters"))}
                library (assoc :library library)
                reference (assoc :reference reference)
                limit (assoc :limit (:valueInteger limit)))))))
