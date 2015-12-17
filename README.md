# fusion-clj

Suppose a Kafka consumer needs to dispatch some processing to other distributed consumers when it receives a message. Also suppose that Consumer must wait for those additional long-running processes to complete before moving on. Perhaps you do some polling using a REST API, or you could create ephemeral Kafka topics that only one consumer listens to and on which only one message would ever arrive. This is what fusion-clj seeks to accomplish: using ephemeral topics to mimic callback behaviour for long-running distributed processes.

## Usage

FIXME

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
