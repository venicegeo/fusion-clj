(defproject fusion-clj "0.1.0-SNAPSHOT"
  :description "Fusing ephemeral kafka queues for dependent distibuted processes"
  :url "http://github.com/venicegeo/fusion-clj"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/data.json "0.2.6"]
                 [clj-kafka "0.3.4"]
                 [com.stuartsierra/dependency "0.2.0"]])
