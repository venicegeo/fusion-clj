(ns fusion-clj.common
  (:require [clojure.data.json :as json]
            [clj-kafka.new.producer :as p]
            [clj-kafka.consumer.zk :as c]
            [clj-kafka.admin :as a]
            [com.stuartsierra.dependency :as d]))

(defn msg->clj
  "Converts a KafkaMessage whose .value is byte array value of a json
  encoded string into a clojure map."
  [msg]
  {:topic (.topic msg)
   :offset (.offset msg)
   :partition (.partition msg)
   :key (.key msg)
   :value (json/read-str (String. (.value msg)))})

(defn clj->msg
  "Converts a clojure map to a byte-array of a json encoded string."
  [clj]
  (.getBytes (json/write-str clj)))

(defn create-ephemeral-topic!
  "Creates a topic in Zookeeper."
  [zk topic]
  (with-open [zookeeper (a/zk-client zk)]
    (when-not (a/topic-exists? zookeeper topic)
      (a/create-topic zookeeper topic))))

(defn delete-ephemeral-topic!
  "Deletes a topic in Zookeeper."
  [zk topic]
  (with-open [zookeeper (a/zk-client zk)]
    (when (a/topic-exists? zookeeper topic)
      (a/delete-topic zookeeper topic))))

(defn await-msg-return
  "Wait for the single message to appear on the topic (response-topic)
  and return it as a clojure data structure."
  [topic consumer-config zk]
  (let [consumer (c/consumer consumer-config) ; create a new consumer
        ;; take 1 will block until it gets the reply from the dep service
        response (take 1 (c/messages consumer topic))]
    (do (c/shutdown consumer) ; close this consumer since we got it's only message
        ;; delete the topic since it's served it's purpose
        (delete-ephemeral-topic! zk topic)
        ;; return the parsed message
        (msg->clj response))))

(defn produce-dep-msg
  "Send the message to the dep (topic), assoc the :response-topic into
  the message so the dep service knows where to reply."
  [{:keys [topic args]} producer response-topic]
  (let [clj-msg (assoc {:data args} :response-topic response-topic)]
    (p/send producer (p/record topic topic (clj->msg clj-msg)))))

(defn process-dep
  "Process an individual dependency."
  [dep producer zk]
  (let [;; Generate a random response topic string
        res-topic (str (UUID/randomUUID))]
    (do (create-ephemeral-topic! zk res-topic) ; create the topic (prevent race condition)
        (produce-dep-msg dep producer res-topic) ; send the message to the dep topic
        ;; return the response topic
        res-topic)))

(defn process-deps
  "Given a dependency map, a producer, a consumer-config map, and a
  zookeeper host string create a directed acyclic graph using the
  provided dependency map. Gets the topological sort of the nodes in the
  graph, and executes them in order; injecting the results of
  dependencies into the args of dependent tasks. Returns a map whose
  keys are the keys of the deps-map whose values are the
  results of sending it's corresponding args to topic."
  [deps-map producer consumer-config zk]
  (let [graph (reduce-kv (fn [g k v]
                           (if-let [k-deps (:deps v)]
                             (reduce (fn [g* d]
                                       (d/depend g* k d)) g k-deps)
                             g)) (d/graph) deps-map) ; build up our dep graph
                                        ; get the topological sort of the graph
        topo (d/topo-sort dep-graph)]
    (reduce (fn [a d]
              (let [args (-> deps-map d :args)
                    arg-in-fn (or (-> deps-map d :arg-in-fn) conj)
                    args* (if-let [ds (d/immediate-dependencies graph d)]
                            (reduce (fn [a* d*]
                                      (arg-in-fn a* (-> a d* :result))) args ds)
                            args)]
                (assoc-in a [d :result] (-> (assoc (d deps-map) :args args*) ; "replace" the original args with those that include dependency results
                                            (process-dep producer zk) ; process the dependency
                                            (await-msg-return consumer-config zk) ; wait for a message on the :return-topic
                                            :value))))
            deps-map topo)))
