(ns ^{:author "Mark Feeney"}
  find-loved.core
  (:require
    [clojure.core.typed :refer [ann cf check-ns Option Seqable] :as t]
    [clojure.tools.cli :refer [cli]]
    [clojure.data.zip.xml :refer [attr text xml-> xml1->]]
    [clj-http.client :as client]
    [clojure.string :as str]
    [clojure.xml :as xml]
    [clojure.zip :as zip]
    [clojure.edn :as edn]
    [clojure.java.io :as io])
  (:import
    [java.io File]
    [java.util Calendar GregorianCalendar]
    [java.util.logging Logger]
    [org.jaudiotagger.audio AudioFile AudioFileIO AudioFileFilter]
    [org.jaudiotagger.tag FieldKey]))

(set! *warn-on-reflection* true)

(ann ^:no-check clojure.xml/parse [Any -> Any])
(ann ^:no-check clojure.string/blank? [Any -> Any])
(ann ^:no-check clojure.string/lower-case [Any -> Any])
(ann ^:no-check clojure.string/replace [Any -> Any])
(ann ^:no-check clojure.string/trim [Any -> Any])
(ann ^:no-check clojure.java.io/writer [Any -> Any])
(ann ^:no-check clojure.java.io/as-file [Any -> Any])
(ann ^:no-check clojure.java.io/input-stream [Any -> Any])
(ann ^:no-check clojure.zip/xml-zip [Any -> Any])
(ann ^:no-check clojure.data.zip.xml/attr [Any -> Any])
(ann ^:no-check clojure.data.zip.xml/text [Any -> Any])
(ann ^:no-check clojure.data.zip.xml/xml-> [Any Any * -> Any])
(ann ^:no-check clojure.data.zip.xml/xml1-> [Any Any * -> Any])
(ann ^:no-check clj-http.client/get [Any -> Any])
(ann ^:no-check clojure.core/update-in [Any -> Any])
(ann ^:no-check clojure.core/get-in [Any -> Any])
(ann ^:no-check clojure.core/sort-by [Any -> Any])
(ann ^:no-check clojure.core/group-by [Any -> Any])
(ann ^:no-check clojure.core/file-seq [Any -> Any])

(t/non-nil-return java.lang.Integer/valueOf :all)

(ann LIMIT Number)
(def LIMIT 50)

(ann LOVED_URL String)
(def LOVED_URL "http://ws.audioscrobbler.com/2.0/?method=user.getlovedtracks&user=%s&api_key=%s&page=%d&limit=%d")

(ann CUR_YEAR Number)
(def CUR_YEAR (. (GregorianCalendar.) get (Calendar/YEAR)))

(ann ^:no-check get-api-key [-> (Option String)])
(defn get-api-key
  "Returns the last.fm API key found in ~/.lastfm_api_key, or nil if that can't
  be found."
  []
  (let [home (get (System/getenv) "HOME" ".")
        f    (io/as-file (format "%s/.lastfm_api_key" home))]
    (when (.exists f)
      (str/trim (slurp f)))))

;; A track returned from last.fm's API
(t/ann-record LovedTrack [artist-name :- (Option String)
                          mb-artist-id :- (Option String)
                          track-name :- (Option String)
                          mb-track-id :- (Option String)
                          date-added :- (Option String)])
(defrecord LovedTrack
  [artist-name
   mb-artist-id
   track-name
   mb-track-id
   date-added])

;; A track found in the local file system
(t/ann-record FsTrack [artist-name :- (Option String)
                       mb-artist-id :- (Option String)
                       track-name :- (Option String)
                       mb-track-id :- (Option String)
                       album-name :- (Option String)
                       year :- (Option String)
                       path :- (Option String)])

(defrecord FsTrack
  [artist-name
   mb-artist-id
   track-name
   mb-track-id
   album-name
   year
   path])

(ann blank->nil [(Option String) -> (Option String)])
(defn blank->nil
  "Change empty strings into nil."
  [s]
  (when (not (str/blank? s)) s))

(ann ^:no-check mk-loved-track [Any -> LovedTrack])
(defn mk-loved-track
  "Turn the given Clojure XML zipper into a LovedTrack record."
  [z]
  (let [getval (fn [& preds] (-> (apply xml1-> z preds)
                                 (blank->nil)))]
    (LovedTrack.
      (getval :artist :name text)
      (getval :artist :mbid text)
      (getval :name text) ; track name
      (getval :mbid text) ; musicbrainz track id
      (getval :date (attr :uts)))))

