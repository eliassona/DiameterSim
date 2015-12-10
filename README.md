# diameter-sim

A Clojure library for testing the Diameter protocol.

## Usage

Import the lib
```clojure
(use 'diameter.main)
(in-ns 'diameter.main)
```
Start a diameter server on 3869. Note, it currently blocks until the client connects, there for another repl must be started for the client.

```clojure
=> (def server (start! :transport :tcp, :kind :server, :port 3869))
Binding server socket: ServerSocket[addr=0.0.0.0/0.0.0.0,localport=3869]
``` 

Start the client 
```clojure
=> (def options (start! :transport :tcp, :port 3869, :kind :client))
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
=> (send-cmd! a-cmd options)  
true
{:version 1, :flags #{}, :cmd 11, :app 100, :hbh 3, :e2e 1627088595, :fixed-avps [], :required-avps #{{:code 264, :flags #{:m}, :data dia2} {:code 296, :flags #{:m}, :data dr} {:code 268, :flags #{:m}, :data 2001}}}
                      
```
To see the default options that are used in the start! function
```clojure
=> (pprint (default-options))
{:transport :tcp,
 :realm "cl",
 :send-wdr true,
 :host "localhost",
 :req-chan
 #<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@5f4215b6>,
 :res-chan
 #<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@5ba616b0>,
 :kind :client,
 :answer
 #<base$default_options$fn__13970 diameter.base$default_options$fn__13970@782f4407>,
 :app 100,
 :cer #<base$cer_req_of diameter.base$cer_req_of@2b6cf1e2>,
 :print-fn #<core$println clojure.core$println@130afb22>,
 :send-wda true,
 :wdr #<base$wd_req_of diameter.base$wd_req_of@438dec32>}
```


## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
