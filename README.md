# fusion-clj

Suppose a Kafka consumer needs to dispatch some processing to other distributed consumers when it receives a message. Also suppose that Consumer must wait for those additional long-running processes to complete before moving on. Perhaps you do some polling using a REST API, or you could create ephemeral Kafka topics that only one consumer listens to and on which only one message would ever arrive. This is what fusion-clj seeks to accomplish: using ephemeral topics to mimic callback behaviour for long-running distributed processes.

## Purpose

When building a system of microservices where data is passed between then individual components via Kafka, a scenario can arise where callback-like behaviour is desired; especially so when services have a hierarchical relationship. One can also model those dependent relationships in the form of a directed acylic graph; expressing a processing chain of multiple services which depend on the output of other services.

Kafka allows us to offload potentially complex or expensive data processing operations to independent services in a "fire and forget" manner. In some scenarios, however, we may want to know that a particular message has been processed and have access to it's result. One could implement this via a REST service where a client sends data via POST/PUT and then polls a GET endpoint until the service returns the processed data. It's here that the case for creating an arbitrary, ephemeral topic on which a single message will ever arrive and from which a single consumer will ever read. This "return-topic" key is included in the produced message, the producing service then creates a consumer to read from the "return-topic." The service consuming the produced message will process that message and put it's result on the "return-topic." At this point the original producing service will receive the message on that topic and have access to it's result, potentially passing that result on to some other dependent service as expressed in the dependency graph.

## Overview

fusion-clj provides two components for use in the above scenario: a `Pipeline` and a `Reactor`.

### Pipeline

The pipeline acts as a gatekeeper to the rest of the services. At it's core it is a Kafka producer that delegates work to the appropriate top-level services or topics. It is intended to live on some data producing application like an API. It has the option of waiting for a message to arrive on an ephemeral topic to communicate to a client or some other service that the processing pipeline has completed for a specific message and/or responding with the result of the pipeline.

### Reactor

The reactor acts as a consumer of data from a pipeline or other reactors. At it's core it is a Kafka consumer that processes messages from a persistent topic and optionally sends messages to child or dependent reactors, waiting for them to respond in turn. When it is done processing it checks for a `:return-topic` key in the originally received message and, if present, puts it's result on that topic before putting the result on an optionally supplied channel. A reactor always puts the result of it's processed message on a channel whether or not a `:return-topic` is provided as long as the channel is provided. If a channel and a `:return-topic` are not provided, does nothing, assuming you have handled it in the supplied `proc-fn`.

## Usage

### Pipeline

```
(require '[fusion-clj.pipeline :as p])
```

TODO


### Reactor

```
(:require [fusion-clj.reactor :as r]
          [clojure.core.async :refer [chan])

(defn some-fn [...]
  (let [my-chan (chan)
        reactor-fn (r/reactor deps-fn proc-fn)
        elements (r/elements consumer-config "my-main-topic" 
                             producer-config zk-host my-chan)]
    ;; later...
    (reactor-fn elements) ; returns immediately (runs on separate OS thread)
    ;; read from my-chan
    ))
```

The `reactor` function takes two arguments, a `deps-fn` and a `proc-fn`, and returns a function which accepts the result of calling the `elements` function covered below.

#### deps-fn

The `deps-fn` should be a function which accepts a Kafka message in the form of a map:

```
{:topic "some-topic"
 :offset 42
 :partition 1
 :key "some-key"
 :value ..clojure-data-structure..}
```

It should return a map of dependencies (deps-map), if any, of the form:

```
{:one {:topic "add"
       :args [1 2 3]}
 :two {:topic "subtract"
       :args [2]
       :arg-in-fn cons
       :deps [:one]}
 ...
}       
```

This tells the reactor what dependent processes must be executed prior to executing the `proc-fn`. Given a map of this form the reactor will generate a directed acyclic graph data structure and derive a sorted topology from that graph. In the example above, assume we have some simple "math" services. The reactor will derive that `:two` depends on `:one`. It will send a message to the `add` topic with `[1 2 3]` under the `:data` key and a `:return-topic "some-uuid"`:

```
{:return-topic "some-uuid"
 :data [1 2 3}}
```

It will then create a consumer that reads from `return-topic`. When the response message arrives it will then process `:two` and the result of `:one` be injected using the `:arg-in-fn` (optional, defaults to `conj`) into `:two`'s `:args`. The message sent to the `subtract` topic will then look like this:

```
{:return-topic "some-uuid2"
 :data [6 2]}
```

When all dependencies have been processed. The original message and the deps-map with a `:result` key for each dep in the following form with be passed to `proc-fn`:

```
{:one {:topic "add"
       :args [1 2 3]
       :result 6}
 :two {:topic "subtract"
       :args [2]
       :arg-in-fn cons
       :deps [:one]
       :result 4}
 ...
}       
```

#### proc-fn

The proc-fn should be function which accepts one required argument, a kafka message in the form of a map, and one optional argument that is a map of the form 

```
{:some-step1 ..result..
 :some-step2 ..result..} 
```

which are the results of each of it's dependent services (if there were any).

The result of this function will be put onto a `:response-topic` if one was included in the original kafka message. If a channel was supplied in `elements` (covered below), then the result will be put on that channel (regardless of a specified `:response-topic`). If a channel is not provided, does nothing with the result. If a `:response-topic` and a channel are not available, does nothing, assuming you did something with it in `proc-fn`.
 
#### Elements

The `elements` function returns a map of some resources that are passed to the function returned by the `reactor` function. It takes the following arguments:

`consumer-config` - A map of string keys and string values that is used to configure a Kafka consumer. See the [Kafka docs](http://kafka.apache.org/documentation.html#consumerconfigs) for options.

`topic` - The main topic (string) from which the reactor will consume messages.

`producer-config` - A map of string keys and string values that will be used to configure a Kafka producer. See the [Kafka docs](http://kafka.apache.org/documentation.html#producerconfigs) for options.

`zk` - A string of the form `[host]:[port]` for connecting to the Zookeeper service that Kafka uses. This is used to create and delete ephemeral topics.

`channel` - (Optional) A core.async channel on which the results of processing a message will be put.

#### Starting The Reactor

Calling the function returned by `reactor` with the resulting map returned by `elements` will spawn a separate thread where a Consumer will indefinitely read from the main topic supplied in the `elements` function. Returns immediately.

#### Stopping The Reactor

It's recommended to hold a reference to the resulting map returned from `elements`, so that the reactor can be cleanly shut down.

```
(r/shutdown-reactor elements)
```

Closes the channel (if provided), shuts down the main consumer, and closes the shared producer.
    
## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
