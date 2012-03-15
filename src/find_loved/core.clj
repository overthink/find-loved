(ns ^{:author "Mark Feeney"}
  find-loved.core
  (:use [clojure.tools.cli :only (cli)]
        [clojure.data.zip.xml :only (attr text xml-> xml1->)])
  (:require [clj-http.client :as client]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.java.io :as io])
  (:import [org.jaudiotagger.audio AudioFileIO AudioFileFilter]
           [org.jaudiotagger.tag FieldKey])
  (:gen-class))

; Some constants
(def LIMIT 50)
(def LOVED_URL "http://ws.audioscrobbler.com/2.0/?method=user.getlovedtracks&user=%s&api_key=%s&page=%d&limit=%d")

(defn get-api-key
  "Returns the last.fm API key found in ~/.lastfm_api_key, or nil if that can't
  be found."
  []
  (let [home (-> (System/getenv) (get "HOME" "."))
        f    (io/as-file (format "%s/.lastfm_api_key" home))]
    (when (.exists f)
      (.trim (slurp f)))))

; A track returned from last.fm's API
(defrecord LovedTrack [artist-name track-name date-added])

; A track found in the local file system
(defrecord FsTrack [artist-name track-name album-name path])

(defn mk-loved-track
  "Turn the given Clojure XML zipper into a LovedTrack record."
  [z]
    (LovedTrack.
      (xml1-> z :artist :name text)
      (xml1-> z :name text)
      (xml1-> z :date (attr :uts))))

; We only need one of these filters, so make it a 'global' in the namespace.
(def audio-file-filter (AudioFileFilter. false))

(defn mk-fs-track
  "Make a track record for a music file on the local filesystem.  Returns a
  FsTrack if possible, or nil if we couldn't read meta-data from the file."
  [^java.io.File file]
  (when (.accept audio-file-filter file)
    (let [tag (-> (AudioFileIO/read file) (.getTag))
          artist (.getFirst tag FieldKey/ARTIST)
          track (.getFirst tag FieldKey/TITLE)
          album (.getFirst tag FieldKey/ALBUM)]
      (FsTrack. artist track album (.getAbsolutePath file)))))

(defn str-to-xml 
  "Parse given string into Clojure's tag/attrs/content format."
  [s]
  (with-open [xml-in (io/input-stream (.getBytes s "UTF-8"))]
    (xml/parse xml-in)))

(defn ser
  "Write obj to filename in a way that Clojure's reader can read it back in.
  Returns obj."
  [filename obj]
  (with-open [w (io/writer filename)]
    (binding [*out* w
              *print-dup* true]
      (prn obj)))
  obj)

(defn deser
  "Reads a single Clojure data structure from f and returns it. f is anything
  that can be coerced into a File."
  [f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (binding [*read-eval* false]  ; don't allow eval reader macro '#='
      (read r))))

(defn get-tracks
  "Get a lazy seq of user's loved tracks.  HTTP requests are made only as
  necessary as the list is consumed."
  ([user api-key] (get-tracks user api-key 1))
  ([user api-key page]
   (lazy-seq
     (let [resp (client/get (format LOVED_URL user api-key page LIMIT))
           zipped (zip/xml-zip (str-to-xml (:body resp)))
           total (Integer/valueOf (xml1-> zipped :lovedtracks (attr :totalPages)))
           tracks (map mk-loved-track (xml-> zipped :lovedtracks :track))]
       (when (<= page total)
         (concat tracks (get-tracks user api-key (inc page))))))))

(defn get-tracks-cached
  "If we have a cached copy of the loved tracks on disk, use them, otherwise
  forward call to get-tracks."
  [user api-key]
  (let [f (io/as-file (format ".%s_loved_tracks" user))]
    (if (.exists f)
      (deser f)
      (ser (.getName f) (get-tracks user api-key))))) ; get all the tracks, cache 'em on disk, return 'em

(defn -main [& args]
  (let [[opts args banner] (cli args 
                                ["--api-key" "last.fm API key, if not set users $HOME/.lastfm_api_key"]
                                ["-h" "--help" "Show help and exit" :flag true]
                                ["--debug" "Output a lot of junk" :flag true])
        api-key (or (:api-key opts) (get-api-key))
        [user & roots] args
        fs-tracks (->> roots 
                    (map io/as-file)
                    (mapcat file-seq)
                    (map mk-fs-track))
        loved-tracks (get-tracks-cached user api-key)]
    (when (:help opts)
      (println "find-loved LASTFM-USERNAME FS-ROOT0 [RS-ROOT1 [..]]")
      (println banner)
      (System/exit 1))
    (doseq [t loved-tracks]
      (println (format "%s - %s" (:artist-name t) (:track-name t))))
    (doseq [t fs-tracks]
      (prn t))
    (println (format "\nTotal: %d tracks" (count loved-tracks)))))

