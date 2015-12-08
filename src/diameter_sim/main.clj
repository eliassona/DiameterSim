(ns diameter_sim.main
  (:require [diameter_sim.codec :refer [def-cmd]]
            [diameter_sim.base :refer [origin-realm-avp-id origin-host-avp-id
                                       destination-host-avp-id auth-application-id-avp-id
                                       session-id-avp-id
                                       start!]]
            [clojure.core.async :refer [>!!]])
   (:use [diameter_sim.transport]))

(comment
  (def options (start! :transport :tcp))
  (def-cmd a-cmd 11 0 0)
  (def a-cmd 
    {:cmd a-cmd-def, :app 100, :flags #{:r} 
         :required-avps #{{:code origin-realm-avp-id, :flags #{:m}, :data "cl"}
                          {:code origin-host-avp-id, :flags #{:m}, :data "localhost"}
                          {:code destination-host-avp-id, :flags #{:m}, :data "dr"}
                          {:code auth-application-id-avp-id, :flags #{:m}, :data 100}
                          {:code session-id-avp-id, :flags #{}, :data "asdf"}}})
  (>!! (:req-chan options) a-cmd))
