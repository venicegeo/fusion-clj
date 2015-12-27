(ns fusion-clj.reactor
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :refer [reader]]
            [clj-kafka.new.producer :as p]
            [clj-kafka.consumer.zk :as c]
            [clj-kafka.admin :as a]
            [com.stuartsierra.dependency :as d]
            [fusion-clj.common :as com])
  (:import [java.util UUID]))

(defn- process
  "Main processing function of a reactor."
  [consumer-config producer zk deps-fn proc-fn msg]
  (let [clj-msg (com/msg->clj msg) ; Parse json msg value to clojure map
        ;; deps-fn should return a nested map structure of the form:
        ;; {:step1 {:topic "multiply"
        ;;          :args [3 2]}
        ;;  :step2 {:topic "add"
        ;;          :args [1]
        ;;          :arg-in-fn conj
        ;;          :deps [:step1]}...}
        deps-map (deps-fn clj-msg)
        results (com/process-deps deps-map producer consumer-config zk)
        ;; Process the original message and the deps results
        result (proc-fn clj-msg results)]
    ;; If there's a :return-topic, send the result of proc-fn to that topic
    (if-let [ret-topic (-> clj-msg :value :return-topic)]
      @(p/send producer (p/record ret-topic (:topic clj-msg) (com/clj->msg result)))
      ;; Otherwise we return the result
      result)))

(defn fuse!
  "Given a reactor and elements, simply calls reactor with elements. Is
  this really necessary?"
  [reactor elements]
  (reactor elements))

(defn shutdown-reactor
  "Given the map returned from `elements`, cleanly shuts down the reactor."
  [{:keys [consumer producer channel] :as elements}]
  (do (when channel
        (async/close! channel))
      (c/shutdown consumer)
      (.close producer)))

(defn elements
  "Given a kafka consumer configuration map, a main topic to consume
  messages on, a kafka producer configuration map, a Zookeeper
  connection string, and a core.async channel, returns a map of
  `elements` which are passed to a reactor fn."
  [consumer-config topic producer-config zk channel]
  (let [consumer (c/consumer consumer-config)
        producer (p/producer producer-config
                             (p/string-serializer)
                             (p/byte-array-serializer))]
    {:consumer consumer
     :stream (-> consumer
                 (c/create-message-stream topic)) ;; Create our topic stream
     :consumer-config consumer-config
     :producer producer
     :zk zk
     :channel channel}))

(defn reactor
  "Given a dependency function (deps-fn) and a processing
  function (proc-fn) returns a function which accepts an elements
  map. When invoked, processes messages from a kafka consumer, calls
  deps-fn with each message to get an array of dependent topics,
  processes those deps, and finally calls proc-fn with the original
  message and a seq of messages returned from the dependent services. If
  the original message has a :return-topic key, puts the result of
  proc-fn on that topic and the resulting map gets put on the channel, if
  not, puts the result on the channel returned by the invoked reactor."
  [deps-fn proc-fn]
  (fn [elements]
    (let [{:keys [stream producer consumer-config zk channel]} elements]
      (async/thread
        (doseq [msg (c/stream-seq stream)]
          (async/thread (async/>!! channel (process consumer-config
                                                    producer zk
                                                    deps-fn proc-fn msg))))))))
