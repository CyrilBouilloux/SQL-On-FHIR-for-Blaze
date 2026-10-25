(ns blaze.addon.sql-on-fhir.operation.reference-test
  "F-011 (T-056, ADR 0009 C1) : résolution de `viewReference` par canonique vers une `Library`
  rangée, dans la base de la requête (nœud en mémoire du jar)."
  (:require
   [blaze.addon.sql-on-fhir.noeud :as noeud]
   [blaze.addon.sql-on-fhir.operation.reference :as reference]
   [blaze.addon.sql-on-fhir.operation.reference-spec]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)
(st/instrument)

(test/use-fixtures :once su/registre-types)
(test/use-fixtures :each tu/fixture)

(def ^:private contexte (delay (fichier/contexte)))

(defn- lire [m] (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(def ^:private url "http://example.org/fhir/ViewDefinition/pat")

(defn- vue [version]
  {:resourceType "ViewDefinition" :status "active" :resource "Patient" :url url :version version
   :select [{:column [{:name "id" :path "id"}]}]})

(defn- base64 [m]
  (.encodeToString (Base64/getEncoder) (.getBytes (j/write-value-as-string m) "UTF-8")))

(defn- bibliotheque
  "Opération d'écriture de la `Library` `id` qui range `vue` ; `content` remplaçable."
  [id url version contenu]
  [:put (lire {:resourceType "Library" :id id :url url :version version :status "active"
               :type {:coding [{:system "http://terminology.hl7.org/CodeSystem/library-type" :code "asset-collection"}]}
               :content contenu})])

(defn- rangee [id version]
  (bibliotheque id url version [{:contentType "application/json" :data (base64 (vue version))}]))

(defmacro ^:private avec-base
  {:clj-kondo/lint-as 'clojure.core/let}
  [[db txs] & body]
  `(let [systeme# (noeud/demarrer)
         node# (:blaze.db/node systeme#)]
     (try
       (run! #(noeud/ecrire! node# %) ~txs)
       (let [~db (d/db node#)] ~@body)
       (finally (ig/halt! systeme#)))))

(deftest forme-sans-schema
  (avec-base [db [[(rangee "l1" "1.0.0")]]]
             (testing "sans `scheme:` : 400 not-supported, même si la Library existe"
               (doseq [r ["ViewDefinition/l1" "Library/l1" "l1" "" "pat|1.0.0"]]
                 (given (reference/resolve-view db r)
                   ::anom/category := ::anom/unsupported
                   :http/status := 400)))))

(deftest canonique
  (avec-base [db [[(rangee "l1" "1.0.0") (rangee "l2" "2.0.0")]]]
             (testing "url|version : la vue de cette version"
               (given (reference/resolve-view db (str url "|2.0.0"))
                 :resourceType := "ViewDefinition"
                 :version := "2.0.0"))
             (testing "url seule, une seule Library de cette url"
               (avec-base [db [[(rangee "l1" "1.0.0")]]]
                          (given (reference/resolve-view db url)
                            :version := "1.0.0")))
             (testing "version absente : 400 not-found"
               (given (reference/resolve-view db (str url "|9.9.9"))
                 ::anom/category := ::anom/not-found
                 :http/status := 400))
             (testing "plusieurs correspondances sans version : 400 multiple-matches"
               (given (reference/resolve-view db url)
                 ::anom/category := ::anom/incorrect
                 :fhir/issue := "multiple-matches"
                 :http/status := 400))))

(deftest introuvable
  (avec-base [db [[(rangee "l1" "1.0.0")]]]
             (testing "url sans Library"
               (given (reference/resolve-view db "http://example.org/fhir/ViewDefinition/autre")
                 ::anom/category := ::anom/not-found
                 :http/status := 400))
             (testing "Library supprimée"
               (avec-base [db [[(rangee "l1" "1.0.0")] [[:delete "Library" "l1"]]]]
                          (given (reference/resolve-view db url)
                            ::anom/category := ::anom/not-found)))))

(deftest base-de-la-requete
  (testing "la base lue est celle de la requête : une Library écrite après n'y est pas"
    (let [systeme (noeud/demarrer)
          node (:blaze.db/node systeme)]
      (try
        (noeud/ecrire! node [(rangee "l1" "1.0.0")])
        (let [avant (d/db node)]
          (noeud/ecrire! node [(rangee "l2" "2.0.0")])
          (given (reference/resolve-view avant (str url "|2.0.0"))
            ::anom/category := ::anom/not-found)
          (given (reference/resolve-view (d/db node) (str url "|2.0.0"))
            :version := "2.0.0"))
        (finally (ig/halt! systeme))))))

(deftest contenu-inutilisable
  (testing "sans pièce jointe, JSON invalide, pas une vue : 422"
    (doseq [contenu [[]
                     [{:contentType "application/json" :data (.encodeToString (Base64/getEncoder) (.getBytes "{" "UTF-8"))}]
                     [{:contentType "application/json" :data (.encodeToString (Base64/getEncoder) (.getBytes "[1]" "UTF-8"))}]
                     [{:contentType "text/cql" :data (base64 (vue "1.0.0"))}]]]
      (avec-base [db [[(bibliotheque "l1" url "1.0.0" contenu)]]]
                 (given (reference/resolve-view db url)
                   ::anom/category := ::anom/incorrect
                   :http/status := 422)))))

(deftest entree
  (avec-base [db [[(rangee "l1" "1.0.0")]]]
             (testing "sans référence : l'entrée est rendue telle quelle"
               (is (= {:view (vue "x") :name "a"} (reference/resolve-entry db {:view (vue "x") :name "a"}))))
             (testing "référence : remplacée par la vue, le reste conservé"
               (given (reference/resolve-entry db {:view-reference url :name "a"})
                 [:view :version] := "1.0.0"
                 :name := "a"
                 :view-reference := nil))
             (testing "refus : l'anomalie"
               (given (reference/resolve-entry db {:view-reference "ViewDefinition/l1"})
                 ::anom/category := ::anom/unsupported))))
