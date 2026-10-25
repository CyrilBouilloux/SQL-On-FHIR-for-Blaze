(ns blaze.addon.sql-on-fhir.data-source-test
  "T-034 (DS-01…DS-04, DS-06, DS-07, IB-10) : lecture à `t` fixe sur le nœud en mémoire du jar."
  (:refer-clojure :exclude [compile])
  (:require
   [blaze.addon.sql-on-fhir.data-source :as ds]
   [blaze.addon.sql-on-fhir.data-source-spec]
   [blaze.addon.sql-on-fhir.noeud :as noeud]
   [blaze.addon.sql-on-fhir.test-util :as su]
   [blaze.addon.sql-on-fhir.view.compile :refer [compile]]
   [blaze.addon.sql-on-fhir.view.fichier :as fichier]
   [blaze.anomaly :as ba]
   [blaze.db.api :as d]
   [blaze.fhir.spec :as fhir-spec]
   [blaze.test-util :as tu]
   [clojure.spec.test.alpha :as st]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [integrant.core :as ig]
   [jsonista.core :as j]
   [juxt.iota :refer [given]]))

(st/instrument)

(use-fixtures :once su/registre-types)
(use-fixtures :each tu/fixture)

(def ^:private contexte (delay (fichier/contexte)))

(defn- lire [m] (fhir-spec/parse-json @contexte (j/write-value-as-string m)))

(defn- patient [id & [m]] [:put (lire (merge {:resourceType "Patient" :id id :active true} m))])

(defn- observation [id sujet & [m]]
  [:put (lire (merge {:resourceType "Observation" :id id :status "final" :code {:text "x"}
                      :subject {:reference (str "Patient/" sujet)}}
                     m))])

(defn- groupe [id & membres]
  [:put (lire {:resourceType "Group" :id id :type "person" :actual true
               :member (mapv (fn [r] {:entity {:reference r}}) membres)})])

