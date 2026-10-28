(ns blaze.addon.sql-on-fhir.operation.sortie
  "IB-12 : voie de sortie brute de `$viewdefinition-run` et de `$sqlquery-run` (T-116, IB-16). Le
  composant `app` enrobe `:blaze.handler/app`. Pour ces deux routes seules, à l'entrée il range `_format` dans la requête
  lit puis fixe `Accept` (le format qu'il porte, ::accept-format, passe après `_format`), à la sortie il remet les lignes (clé `::raw`) en `:body`. Cela suppose deux
  comportements de Blaze que garde `sortie-amont-test` : clé inconnue conservée, `:body` nil
  non encodé. T-048 (IB-13) : le même composant sert les routes de l'export (`:export-routes`,
  facultatif : présent si `$viewdefinition-export` est allumée) avant de passer à l'application ;
  un future de `nil` (« pas pour moi », ex. `/__async-status/` d'une autre tâche) passe à l'application."
  (:require
   [blaze.async.comp :as ac]
   [clojure.string :as str]
   [integrant.core :as ig]
   [ring.util.codec :as codec]
   [taoensso.timbre :as log]))

(defn- run-request?
  "Vrai pour `POST …/$viewdefinition-run` et `POST …/$sqlquery-run` (T-116) : les deux rendent des lignes brutes."
  [{:keys [request-method uri]}]
  (and (= :post request-method)
       (some? uri)
       (or (str/ends-with? uri "/$viewdefinition-run") (str/ends-with? uri "/$sqlquery-run"))))

(defn- format-param [query-string]
  (some->> query-string (re-find #"(?:^|&)_format=([^&]*)") second codec/form-decode-str))

(defn- strip-format [query-string]
  (when query-string
    (->> (str/split query-string #"&")
         (remove #(str/starts-with? % "_format="))
         (str/join "&"))))

(def ^:private accept-formats
  {"application/json" "json" "application/x-ndjson" "ndjson" "text/csv" "csv"})

(def ^:private unservable
  "Une virgule (liste) ou un poids nul."
  #"(?i),|;\s*q\s*=\s*0(?:\.0*)?\s*(?:;|$)")

(defn- accept-format
  "F-018, table M : le format d'un `Accept` à type unique de la table, quels que soient ses
  paramètres, sauf un poids nul (`q=0`, `q=0.0`, `q=0.000` : « not acceptable », RFC 9110
  §12.4.2) ; nil pour ce poids nul, pour un `Accept` qui contient une virgule (liste de plusieurs
  types, pondérée ou non, jamais servie) ou pour un type hors table : le défaut s'applique. Tout
  `q` > 0 d'un type unique est servi."
  [accept]
  (when-not (some->> accept (re-find unservable))
    (some-> accept (str/split #";" 2) first str/trim str/lower-case accept-formats)))

(defn- entree [request]
  (-> request
      (assoc ::format (format-param (:query-string request))
             ::accept-format (accept-format (get-in request [:headers "accept"])))
      (update :query-string strip-format)
      (assoc-in [:headers "accept"] "application/fhir+json")))

(defn- sortie [{::keys [raw content-type] :as response}]
  (cond-> response
    raw (-> (dissoc ::raw ::content-type)
            (assoc :body raw)
            (assoc-in [:headers "Content-Type"] content-type))))

(defmethod ig/init-key :blaze.addon.sql-on-fhir.operation.sortie/app [_ {:keys [app export-routes]}]
  (log/info "Init $viewdefinition-run raw output wrapper" (if export-routes "with export routes" ""))
  (fn [request respond raise]
    (if (run-request? request)
      (app (entree request) (comp respond sortie) raise)
      (if-some [response (when export-routes (export-routes request))]
        (ac/handle response (fn [r e] (cond e (raise e) r (respond r) :else (app request respond raise))))
        (app request respond raise)))))
