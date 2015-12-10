# diameter-sim

A Clojure library for testing the Diameter protocol.

## Usage

Import the lib
```clojure
(use 'diameter.main)
(in-ns 'diameter.main)
```
Start a diameter server on 3869. Note, it currently blocks until the client connects, there for another repl must be started for the client. This step is only needed if there is not a server already running.


```clojure
=> (def server (start! :transport :tcp, :kind :server, :port 3869))
Binding server socket: ServerSocket[addr=0.0.0.0/0.0.0.0,localport=3869]
``` 

Start the client 
```clojure
=> (def client (start! :transport :tcp, :port 3869, :kind :client))
{:version 1, :flags #{}, :cmd 257, :app 0, :hbh 0, :e2e 1776805358, :fixed-avps [], :required-avps #{{:code 257, :flags #{:m}, :data 127.0.0.1} {:code 281, :flags #{}, :data Successful handshake} {:code 299, :flags #{:m}, :data 0} {:code 258, :flags #{:m}, :data 100} {:code 264, :flags #{:m}, :data dia2} {:code 267, :flags #{}, :data 4294967295} {:code 269, :flags #{}, :data MediationZone} {:code 278, :flags #{:m}, :data 1449595604} {:code 296, :flags #{:m}, :data dr} {:code 266, :flags #{:m}, :data 9008} {:code 268, :flags #{:m}, :data 2001}}}
Diameter session started
```
Define a diameter command
```clojure
(def-cmd a-cmd 11 0 0)
(def a-cmd 
  {:cmd a-cmd-def, :app 100, :flags #{:r} 
       :required-avps #{{:code origin-realm-avp-id, :flags #{:m}, :data "cl"}
                        {:code origin-host-avp-id, :flags #{:m}, :data "localhost"}
                        {:code destination-host-avp-id, :flags #{:m}, :data "dr"}
                        {:code auth-application-id-avp-id, :flags #{:m}, :data 100}
                        {:code session-id-avp-id, :flags #{}, :data "asdf"}}})
```

Send the command
```clojure
=> (send-cmd! a-cmd client)  
true
{:version 1, :flags #{}, :cmd 11, :app 100, :hbh 3, :e2e 1627088595, :fixed-avps [], :required-avps #{{:code 264, :flags #{:m}, :data dia2} {:code 296, :flags #{:m}, :data dr} {:code 268, :flags #{:m}, :data 2001}}}
                      
```
To see the default options that are used in the start! function
```clojure
=> (use 'clojure.repl)
=> (source default-options)
(defn default-options []
  {:transport :tcp
   :kind :client
   :host "localhost"
   :realm "cl"
   :app 100
   :cer cer-req-of
   :answer (fn [req options] (standard-answer-of req options))
   :print-fn println
   :wdr wd-req-of
   :req-chan  (chan 1000)
   :res-chan  (slide-chan)}
  )


```


## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
