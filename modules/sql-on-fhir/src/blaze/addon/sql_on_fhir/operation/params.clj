(ns blaze.addon.sql-on-fhir.operation.params
  "OP-01, OP-02 : lecture des paramètres de `$viewdefinition-run` dans le `Parameters` JSON brut
  (clés en mots-clés) : fonction pure, qui rend une map ou l'anomalie du premier refus."
  (:require
   [blaze.anomaly :as ba :refer [if-ok]]
   [cognitect.anomalies :as anom])
  (:import
   [java.time DateTimeException OffsetDateTime]))

(set! *warn-on-reflection* true)

(def ^:private formats #{"json" "ndjson" "csv"})

(def ^:private unsupported-params
  {"source" "`source` is not supported."
   "resource" "`resource` is not supported."})

(def ^:private fhir-instant
  #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")

(defn unprocessable
  "Une vue refusée par le moteur est une requête valide qu'il ne peut traiter : 422 (IG) ;
  un patient ou un groupe absent est une requête incorrecte : 400."
  [{::anom/keys [category] :as anomaly}]
  (case category
    (::anom/incorrect ::anom/unsupported) (assoc anomaly :http/status 422)
    ::anom/not-found (assoc anomaly :http/status 400)
    anomaly))

(defn- refused [msg] (ba/unsupported msg :http/status 400))

(defn- parse-since [s]
  (if (and (string? s) (re-matches fhir-instant s))
    (try
      (str (.toInstant (OffsetDateTime/parse ^CharSequence s)))
      (catch DateTimeException _ (ba/incorrect (format "`_since` `%s` is not a valid instant." s))))
    (ba/incorrect (format "`_since` `%s` is not a valid FHIR instant." s))))

(defn- parse-id
  "Identifiant de la référence (`Type/id`) ou de l'identifiant nu `v` ; `type` est le type attendu."
  [name type v]
  (let [[_ id] (when (string? v) (re-matches (re-pattern (str "(?:" type "/)?([A-Za-z0-9\\-.]{1,64})")) v))]
    (or id (ba/incorrect (format "`%s` must be a `%s` reference or id." name type)))))

(defn- value [{:keys [valueReference valueString valueId valueCode valueInstant]}]
  (or (:reference valueReference) valueString valueId valueCode valueInstant))

(defn- read-view
  "Vue des paramètres `by-name` : `{:view}` (`viewResource`) ou `{:view-reference}` (la canonique,
  à résoudre dans la base de la requête, `operation.reference`) ; l'anomalie `incorrect` si les
  deux, aucune, ou une `viewReference` sans valeur."
  [by-name]
  (let [view (:resource (by-name "viewResource"))
        reference (by-name "viewReference")
        canonical (some-> reference value)]
    (cond
      (and view reference) (ba/incorrect "Only one of `viewResource` and `viewReference` is allowed.")
      view {:view view}
      reference (if (string? canonical)
                  {:view-reference canonical}
                  (ba/incorrect "`viewReference` must be a reference." :fhir/issue "required"))
      :else (ba/incorrect "Missing `viewResource` or `viewReference`." :fhir/issue "required"))))

(defn- parse-limit [n]
  (if (pos-int? n) n (ba/incorrect "`_limit` must be a positive integer.")))

(defn- read-filters
  "Filtres `patient`, `group`, `_since` des paramètres `by-name` (map nom → paramètre) :
  `{:patient :group :since}` ou l'anomalie du premier refus."
  [by-name]
  (let [param (fn [name] (some-> (by-name name) value))
        patient (some->> (param "patient") (parse-id "patient" "Patient"))
        group (some->> (param "group") (parse-id "group" "Group"))
        since (some-> (param "_since") parse-since)]
    (or (some #(when (ba/anomaly? %) %) [patient group since])
        {:patient patient :group group :since since})))

(defn read-params
  "Paramètres lus de `body` (`Parameters`) et du `_format` de la requête `query-format` (qui
  l'emporte sur celui du corps, lui-même devant le format `accept-format` tiré d'`Accept`, F-018) :
  `{:view :format :header :patient :group :since :limit}`, avec
  `:view-reference` (canonique) à la place de `:view` pour une `viewReference`, ou une anomalie :
  `incorrect` (400), `unsupported` (400 : `source`, `resource`, format hors `json`, `ndjson`,
  `csv`)."
  ([body query-format] (read-params body query-format nil))
  ([body query-format accept-format]
   (if-not (= "Parameters" (:resourceType body))
     (ba/incorrect "The request body must be a Parameters resource.")
     (let [by-name (into {} (map (juxt :name identity)) (:parameter body))
           fmt (or query-format (value (by-name "_format")) accept-format "ndjson")]
       (cond
         (some by-name (keys unsupported-params))
         (refused (some (fn [[k msg]] (when (by-name k) msg)) unsupported-params))

         (not (formats fmt))
         (refused (format "Format `%s` is not supported, use json, ndjson or csv." fmt))

         :else
         (if-ok [view (read-view by-name)
                 filters (read-filters by-name)
                 limit (some-> (:valueInteger (by-name "_limit")) parse-limit)]
           (assoc (merge filters view)
                  :format fmt
                  :header (not (false? (:valueBoolean (by-name "header"))))
                  :limit limit)
           identity))))))

(defn- read-export-view
  "Vue de rang `i` (de 0) d'un paramètre `view` : `{:rank :view | :view-reference}` avec `:name`
  s'il est donné, ou l'anomalie de son refus. Le nom par défaut vient de `name-views`."
  [i {:keys [part]}]
  (let [by-name (into {} (map (juxt :name identity)) part)]
    (if-ok [source (read-view by-name)]
      (cond-> (assoc source :rank (inc i))
        (:valueString (by-name "name")) (assoc :name (:valueString (by-name "name"))))
      identity)))

(defn name-views
  "`views` (de `read-export-params`, `:view-reference` résolues en `:view`) nommées : `view.name`,
  sinon le `name` de la vue, sinon `view-<rang>` (IG). Ou l'anomalie `incorrect` si des noms
  se répètent."
  [views]
  (let [named (mapv (fn [{:keys [name view rank] :as entry}]
                      (-> entry
                          (assoc :name (or name (when (string? (:name view)) (:name view)) (str "view-" rank)))
                          (dissoc :rank)))
                    views)]
    (if (apply distinct? (map :name named))
      named
      (ba/incorrect "The names of the views must be distinct."))))

(defn- read-export-views [parameters]
  (let [views (into [] (comp (filter #(= "view" (:name %))) (map-indexed read-export-view)) parameters)]
    (cond
      (empty? views) (ba/incorrect "At least one `view` is required." :fhir/issue "required")
      (some ba/anomaly? views) (some #(when (ba/anomaly? %) %) views)
      :else views)))

(defn read-export-params
  "AS-01 : paramètres de `$viewdefinition-export` lus dans le `Parameters` JSON brut `body` :
  `{:views [{:rank :name? :view | :view-reference}] :format :header :client-tracking-id :patient :group :since}`, ou
  l'anomalie du premier refus : `incorrect` (400 ; `required` : aucun `view`, ou un `view` sans
  `viewResource` ni `viewReference`), `unsupported` (400 : `source`, plusieurs `patient` ou `group`,
  `_format` hors `json`, `ndjson`, `csv`)."
  [body]
  (if-not (= "Parameters" (:resourceType body))
    (ba/incorrect "The request body must be a Parameters resource.")
    (let [by-name (into {} (map (juxt :name identity)) (:parameter body))
          fmt (or (some-> (by-name "_format") value) "ndjson")]
      (cond
        (by-name "source") (refused (unsupported-params "source"))

        (some #(< 1 (count (filter (fn [p] (= % (:name p))) (:parameter body)))) ["patient" "group"])
        (refused "Only one `patient` and one `group` are supported.")

        :else
        (if-ok [views (read-export-views (:parameter body))]
          (if (formats fmt)
            (if-ok [filters (read-filters by-name)]
              (assoc filters
                     :views views
                     :format fmt
                     :header (not (false? (:valueBoolean (by-name "header"))))
                     :client-tracking-id (:valueString (by-name "clientTrackingId")))
              identity)
            (refused (format "Format `%s` is not supported, use json, ndjson or csv." fmt)))
          identity)))))
