(ns fusion-clj.core
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :refer [reader]]
            [clj-kafka.new.producer :as p]
            [clj-kafka.consumer.zk :as c]
            [clj-kafka.admin :as a])
  (:import [java.util UUID]))

(defn- msg->clj
  "Converts a KafkaMessage whose .value is byte array value of a json
  encoded string into a clojure map."
  [msg]
  {:topic (.topic msg)
   :offset (.offset msg)
   :partition (.partition msg)
   :key (.key msg)
   :value (json/read-str (String. (.value msg)))})

(defn- clj->msg
  "Converts a clojure map to a byte-array of a json encoded string."
  [clj]
  (.getBytes (json/write-str clj)))

(defn- create-ephemeral-topic!
  "Creates a topic in Zookeeper."
  [zk topic]
  (with-open [zookeeper (a/zk-client zk)]
    (when-not (a/topic-exists? zookeeper topic)
      (a/create-topic zookeeper topic))))

(defn- delete-ephemeral-topic!
  "Deletes a topic in Zookeeper."
  [zk topic]
  (with-open [zookeeper (a/zk-client zk)]
    (when (a/topic-exists? zookeeper topic)
      (a/delete-topic zookeeper topic))))

(defn- await-dep-return
  "Wait for the single message to appear on the topic (response-topic)
  and return it as a clojure data structure."
  [consumer-config zk topic]
  (let [consumer (c/consumer consumer-config) ; create a new consumer
        ;; take 1 will block until it gets the reply from the dep service
        response (take 1 (c/messages consumer topic))]
    (do (c/shutdown consumer) ; close this consumer since we got it's only message
        ;; delete the topic since it's served it's purpose
        (delete-ephemeral-topic! zk topic)
        ;; return the parsed message
        (msg->clj response))))

(defn- produce-dep-msg
  "Send the message to the dep (topic), assoc the :response-topic into
  the message so the dep service knows where to reply."
  [clj-msg producer topic response-topic]
  (let [clj-msg* (assoc-in clj-msg [:value :response-topic] response-topic)]
    ;; Deref the future immediately so we know it was sent
    @(p/send producer (p/record topic topic (clj->msg (:value clj-msg*))))))

(defn- process-dep
  "Process an individual dependency."
  [dep clj-msg producer zk]
  (let [;; Generate a random response topic string
        res-topic (str (UUID/randomUUID))]
    (do (create-ephemeral-topic! zk res-topic) ; create the topic (prevent race condition)
        (produce-dep-msg clj-msg producer dep res-topic) ; send the message to the dep topic
        ;; return the response topic
        res-topic)))

(defn- process
  "Main processing function of a reactor.
  TODO: Allow deps to be a DAG and walk the graph."
  [consumer-config producer zk deps-fn proc-fn msg]
  (let [clj-msg (msg->clj msg) ; Parse json msg to clojure map
        deps (deps-fn clj-msg) ; Get a seq of dependent topics
        ;; Process each dep and get their return topics
        res-topics (map #(process-dep % clj-msg producer zk) deps)
        ;; Listen for the response for each return topic
        deps-results (map #(await-dep-return consumer-config zk %) res-topics)
        ;; Process the original message and the deps results
        result (proc-fn clj-msg deps-results)]
    ;; If there's a :return-topic, send the result of proc-fn to that topic
    (if-let [ret-topic (-> clj-msg :value :return-topic)]
      @(p/send producer (p/record ret-topic (:topic clj-msg) (clj->msg result)))
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
  (do (async/close! channel)
      (c/shutdown consumer)
      (.close producer)))

(defn elements
  "Given a kafka consumer configuration map, a main topic to consume
  messages on, a kafka producer configuration map, a Zookeeper
  connection string, and a core.async channel, returns a map of
  `elements` which are passed to a reactor fn."
  [consumer-config topic producer-config zk channel]
  (let [consumer (c/consumer consumer-config)
        prodcuer (p/producer producer-config
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
      (doseq [msg (c/stream-seq stream)]
        (async/thread (async/>!! channel (process consumer-config
                                                  producer zk
                                                  deps-fn proc-fn msg)))))))
