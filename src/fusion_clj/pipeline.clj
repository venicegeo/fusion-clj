(ns fusion-clj.pipeline
  (:require [clojure.core.async :as async]
            [clj-kafka.new.producer :as p]
            [clj-kafka.consumer.zk :as c]
            [fusion-clj.common :as com]))


(defn- send-and-wait
  "Sends message to topic via producer then waits for a message on the
  topic returned from `process-dep`. Returns the KafkaMessage received
  on the return-topic."
  [producer topic message consumer-config zk]
  (let [response-topic (com/process-dep {:topic topic
                                         :args message} producer zk)]
    (com/await-msg-return response-topic consumer-config zk)))

(defn pipeline
  "Takes a producer-config map and optional consumer-config, zk, and
  channel keys. Returns a pipeline function that accepts a topic,
  message, and an optional boolean flag that indicates whethere the
  pipeline should wait for a response from a consumer of `topic`. Must
  provide a consumer-config map, a zk host string, and the wait? flag
  must be truthy when invoked for the pipeline to listen for response
  messages. If the above is satisfied and a core async channel is also
  provided, puts the resulting KafkaMessage on that channel; otherwise
  returns the KafkaMessage. If the wait? flag is falsey, the
  consumer-config map or a zk host string is not provided, simply puts
  message on topic and returns that Future.

  (pipeline {:bootstrap-servers ...} :consumer-config {...}
                                     :zk localhost:2181
                                     :channel my-chan)"
  [producer-config & {:keys [consumer-config zk channel] :as p-config}]
  (let [producer (p/producer producer-config
                             (p/string-serializer)
                             (p/byte-array-serializer))]
    (fn [topic message & [wait?]]
      (if (and consumer-config zk wait?)
        (let [result (send-and-wait producer topic message consumer-config zk)]
          (if channel
            (async/>!! channel result)
            result))
        (p/send producer (p/record topic (com/clj->msg message)))))))
