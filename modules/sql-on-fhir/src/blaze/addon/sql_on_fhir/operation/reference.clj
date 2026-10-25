(ns blaze.addon.sql-on-fhir.operation.reference
  "F-011 (ADR 0009 C1) : `viewReference` est la canonique `url` ou `url|version` d'une `Library`
  rangée (son `content` JSON porte la vue). Elle est cherchée par `blaze.db.api/type-query` dans la
  base `db` de la requête, jamais téléchargée. Refus (400) : forme sans `scheme:` `not-supported`,
  aucune `Library` `not-found`, plusieurs `multiple-matches`."
  (:require
   [blaze.anomaly :as ba :refer [if-ok]]
   [blaze.db.api :as d]
   [clojure.string :as str]
   [jsonista.core :as j])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private scheme #"[A-Za-z][A-Za-z0-9+.-]*:.*")

(defn- find-libraries
  "Poignées des `Library` de `url` (et de `version` si donnée) dans `db`, au plus deux."
  [db url version]
  (ba/try-anomaly
   (into [] (take 2)
         (ba/throw-when
          @(d/type-query db "Library" (cond-> [["url" url]] version (conj ["version" version])))))))

(defn- unusable [msg] (ba/incorrect msg :http/status 422))

(defn- view-of
  "Vue JSON de la pièce jointe `application/json` de `library`, ou une anomalie 422."
  [library]
  (if-let [data (some #(when (= "application/json" (some-> % :contentType :value))
                         (some-> % :data :value))
                      (:content library))]
    (let [view (ba/try-anomaly
                (j/read-value (.decode (Base64/getDecoder) ^String data) j/keyword-keys-object-mapper))]
      (if (and (map? view) (not (ba/anomaly? view)))
        view
        (unusable "The `content` of the referenced Library is not a JSON ViewDefinition.")))
    (unusable "The referenced Library has no `application/json` content.")))

(defn resolve-view
  "Vue (map JSON) de la `Library` que désigne `reference` dans `db`, ou l'anomalie du refus."
  [db reference]
  (if-not (and (string? reference) (re-matches scheme reference))
    (ba/unsupported
     "Only a canonical `viewReference` (`url` or `url|version`) is supported."
     :http/status 400)
    (let [[url version] (str/split reference #"\|" 2)]
      (if-ok [handles (find-libraries db url (not-empty version))]
        (case (count handles)
          0 (ba/not-found (format "No Library with the canonical `%s` on this server." reference)
                          :http/status 400)
          1 (if-ok [library @(d/pull db (first handles))]
              (view-of library)
              identity)
          (ba/incorrect (format "Several Libraries match `%s`, add `|version`." reference)
                        :fhir/issue "multiple-matches" :http/status 400))
        identity))))

(defn resolve-entry
  "`entry` dont le `:view-reference`, s'il y en a un, est remplacé par `:view` (la vue résolue
  dans `db`) ; l'anomalie du refus sinon. Sans référence, `entry` est rendue telle quelle."
  [db {:keys [view-reference] :as entry}]
  (if (contains? entry :view-reference)
    (if-ok [view (resolve-view db view-reference)]
      (-> entry (dissoc :view-reference) (assoc :view view))
      identity)
    entry))
