(ns ^{:author "Mark Feeney"}
  find-loved.core
  (:use [clojure.tools.cli :only (cli)]
        [clojure.data.zip.xml :only (attr text xml-> xml1->)])
  (:require [clj-http.client :as client]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.java.io :as io])
  (:import [java.util.logging Logger]
           [org.jaudiotagger.audio AudioFileIO AudioFileFilter]
           [org.jaudiotagger.tag FieldKey])
  (:gen-class))

; Some constants
(def LIMIT 50)
(def LOVED_URL "http://ws.audioscrobbler.com/2.0/?method=user.getlovedtracks&user=%s&api_key=%s&page=%d&limit=%d")

(defn debug [msg] (binding [*out* *err*] (println msg)))

(defn get-api-key
  "Returns the last.fm API key found in ~/.lastfm_api_key, or nil if that can't
  be found."
  []
  (let [home (-> (System/getenv) (get "HOME" "."))
        f    (io/as-file (format "%s/.lastfm_api_key" home))]
    (when (.exists f)
      (.trim (slurp f)))))

; A track returned from last.fm's API
(defrecord LovedTrack [artist-name mb-artist-id track-name mb-track-id date-added])

; A track found in the local file system
(defrecord FsTrack [artist-name mb-artist-id track-name mb-track-id album-name year path])

(defn- nonempty "Change empty string to nil" 
  [s] (if (= "" s) nil s))

(defn mk-loved-track
  "Turn the given Clojure XML zipper into a LovedTrack record."
  [z]
  (let [getval (fn [& preds] (-> (apply xml1-> z preds) (nonempty)))]
    (LovedTrack.
      (getval :artist :name text)
      (getval :artist :mbid text)
      (getval :name text) ; track name
      (getval :mbid text) ; musicbrainz track id
      (getval :date (attr :uts)))))

; We only need one of these filters, so make it a 'global' in the namespace.
(def audio-file-filter (AudioFileFilter. false))

(defn mk-fs-track
  "Make a track record for a music file on the local filesystem.  Returns a
  FsTrack if possible, or nil if we couldn't read meta-data from the file."
  [file]
  (when (.accept audio-file-filter file)
    (let [tag (-> (AudioFileIO/read file) (.getTag))
          f (fn [field] (-> (.getFirst tag field) (nonempty)))]
      (when tag
        (FsTrack. 
          (f FieldKey/ARTIST)
          (f FieldKey/MUSICBRAINZ_ARTISTID)
          (f FieldKey/TITLE)
          (f FieldKey/MUSICBRAINZ_TRACK_ID)
          (f FieldKey/ALBUM)
          (f FieldKey/YEAR)
          (.getAbsolutePath file))))))

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

(defn normalize
  "Normalize s for use as a key in our track db indexes.  Lowercase everything,
  clean up spacing, etc.  Ghetto tokenizing, effectively."
  [^String s]
  (when s
    (-> s (.toLowerCase) (.trim) (str/replace #"\s+" " "))))

(defn artist-keys
  "Returns a seq of keys under which tracks from the given artist should be
  stored.  e.g. 'The Decemberists' should be stored under 'the decemberists',
  and 'decemberists'."
  [artist]
  (let [nrm (normalize artist)]
    (if (and nrm (.startsWith nrm "the "))
      [nrm (str/replace nrm #"^the " "")]
      [nrm])))

(defn add-track
  "Add an FsTrack to the given track db.  Return the new db."
  [db track]
  (let [upd (fn [db0 idx k] (update-in db0 [idx k] #(conj (set %1) %2) track))
        ; This code is lame: update the by-artist-name index, save the result,
        ; then in that result update the by-track-name index.
        db1 (reduce #(upd %1 :by-artist-name %2) 
                    db
                    (artist-keys (:artist-name track)))]
      (upd db1 :by-track-name (normalize (:track-name track)))))

(defn disable-jul!
  "Just turn off java.util.logging outright by removing all the handlers on the
  root logger. jaudiotagger spams tons of INFO logs by default, and there
  appeared to be a big performance impact as well."
  []
  (let [root (Logger/getLogger "")
        handlers (.getHandlers root)]
    (doseq [h handlers]
      (.removeHandler root h))))

(defn pr-m3u-line 
  "Print a m3u-compatible line of text to *out* for the given track."
  [t]
  (println (:path t)))

(defn best-match
  "When a bunch of FsTrack objects seem to match a LovedTrack, this function
  picks the best one.  Total BS right now: just prefers the oldest track."
  [loved-track fs-tracks]
  (->> fs-tracks 
    (sort-by #(Integer/valueOf (-> (or (:year %) "9999") (subs 0 4))) ; default to 9999, only look at first 4 chars
             <) ; oldest to newest, no year -> sort last
    (first)))

(defn -main [& args]
  (set! *warn-on-reflection* true)
  (disable-jul!)
  (let [[opts args banner] (cli args
                                ["--api-key" "last.fm API key, if not set users $HOME/.lastfm_api_key"]
                                ["-h" "--help" "Show help and exit" :flag true])
        api-key (or (:api-key opts) (get-api-key))
        [user & roots] args
        loved-tracks (get-tracks-cached user api-key)
        fs-tracks (->> roots
                    (map io/as-file)
                    (mapcat file-seq)
                    (map mk-fs-track)
                    (remove nil?))
        track-db (reduce add-track {} fs-tracks)]
    (when (:help opts)
      (println "find-loved LASTFM-USERNAME SEARCH_DIR0 [SEARCH_DIR1 [..]]")
      (println banner)
      (System/exit 1))

    ; Hey look, it actually matches stuff now.  Really basic matcher just to
    ; see it working.
    (doseq [t loved-tracks]
      (if-let [ms (get-in track-db [:by-track-name (normalize (:track-name t))])]
        (pr-m3u-line (best-match t ms))))

    (debug (format "%d unique track names in db" (count (get-in track-db [:by-track-name]))))
    (debug (format "%d artist name variations in db" (count (get-in track-db [:by-artist-name]))))
    (debug (format "%d loved tracks" (count loved-tracks)))
    (debug (format "%d fs tracks" (count fs-tracks)))))

