(ns blaze.addon.sql-on-fhir.export.storage
  "AS-05 : fichiers d'un export dans le dossier d'export. Un fichier s'appelle `<jeton>.<ext>` ;
  il est écrit en `<jeton>.<ext>.part` puis renommé : sous son nom, il est entier."
  (:require
   [clojure.java.io :as io])
  (:import
   [java.io File Writer]
   [java.nio.file Files StandardCopyOption]))

(set! *warn-on-reflection* true)

(def ^:private extensions {"ndjson" "ndjson" "csv" "csv" "json" "json"})

(def ^:private content-types
  {"ndjson" "application/x-ndjson" "csv" "text/csv" "json" "application/json"})

(defn extension
  "Extension des fichiers du `format` (`json`, `ndjson`, `csv`)."
  [format]
  (extensions format))

(defn content-type
  "Type de contenu des fichiers du `format`."
  [format]
  (content-types format))

(defn- named ^File [dir token format suffix]
  (io/file dir (str token "." (extension format) suffix)))

(defn file
  "Fichier `<jeton>.<ext>` de `dir`."
  ^File [dir token format]
  (named dir token format ""))

(defn write-file!
  "Écrit le fichier du `token` : `write-fn` reçoit un `Writer` UTF-8 sur `<jeton>.<ext>.part`,
  qui est renommé s'il revient. Si elle lance, le `.part` est supprimé et l'exception sort.
  Crée `dir` au besoin. Rend le fichier."
  ^File [^File dir token format write-fn]
  (.mkdirs dir)
  (let [part (named dir token format ".part")
        target (file dir token format)]
    (try
      (with-open [^Writer out (io/writer part :encoding "UTF-8")]
        (write-fn out))
      (Files/move (.toPath part) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
      target
      (catch Throwable e
        (.delete part)
        (throw e)))))

(defn delete-files!
  "Supprime, dans `dir`, le fichier et le `.part` de chacun des `tokens`."
  [dir tokens format]
  (run! (fn [token]
          (.delete (file dir token format))
          (.delete (named dir token format ".part")))
        tokens))

(defn used-bytes
  "Somme des tailles des fichiers de `dir`, `.part` compris ; 0 si `dir` n'existe pas."
  [^File dir]
  (transduce (map #(.length ^File %)) + 0 (.listFiles dir)))

(defn limited-writer
  "`Writer` qui écrit dans `out` et appelle `check!` (qui lève pour interrompre) chaque fois que
  `step` caractères au moins ont été écrits depuis le dernier appel — `out` est alors vidé, la
  taille du fichier est à jour — puis une dernière fois à la fermeture, avant de fermer `out`."
  ^Writer [^Writer out step check!]
  (let [pending (volatile! 0)
        count! (fn [n]
                 (when (<= step (vswap! pending + n))
                   (vreset! pending 0)
                   (.flush out)
                   (check!)))]
    (proxy [Writer] []
      (write
        ([x]
         (if (integer? x)
           (do (.write out (int x)) (count! 1))
           (do (.write out ^String x) (count! (count x)))))
        ([x off len]
         (.write out ^String x (int off) (int len))
         (count! len)))
      (flush [] (.flush out))
      (close []
        (try
          (.flush out)
          (check!)
          (finally (.close out)))))))