(defmacro ^:private avec-noeud
  {:clj-kondo/lint-as 'clojure.core/let}
  [[noeud txs] & body]
  `(let [systeme# (noeud/demarrer)
         ~noeud (:blaze.db/node systeme#)]
     (try
       (run! #(noeud/ecrire! ~noeud %) ~txs)
       ~@body
       (finally (ig/halt! systeme#)))))

(def ^:private jeu
  [[(patient "p1") (patient "p2") (patient "p3") (patient "p4")
    [:put (lire {:resourceType "Organization" :id "x"})]]
   [(observation "o1" "p1") (observation "o2" "p1") (observation "o3" "p2")
    (observation "o4" "p2" {:performer [{:reference "Patient/p1"}]})]
   [(groupe "g1" "Patient/p1" "Patient/p2" "Organization/x") (groupe "g0")]
   [[:delete "Patient" "p4"]]])

(defn- ids [ressources] (vec (sort (map :id (into [] ressources)))))

(defn- vue [type colonnes]
  (compile {:resourceType "ViewDefinition" :status "active" :resource type
            :select [{:column (mapv (fn [c] {:name c :path c}) colonnes)}]}))

(deftest lecture-d-un-type
  (avec-noeud [noeud jeu]
              (let [db (d/db noeud)]
                (testing "DS-01 : autant de ressources que type-list, supprimées exclues"
                  (is (= (d/type-total db "Patient") (count (into [] (ds/resources db "Patient" {})))))
                  (given (ids (ds/resources db "Patient" {})) [0] := "p1" count := 3)
                  (given (ids (ds/resources db "Observation" {})) count := 4)
                  (given (ids (ds/resources db "Encounter" {})) count := 0)))))

(deftest filtre-patient
  (avec-noeud [noeud jeu]
              (let [db (d/db noeud)]
                (testing "DS-02 : compartiment du patient, une fois par ressource"
                  (is (= ["o1" "o2" "o4"] (ids (ds/resources db "Observation" {:patient "p1"}))))
                  (is (= ["p1"] (ids (ds/resources db "Patient" {:patient "p1"}))))
                  (is (= [] (ids (ds/resources db "Observation" {:patient "p3"})))))
                (testing "patient absent ou supprimé : not-found"
                  (doseq [id ["zz" "p4"]]
                    (given (ds/resources db "Observation" {:patient id})
                      ::anom/category := ::anom/not-found))))))

(deftest filtre-groupe
  (avec-noeud [noeud jeu]
              (let [db (d/db noeud)]
                (testing "DS-03 : compartiments des membres Patient, o4 une seule fois, membre non Patient ignoré"
                  (is (= ["o1" "o2" "o3" "o4"] (ids (ds/resources db "Observation" {:group "g1"}))))
                  (is (= ["p1" "p2"] (ids (ds/resources db "Patient" {:group "g1"})))))
                (testing "groupe vide : aucune ressource"
                  (is (= [] (ids (ds/resources db "Observation" {:group "g0"})))))
                (testing "groupe absent : not-found"
                  (given (ds/resources db "Observation" {:group "zz"})
                    ::anom/category := ::anom/not-found))
                (testing "patient et groupe : intersection"
                  (is (= ["o1" "o2" "o4"] (ids (ds/resources db "Observation" {:group "g1" :patient "p1"}))))
                  (is (= [] (ids (ds/resources db "Observation" {:group "g1" :patient "p3"}))))))))

(deftest filtre-since
  (avec-noeud [noeud jeu]
              (let [db (d/db noeud)
                    instant #(str (:blaze.db.tx/instant (d/tx db %)))]
                (testing "DS-04 : bornes comme _lastUpdated=gt (exclue), même résultat que la recherche"
                  (let [t-o (instant 2)
                        attendu (ids @(d/type-query db "Observation" [["_lastUpdated" (str "gt" (instant 1))]]))]
                    (is (= ["o1" "o2" "o3" "o4"] attendu))
                    (is (= attendu (ids (ds/resources db "Observation" {:since (instant 1)}))))
                    (is (= [] (ids (ds/resources db "Observation" {:since t-o}))))
                    (is (= ["o1" "o2" "o4"] (ids (ds/resources db "Observation" {:since (instant 1) :patient "p1"}))))
                    (is (= ["o1" "o2" "o3" "o4"] (ids (ds/resources db "Observation" {:since (instant 1) :group "g1"}))))
                    (is (= [] (ids (ds/resources db "Patient" {:since (instant 1)}))))))
                (testing "instant invalide : incorrect"
                  (given (ds/resources db "Observation" {:since "pas-un-instant"})
                    ::anom/category := ::anom/incorrect)))))

(deftest lignes-et-limite
  (avec-noeud [noeud jeu]
              (let [db (d/db noeud)
                    v (vue "Patient" ["id"])]
                (testing "lignes de la vue"
                  (is (= [{:id "p1"} {:id "p2"} {:id "p3"}] (sort-by :id (ds/rows v db {})))))
                (testing "_limit = min(n, total)"
                  (is (= 1 (count (ds/rows v db {:limit 1}))))
                  (is (= 3 (count (ds/rows v db {:limit 10}))))
                  (is (= 2 (count (ds/rows v db {:limit 2 :group "g1"})))))
                (testing "anomalie de la source rendue telle quelle"
                  (given (ds/rows v db {:patient "zz"}) ::anom/category := ::anom/not-found)))))

(deftest limite-sans-lire-plus-que-necessaire
  (avec-noeud [noeud [[(patient "p1")] (mapv #(observation (str "o" %) "p1") (range 250))]]
              (let [db (d/db noeud)
                    v (vue "Observation" ["id"])
                    appels (atom 0)
                    pull-many d/pull-many]
                (with-redefs [d/pull-many (fn [& args] (swap! appels inc) (apply pull-many args))]
                  (is (= 1 (count (ds/rows v db {:limit 1}))))
                  (is (= 1 @appels))
                  (reset! appels 0)
                  (is (= 250 (count (ds/rows v db {}))))
                  (is (= 3 @appels))))))

(deftest instant-coherent
  (avec-noeud [noeud jeu]
              (let [db (d/db noeud)
                    v (vue "Patient" ["id" "active"])
                    lecture (ds/resources db "Patient" {})
                    avant (ds/rows v db {})]
      ;; pendant la « pause » : une ressource créée, une modifiée
                (noeud/ecrire! noeud [(patient "p9") (patient "p1" {:active false})])
                (is (= 4 (d/type-total (d/db noeud) "Patient")))
                (testing "DS-07 : la créée dans 0 ligne, la modifiée avec ses valeurs d'avant"
                  (is (= ["p1" "p2" "p3"] (ids lecture)))
                  (is (= (sort-by :id avant) (sort-by :id (ds/rows v db {}))))
                  (is (true? (:active (first (sort-by :id (ds/rows v db {}))))))
                  (is (false? (:active (first (sort-by :id (ds/rows v (d/db noeud) {}))))))))))

(deftest source-de-lignes
  (avec-noeud [noeud [[(patient "p1") (patient "p2") (patient "p3")] (mapv #(observation (str "o" %) "p1") (range 250))]]
              (let [db (d/db noeud)
                    v (vue "Observation" ["id"])
                    appels (atom 0)
                    pull-many d/pull-many]
                (testing "DS-01 (T-048) : mêmes lignes que `rows`, réductibles et lues à la demande"
                  (let [source (ds/row-source v db {})]
                    (is (instance? clojure.lang.IReduceInit source))
                    (is (= (sort-by :id (ds/rows v db {})) (sort-by :id (into [] source))))
                    (is (= 250 (count (into [] source))))))
                (testing "la lecture s'arrête dès que le consommateur rend `reduced`"
                  (with-redefs [d/pull-many (fn [& args] (swap! appels inc) (apply pull-many args))]
                    (is (= 1 (count (into [] (take 1) (ds/row-source v db {})))))
                    (is (= 1 @appels))))
                (testing "filtres et anomalie de la source"
                  (is (= 250 (count (into [] (ds/row-source v db {:patient "p1"})))))
                  (given (ds/row-source v db {:patient "zz"}) ::anom/category := ::anom/not-found))
                (testing "une ressource que la vue refuse : l'anomalie sort du réduit, par `ba/try-anomaly`"
                  (let [mauvaise (compile {:resourceType "ViewDefinition" :status "active" :resource "Observation"
                                           :select [{:column [{:name "c" :path "code"}]}]})]
                    (given (ba/try-anomaly (into [] (ds/row-source mauvaise db {})))
                      ::anom/category := ::anom/incorrect))))))
