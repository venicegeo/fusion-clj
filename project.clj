(defproject fusion-clj "0.1.3"
  :description "Fusing ephemeral kafka queues for dependent distibuted processes"
  :url "http://github.com/venicegeo/fusion-clj"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/data.json "0.2.6"]
                 [clj-kafka "0.3.4"]
                 [com.stuartsierra/dependency "0.2.0"]])
