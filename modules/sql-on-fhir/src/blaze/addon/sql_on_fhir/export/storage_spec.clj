(ns blaze.addon.sql-on-fhir.export.storage-spec
  (:require
   [blaze.addon.sql-on-fhir.export.storage :as storage]
   [clojure.spec.alpha :as s])
  (:import
   [java.io File]))

(s/def ::storage/format #{"json" "ndjson" "csv"})
(s/def ::storage/dir (partial instance? File))

(s/fdef storage/extension
  :args (s/cat :format ::storage/format)
  :ret string?)

(s/fdef storage/content-type
  :args (s/cat :format ::storage/format)
  :ret string?)

(s/fdef storage/file
  :args (s/cat :dir ::storage/dir :token string? :format ::storage/format)
  :ret (partial instance? File))

(s/fdef storage/write-file!
  :args (s/cat :dir ::storage/dir :token string? :format ::storage/format :write-fn fn?)
  :ret (partial instance? File))

(s/fdef storage/delete-files!
  :args (s/cat :dir ::storage/dir :tokens (s/coll-of string?) :format ::storage/format)
  :ret nil?)

(s/fdef storage/used-bytes
  :args (s/cat :dir ::storage/dir)
  :ret nat-int?)

(s/fdef storage/limited-writer
  :args (s/cat :out (partial instance? java.io.Writer) :step pos-int? :check! fn?)
  :ret (partial instance? java.io.Writer))
