(ns ^{:author "Mark Feeney"}
  find-loved.core
  (:use [clojure.tools.cli :only (cli)]
        [clojure.data.zip.xml :only (attr text xml-> xml1->)])
  (:require [clj-http.client :as client]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.java.io :as io])
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

(defn make-track
  "Turn the given Clojure XML zipper into a track object."
  [z]
    {:artist-name (xml1-> z :artist :name text)
     :track-name (xml1-> z :name text)
     :date-added (xml1-> z :date (attr :uts))})

(defn str-to-xml 
  "Parse given string into Clojure's tag/attrs/content format."
  [s]
  (with-open [xml-in (io/input-stream (.getBytes s "UTF-8"))]
    (xml/parse xml-in)))

(defn ser
  "Write obj to filename in a way that Clojure's reader can read it back in."
  [filename obj]
  (with-open [w (io/writer filename)]
    (binding [*out* w
              *print-dup* true]
      (prn obj))))

(defn deser
  "Reads whatever's in filename and returns it."
  [filename]
  (with-open [r (java.io.PushbackReader. (io/reader filename))]
    (binding [*read-eval* false]
      (read r))))

(defn get-tracks
  "Get a lazy seq of user's loved tracks.  HTTP requests are made only as
  necessary as the list is consumed."
  ([user api-key] (get-tracks user api-key 1))
  ([user api-key page]
   (lazy-seq
     (let [resp (do #_(println (format "\nreq page %d\n" page)) (client/get (format LOVED_URL user api-key page LIMIT)))
           zipped (zip/xml-zip (str-to-xml (:body resp)))
           total (Integer/valueOf (xml1-> zipped :lovedtracks (attr :totalPages)))
           tracks (map make-track (xml-> zipped :lovedtracks :track))]
       (when (<= page total)
         (concat tracks (get-tracks user api-key (inc page))))))))

(defn -main [& args]
  (let [[opts args banner] (cli args 
                                ["--api-key" "last.fm API key, if not set users $HOME/.lastfm_api_key"]
                                ["-h" "--help" "Show help and exit" :flag true]
                                ["--debug" "Output a lot of junk" :flag true])
        api-key (or (:api-key opts) (get-api-key))
        [user & roots] args
        tracks (get-tracks user api-key)]
    (when (:help opts)
      (println "find-loved LASTFM-USERNAME FS-ROOT0 [RS-ROOT1 [..]]")
      (println banner)
      (System/exit 1))
    (doseq [t tracks]
      (println (format "%s - %s" (:artist-name t) (:track-name t))))
    (println (format "\nTotal: %d tracks" (count tracks)))))

