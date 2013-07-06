(ns ^{:author "Mark Feeney"}
  find-loved.core
  (:use [clojure.tools.cli :only (cli)]
        [clojure.data.zip.xml :only (attr text xml-> xml1->)])
  (:require [clj-http.client :as client]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.util.logging Logger]
           [org.jaudiotagger.audio AudioFileIO AudioFileFilter]
           [org.jaudiotagger.tag FieldKey])
  (:gen-class))

; Some constants
(def LIMIT 50)
(def LOVED_URL "http://ws.audioscrobbler.com/2.0/?method=user.getlovedtracks&user=%s&api_key=%s&page=%d&limit=%d")
(def CUR_YEAR (-> (java.util.GregorianCalendar.)
                  (.get (java.util.Calendar/YEAR))))

(defn get-api-key
  "Returns the last.fm API key found in ~/.lastfm_api_key, or nil if that can't
  be found."
  []
  (let [home (-> (System/getenv) (get "HOME" "."))
        f    (io/as-file (format "%s/.lastfm_api_key" home))]
    (when (.exists f)
      (.trim (slurp f)))))

; A track returned from last.fm's API
(defrecord LovedTrack
  [artist-name
   mb-artist-id
   track-name
   mb-track-id
   date-added])

; A track found in the local file system
(defrecord FsTrack
  [artist-name
   mb-artist-id
   track-name
   mb-track-id
   album-name
   year
   path])

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
    ;; set edn reader options so our LovedTrack records are read in correctly
    ;; without using plain old read with *read-eval*==true.
    (let [opts {:readers {'find_loved.core.LovedTrack
                          (partial apply find-loved.core/->LovedTrack)}}]
      (edn/read opts r))))

(defn get-tracks
  "Get a lazy seq of user's loved tracks.  HTTP requests are made only as
  necessary as the list is consumed."
  ([user api-key] (get-tracks user api-key 1))
  ([user api-key page]
   (lazy-seq
     (let [resp (client/get (format LOVED_URL user api-key page LIMIT))
           zipped (zip/xml-zip (str-to-xml (:body resp)))
           total (Integer/valueOf (xml1-> zipped
                                          :lovedtracks
                                          (attr :totalPages)))
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
      ; get all the tracks, cache 'em on disk, return 'em
      (ser (.getName f) (get-tracks user api-key)))))

(defn normalize
  "Normalize s for use as a key in our track db indexes.  Lowercase everything,
  clean up spacing, squash various noise chars."
  [^String s]
  (when s
    (-> s (.toLowerCase)
      (str/replace #"[/-]" " ")
      (str/replace #"['\"().]" "")
      (str/replace #"\bthe\b" "")
      (str/replace #"\s+" " ")
      (.trim))))

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

;(def t1 (FsTrack. "foos" nil "some title" nil "studio album" "1977" "/asdf"))
;(def t2 (FsTrack. "foos" nil "some title" nil "2000-02-23 Live in Hell" "2000" "/asdf"))

(defn score-fs-track
  "Scoring function used to sort candidate FsTracks."
  [t]
  ; if-let needed when accessing the FsTrack record since sometimes the stored
  ; value for a key is nil, so (:foo t "default") doesn't work
  (let [year        (if-let [x (:year t)] x "1900")
        album-name  (if-let [x (:album-name t)] x "")
        age         (Integer/valueOf (subs year 0 4))
        date-title  (re-find #"\d{4}-(\d\d|xx)-(\d\d|xx)" album-name) ; some dates look like "1999-xx-xx"
        sotd-title  (re-find #"(?i)song of the day" album-name)
        sotd-factor (if sotd-title 0.75 1.0)  ; penalize 'song of the day' mix albums
        age-factor  (if date-title 0.5 1.0)]  ; If title contains a date-looking thing, penalize it
     (* sotd-factor
        (/ (* age age-factor)
           CUR_YEAR))))

(defn best-match
  "When a bunch of FsTrack objects seem to match a LovedTrack, this function
  picks the best one.  Can return nil if all the candidates are crappy."
  [loved-track fs-tracks]
  (->> fs-tracks
       (filter #(->> [% loved-track] ; Drop fs tracks where artist name doesn't match loved track's artist
                     (map :artist-name)
                     (map normalize)
                     (apply =)))
    (sort-by score-fs-track >)
    (first)))

(defn print-misses
  "Print info about the loved tracks that we could not match."
  [misses]
  (let [grouped (group-by :artist-name misses)
        sorted (sort-by #(* -1 (count (second %))) grouped)] ; show artists with most missing tracks first
    (doseq [[artist tracks] sorted]
      (println (str artist ":"))
        (doseq [t tracks]
          (println (str "  - " (:track-name t)))))))

(defn- parse-cli [args]
  (cli args
       ["--api-key" "last.fm API key, if not set users $HOME/.lastfm_api_key"]
       ["--quiet" "Don't write informative stuff on stderr."]
       ["-h" "--help" "Show help and exit" :flag true]))

(defn -main [& args]
  (set! *warn-on-reflection* true)
  (disable-jul!)
  (let [[opts args banner] (parse-cli args)
        api-key (or (:api-key opts) (get-api-key))
        [user & roots] args
        loved-tracks (get-tracks-cached user api-key)
        fs-tracks (->> roots
                       (map io/as-file)
                       (mapcat file-seq)
                       (map mk-fs-track)
                       (remove nil?))
        track-db (reduce add-track {} fs-tracks)
        misses (atom [])]
    (when (:help opts)
      (println "find-loved LASTFM-USERNAME SEARCH_DIR0 [SEARCH_DIR1 [..]]")
      (println banner)
      (System/exit 1))

    ; Find matches just by track name, then hand-off to 'best-match' to select
    ; which of the matches (if any) should win.  If no match is found, record
    ; it our "misses" list.
    (doseq [t loved-tracks]
      (let [matches (get-in track-db [:by-track-name (normalize (:track-name t))])]
        (if-let [best (best-match t matches)]
          (pr-m3u-line best)
          (swap! misses conj t))))

    (when-not (:quiet opts)
      (binding [*out* *err*]
        (print-misses @misses)
        (println)
        (println (format "%d missing loved tracks" (count @misses)))
        (println (format "%d unique track names in db" (count (get-in track-db [:by-track-name]))))
        (println (format "%d artist name variations in db" (count (get-in track-db [:by-artist-name]))))
        (println (format "%d loved tracks" (count loved-tracks)))
        (println (format "%d fs tracks" (count fs-tracks)))))))