(ann mk-fs-track [File -> (Option FsTrack)])
(defn mk-fs-track
  "Make a track record for a music file on the local filesystem.  Returns a
  FsTrack if possible, or nil if we couldn't read meta-data from the file."
  [^File file]
  (when (.accept (AudioFileFilter. false) file)
    (if-let [af (AudioFileIO/read file)]
      (if-let [tag (.getTag af)]
        (let [f (t/fn> [^FieldKey field :- FieldKey] (blank->nil (.getFirst tag field)))]
          (FsTrack.
            (f FieldKey/ARTIST)
            (f FieldKey/MUSICBRAINZ_ARTISTID)
            (f FieldKey/TITLE)
            (f FieldKey/MUSICBRAINZ_TRACK_ID)
            (f FieldKey/ALBUM)
            (f FieldKey/YEAR)
            (.getAbsolutePath file)))))))

(ann ^:no-check str->xml [String -> Any])
(defn str->xml
  "Parse given string into Clojure's tag/attrs/content format."
  [^String s]
  (with-open [^java.io.InputStream xml-in (io/input-stream (.getBytes s "UTF-8"))]
    (xml/parse xml-in)))

(ann clojure.core/*print-dup* boolean)
(ann ^:no-check ser [Any -> Any])
(defn ser
  "Write obj to filename in a way that Clojure's reader can read it back in.
  Returns obj."
  [filename obj]
  (with-open [w (io/writer filename)]
    (binding [*out* w
              *print-dup* true]
      (prn obj)))
  obj)

(ann ^:no-check deser [Any -> Any])
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

(ann ^:no-check get-tracks (Fn [String String -> (Seqable LovedTrack)]
                    [String String Number -> (Seqable LovedTrack)]))
(defn get-tracks
  "Get a lazy seq of user's loved tracks.  HTTP requests are made only as
  necessary as the list is consumed."
  ([user api-key] (get-tracks user api-key 1))
  ([user api-key page]
   (lazy-seq
     (let [resp (client/get (format LOVED_URL user api-key page LIMIT))
           zipped (zip/xml-zip (str->xml (:body resp)))
           total (Integer/parseInt (xml1-> zipped
                                           :lovedtracks
                                           (attr :totalPages)))
           tracks (map mk-loved-track (xml-> zipped :lovedtracks :track))]
       (when (<= page total)
         (concat tracks (get-tracks user api-key (inc page))))))))

(ann ^:no-check get-tracks-cached [String String -> (Seqable LovedTrack)])
(defn get-tracks-cached
  "If we have a cached copy of the loved tracks on disk, use them, otherwise
  forward call to get-tracks."
  [user api-key]
  (let [f (io/as-file (format ".%s_loved_tracks" user))]
    (if (.exists f)
      (deser f)
      ;; get all the tracks, cache 'em on disk, return 'em
      (ser (.getName f) (get-tracks user api-key)))))

(ann ^:no-check normalize [(Option String) -> (Option String)])
(defn normalize
  "Normalize s for use as a key in our track db indexes.  Lowercase everything,
  clean up spacing, squash various noise chars."
  [s]
  (when s
    (-> s
        (str/lower-case)
        (str/replace #"[/-]" " ")
        (str/replace #"['\"().]" "")
        (str/replace #"\s+" " ")
        (str/trim))))

(ann ^:no-check artist-keys [(Option String) -> (Seqable String)])
(defn artist-keys
  "Returns a seq of keys under which tracks from the given artist should be
  stored.  e.g. 'The Decemberists' should be stored under 'the decemberists',
  and 'decemberists'."
  [artist]
  (let [nrm (normalize artist)
        leading-the #"^the "]
    (if (and nrm (re-find leading-the nrm))
      [nrm (str/replace nrm leading-the "")] ; e.g. ['the band', 'band']
      [nrm])))

(ann ^:no-check add-track [clojure.lang.IPersistentMap FsTrack -> clojure.lang.IPersistentMap])
(defn add-track
  "Add an FsTrack to the given track db.  Return the new db."
  [db track]
  (let [upd (fn [db0 idx k]
              (update-in db0
                         [idx k]
                         #(conj (set %1) %2) track))
        ;; This code is lame: update the by-artist-name index, save the result,
        ;; then in that result update the by-track-name index.
        db1 (reduce #(upd %1 :by-artist-name %2)
                    db
                    (artist-keys (:artist-name track)))]
      (upd db1 :by-track-name (normalize (:track-name track)))))

(ann ^:no-check disable-jul! [-> nil])
(defn disable-jul!
  "Just turn off java.util.logging outright by removing all the handlers on the
  root logger. jaudiotagger spams tons of INFO logs by default, and there
  appeared to be a big performance impact as well."
  []
  (let [root (Logger/getLogger "")
        handlers (.getHandlers root)]
    (doseq [h handlers]
      (.removeHandler root h))))

(ann pr-m3u-line [FsTrack -> nil])
(defn pr-m3u-line
  "Print a m3u-compatible line of text to *out* for the given track."
  [t]
  (println (:path t)))

(ann score-fs-track [FsTrack -> Number])
(defn score-fs-track
  "Scoring function for candidate FsTracks. This is where I put ugly heuristics
  like 'penalize tracks named like x'. Should one day be made pluggable."
  [t]
  ;; if-let needed when accessing the FsTrack record since sometimes the stored
  ;; value for a key is nil, so (:foo t "default") doesn't work
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

(ann best-match [LovedTrack (Seqable FsTrack) -> FsTrack])
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

(ann print-misses [(Seqable LovedTrack) -> nil])
(defn print-misses
  "Print info about the loved tracks that we could not match."
  [coll]
  (let [grouped (->> coll
                     (filter :artist-name) ; elems must have track and artist names to be printed
                     (filter :track-name)
                     (group-by :artist-name)
                     (sort-by #(* -1 (count (second %)))))] ; show artists with most missing tracks first
    (doseq [[artist tracks] grouped]
      (println (str artist ":"))
      (doseq [t tracks]
        (println (str "  - " (:track-name t)))))))

(ann ^:no-check parse-cli [(t/Vec String) -> Any])
(defn- parse-cli [args]
  (cli args
       ["--api-key" "last.fm API key, if not set users $HOME/.lastfm_api_key"]
       ["--quiet" "Don't write informative stuff on stderr." :flag true]
       ["-h" "--help" "Show help and exit" :flag true]))

(ann -main [(t/Vec String) -> nil])
(defn -main [& args]
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

    ;; Find matches just by track name, then hand-off to 'best-match' to select
    ;; which of the matches (if any) should win.  If no match is found, record
    ;; it our "misses" list.
    (doseq [t loved-tracks]
      (let [matches (get-in track-db [:by-track-name (normalize (:track-name t))])]
        (if-let [best (best-match t matches)]
          (pr-m3u-line best)
          (swap! misses conj t))))

    (when-not (:quiet opts)
      (binding [*out* *err*]
        (let [ms @misses
              p (fn [& more] (println (apply format more)))]
          (print-misses ms)
          (p "")
          (p "%d missing loved tracks" (count ms))
          (p "%d unique track names in db" (count (get-in track-db [:by-track-name])))
          (p "%d artist name variations in db" (count (get-in track-db [:by-artist-name])))
          (p "%d loved tracks" (count loved-tracks))
          (p "%d fs tracks" (count fs-tracks)))))))

(comment

  (let [args ["--quiet" "--api-key" "12345" "/home/mark/music" "/data/other/music" ]
        [opts dirs _] (parse-cli args)]
    [opts dirs])

  (def t1 (FsTrack. "The Fluffheads" nil "Awesome Song" nil "studio album" "1977" "/asdf"))
  (def t2 (FsTrack. "The Fluffheads" nil "Awesome Song" nil "2000-02-23 Live in Hell" "2000" "/asdf"))

  (score-fs-track t1)
  (score-fs-track t2)

  (def db (-> {}
              (add-track t1)
              (add-track t2)))
  (pprint db)
  (artist-keys "The Fluffheads")
  (artist-keys "Opeth")

  (print-misses [1 2 3 4]) ; garbage, should print nothing
  (print-misses [{:artist-name "foo" :track-name "bar"}
                 3 4 9000
                 {:artist-name "aculy" :track-name "is dolan"}
                 42
                 {:artist-name "foo" :track-name "baz"}])

)

