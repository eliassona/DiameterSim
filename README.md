# diameter-sim

A Clojure library for testing the protocol Diameter.

Note: UNDER CONSTRUCTION

## Usage

```clojure
Import the lib
(use 'diameter.main)
(in-ns 'diameter.main)
```

Start the simulator (assumes a diameter server running on localhost:3869)
```clojure
(def options (start! :transport :tcp))
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
(>!! (:req-chan options) a-cmd)
```

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
