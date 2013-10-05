(defproject find-loved "1.0.0-SNAPSHOT"
  :description "Find your loved last.fm tracks on your local filesystem."
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.typed "0.2.13"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.clojure/data.zip "0.1.0"]
                 [org/jaudiotagger "2.0.3"]
                 [clj-http "0.3.2"]]
  :plugins [[lein-typed "0.3.0"]]
  :core.typed {:check [find-loved.core]}
  :main find-loved.core)

