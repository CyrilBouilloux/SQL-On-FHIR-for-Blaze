(ns blaze.addon.sql-on-fhir.view.fichier-test
  "T-030 (F-006, oracle des N2) : exécution en mémoire d'une vue sur un fichier NDJSON, par
  `view.run/run` inchangé. Absence d'un fichier : code 3 (NON-BRANCHE), jamais une sortie vide."
  (:require
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.test-util :as tu]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [jsonista.core :as j]))

(set! *warn-on-reflection* true)

(use-fixtures :each tu/fixture)

(def ^:private vue
  {:resourceType "ViewDefinition" :resource "Patient" :status "active"
   :select [{:column [{:name "id" :path "id"}
                      {:name "genre" :path "gender"}]}]})

(def ^:private patients
  [{:resourceType "Patient" :id "p1" :gender "male"}
   {:resourceType "Patient" :id "p2" :gender "female"}
   {:resourceType "Observation" :id "o1" :status "final" :code {:text "x"}}])

(defn- ecrire! [nom contenu]
  (let [f (java.io.File/createTempFile nom ".json")]
    (.deleteOnExit f)
    (spit f contenu)
    (.getPath f)))

(defn- ndjson [coll]
  (str/join "\n" (map j/write-value-as-string coll)))

(def ^:private ctx (delay (fichier/contexte)))

(defn- verdict [vue ressources]
  (fichier/verdict @ctx (ecrire! "vue" (j/write-value-as-string vue))
                   (ecrire! "res" (ndjson ressources))))

(deftest lignes-test
  (testing "une ligne NDJSON par ligne de vue, colonnes dans l'ordre, ressources d'un autre type ignorées"
    (is (= [0 ["{\"id\":\"p1\",\"genre\":\"male\"}" "{\"id\":\"p2\",\"genre\":\"female\"}"]]
           ((juxt :code :lignes) (verdict vue patients)))))
  (testing "témoin rouge : une colonne altérée (gender -> id) change la sortie"
    (is (= ["{\"id\":\"p1\",\"genre\":\"p1\"}" "{\"id\":\"p2\",\"genre\":\"p2\"}"]
           (:lignes (verdict (assoc-in vue [:select 0 :column 1 :path] "id") patients)))))
  (testing "lignes vides ignorées"
    (is (= 2 (count (:lignes (fichier/verdict @ctx (ecrire! "vue" (j/write-value-as-string vue))
                                              (ecrire! "res" (str (ndjson patients) "\n\n")))))))))

(deftest fichier-absent-test
  (let [ok (ecrire! "vue" (j/write-value-as-string vue))]
    (testing "ressources absentes : NON-BRANCHE, code 3, aucune ligne"
      (is (= [3 nil "NON-BRANCHE : fichier absent /nulle/part.ndjson"]
             ((juxt :code :lignes :erreur) (fichier/verdict @ctx ok "/nulle/part.ndjson")))))
    (testing "vue absente : NON-BRANCHE, code 3"
      (is (= [3 "NON-BRANCHE : fichier absent /nulle/vue.json"]
             ((juxt :code :erreur) (fichier/verdict @ctx "/nulle/vue.json" ok)))))))

(deftest anomalie-test
  (testing "vue refusée par le moteur : code 8, catégorie et message"
    (let [v (verdict (assoc-in vue [:select 0 :column 0 :path] "id.(") patients)]
      (is (= 8 (:code v)))
      (is (nil? (:lignes v)))
      (is (str/starts-with? (:erreur v) "incorrect : "))))
  (testing "ressource illisible : code 8"
    (let [v (fichier/verdict @ctx (ecrire! "vue" (j/write-value-as-string vue))
                             (ecrire! "res" "pas du json"))]
      (is (= 8 (:code v)))
      (is (str/starts-with? (:erreur v) "incorrect : ")))))

(deftest stdout-sans-journal-test
  (testing "stdout ne porte que des lignes de vue : chacune relue par un analyseur JSON"
    (let [err (java.io.StringWriter.)
          code (atom nil)
          out (binding [*err* err]
                (with-out-str
                  (reset! code (fichier/lancer (ecrire! "vue" (j/write-value-as-string vue))
                                               (ecrire! "res" (ndjson patients))))))]
      (is (= 0 @code))
      (is (= [{:id "p1" :genre "male"} {:id "p2" :genre "female"}]
             (mapv #(j/read-value % (j/object-mapper {:decode-key-fn true}))
                   (str/split-lines out))))
      (testing "le journal d'initialisation a été dérouté sur stderr, non coupé"
        (is (str/includes? (str err) "Init parsing context")))))
  (testing "erreur : stdout vide, message sur stderr"
    (let [err (java.io.StringWriter.)
          out (binding [*err* err]
                (with-out-str
                  (fichier/lancer "/nulle/vue.json" "/nulle/res.ndjson")))]
      (is (= "" out))
      (is (str/includes? (str err) "NON-BRANCHE : fichier absent /nulle/vue.json")))))
