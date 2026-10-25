(ns blaze.addon.sql-on-fhir.export.storage-test
  "AS-05 : fichiers `<jeton>.<ext>`, écrits en `.part` puis renommés."
  (:require
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [blaze.addon.sql-on-fhir.export.storage-spec]
   [blaze.test-util :as tu]
   [clojure.java.io :as io]
   [clojure.spec.test.alpha :as st]
   [clojure.test :as test :refer [deftest is testing]]
   [juxt.iota :refer [given]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)
(st/instrument)

(test/use-fixtures :each tu/fixture)

(defn- dossier-temporaire []
  (.toFile (Files/createTempDirectory "export-test" (make-array FileAttribute 0))))

(defn- noms [dir] (vec (sort (.list dir))))

(defn- supprimer! [dir]
  (run! #(.delete ^java.io.File %) (.listFiles dir))
  (.delete dir))

(deftest formats
  (given (map storage/extension ["ndjson" "csv" "json"]) vec := ["ndjson" "csv" "json"])
  (given (map storage/content-type ["ndjson" "csv" "json"])
    vec := ["application/x-ndjson" "text/csv" "application/json"]))

(deftest ecriture
  (let [dir (dossier-temporaire)]
    (try
      (testing "le fichier n'existe sous son nom qu'une fois écrit en entier"
        (let [pendant (atom nil)
              f (storage/write-file! dir "tok1" "csv"
                                     (fn [out] (.write out "a,b\r\n") (reset! pendant (noms dir))))]
          (is (= ["tok1.csv.part"] @pendant))
          (is (= ["tok1.csv"] (noms dir)))
          (is (= (storage/file dir "tok1" "csv") f))
          (is (= "a,b\r\n" (slurp f)))))
      (testing "UTF-8"
        (storage/write-file! dir "tok2" "ndjson" (fn [out] (.write out "{\"nom\":\"Zoë ☃\"}\n")))
        (is (= "{\"nom\":\"Zoë ☃\"}\n" (slurp (io/file dir "tok2.ndjson") :encoding "UTF-8"))))
      (testing "écriture qui échoue : ni fichier ni .part, l'exception sort"
        (is (thrown-with-msg? Exception #"boum"
                              (storage/write-file! dir "tok3" "json" (fn [out] (.write out "[") (throw (ex-info "boum" {}))))))
        (is (= ["tok1.csv" "tok2.ndjson"] (noms dir))))
      (testing "suppression : fichier et .part, jetons absents ignorés"
        (spit (io/file dir "tok4.json.part") "x")
        (storage/delete-files! dir ["tok1" "tok4" "absent"] "csv")
        (storage/delete-files! dir ["tok4"] "json")
        (is (= ["tok2.ndjson"] (noms dir))))
      (finally (supprimer! dir)))))

(deftest octets-utilises
  (let [dir (dossier-temporaire)]
    (try
      (testing "dossier vide ou absent : 0"
        (is (= 0 (storage/used-bytes dir)))
        (is (= 0 (storage/used-bytes (io/file dir "absent")))))
      (testing "somme des fichiers, .part compris"
        (spit (io/file dir "a.ndjson") "12345")
        (spit (io/file dir "b.csv.part") "678")
        (is (= 8 (storage/used-bytes dir))))
      (finally (supprimer! dir)))))

(deftest ecrivain-borne
  (let [dir (dossier-temporaire)
        verifs (atom 0)
        borne (fn [out step] (storage/limited-writer out step #(swap! verifs inc)))]
    (try
      (testing "le contenu passe tel quel"
        (storage/write-file! dir "t1" "ndjson"
                             (fn [out] (let [w (borne out 1000)] (.write w "ab") (.write w "cd\n") (.close w))))
        (is (= "abcd\n" (slurp (io/file dir "t1.ndjson")))))
      (testing "une vérification à la fermeture, et une à chaque `step` caractères écrits"
        (reset! verifs 0)
        (storage/write-file! dir "t2" "ndjson"
                             (fn [out] (let [w (borne out 10)] (dotimes [_ 5] (.write w "123456")) (.close w))))
        (is (= 3 @verifs) "après 12 caractères, après 24, puis à la fermeture")
        (is (= (apply str (repeat 5 "123456")) (slurp (io/file dir "t2.ndjson")))))
      (testing "les trois formes d'écriture d'un `Writer` (caractère, chaîne, tranche de chaîne) et `flush`"
        (storage/write-file! dir "t6" "ndjson"
                             (fn [out] (let [w (borne out 1000)]
                                         (.write w (int 65)) (.write w "xBCDx" 1 3) (.flush w) (.close w))))
        (is (= "ABCD" (slurp (io/file dir "t6.ndjson"))))
        (storage/delete-files! dir ["t6"] "ndjson"))
      (testing "la vérification voit l'octet écrit : le tampon est vidé avant"
        (let [vu (atom nil)]
          (storage/write-file! dir "t3" "ndjson"
                               (fn [out] (let [w (storage/limited-writer out 5 #(reset! vu (.length (io/file dir "t3.ndjson.part"))))]
                                           (.write w "123456789") (.close w))))
          (is (= 9 @vu))))
      (testing "la vérification qui lève interrompt l'écriture : ni fichier ni .part"
        (is (thrown-with-msg? Exception #"plafond"
                              (storage/write-file! dir "t4" "ndjson"
                                                   (fn [out] (let [w (storage/limited-writer out 5 #(throw (ex-info "plafond" {})))]
                                                               (.write w "123456789"))))))
        (is (= ["t1.ndjson" "t2.ndjson" "t3.ndjson"] (noms dir))))
      (testing "vérification qui lève à la fermeture : le fichier sous-jacent est tout de même fermé, rien ne reste"
        (is (thrown-with-msg? Exception #"fin"
                              (storage/write-file! dir "t5" "ndjson"
                                                   (fn [out] (let [w (storage/limited-writer out 1000 #(throw (ex-info "fin" {})))]
                                                               (.write w "x") (.close w))))))
        (is (= ["t1.ndjson" "t2.ndjson" "t3.ndjson"] (noms dir))))
      (finally (supprimer! dir)))))
