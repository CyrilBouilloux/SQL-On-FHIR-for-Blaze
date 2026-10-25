(ns blaze.addon.sql-on-fhir.operation.sortie-amont-test
  "IB-12, « Voie de sortie gardée » (F-006) : les deux comportements du middleware de sortie
  de Blaze (jar de `BLAZE_VERSION`) dont dépend la sortie brute de `$viewdefinition-run`.
  Panne et remède : `docs/impacts-blaze.md`, fiche IB-12."
  (:require
   [blaze.fhir.structure-definition-repo]
   [blaze.fhir.writing-context]
   [blaze.middleware.fhir.output :refer [wrap-output]]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is]]
   [integrant.core :as ig]
   [ring.util.response :as ring]))

(st/instrument)

(test/use-fixtures :each tu/fixture)

(def ^:private writing-context
  (delay (ig/init-key :blaze.fhir/writing-context
                      {:structure-definition-repo
                       (ig/init-key :blaze.fhir/structure-definition-repo {})})))

(defn- sortie
  "Réponse que rend le middleware de sortie du jar pour une réponse à `:body` nil portant
  la clé inconnue `::inconnue`."
  [reponse]
  (let [p (promise)]
    ((wrap-output (fn [_ respond _] (respond reponse)) @writing-context)
     {:headers {"accept" "application/fhir+json"}} (partial deliver p) #(deliver p %))
    (deref p 10000 ::sans-reponse)))

(def ^:private reponse-brute
  (assoc (ring/response nil) ::inconnue "a,b\r\n"))

(deftest cle-inconnue-conservee
  (is (= "a,b\r\n" (::inconnue (sortie reponse-brute)))
      "le middleware de sortie de Blaze ne conserve plus une clé inconnue de la réponse"))

(deftest corps-nil-non-encode
  (let [r (sortie reponse-brute)]
    (is (and (nil? (:body r)) (nil? (get-in r [:headers "Content-Type"])))
        (str "le middleware de sortie de Blaze encode un :body nil : " (pr-str (dissoc r ::inconnue))))))
