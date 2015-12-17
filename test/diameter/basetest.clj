(ns diameter.basetest
  (:require
    [clojure.core.async :refer [chan >! <! >!! <!! put!]]
    [diameter.codec :refer [def-cmd dbg]]
    [diameter.base :refer [origin-realm-avp-id origin-host-avp-id
                                   destination-host-avp-id
                                   destination-realm-avp-id
                                   auth-application-id-avp-id
                                   vendor-specific-application-id-avp-id
                                   vendor-id-avp-id
                                   result-code-avp-id
                                   session-id-avp-id
                                   start!
                                   send-cmd!
                                   find-avp
                                   close-session!
                                   default-options
                                   dp-req-of
                                   cer-req-of]]
    [clojure.test :refer [deftest is run-tests]]))





(deftest verify-cer
  (let [print-chan (chan)
        print-fn #(put! print-chan %)
        client (start! :transport :local, :print-fn print-fn)]
    (is (= {:cmd 257, :flags #{:r}} (-> print-chan <!! (select-keys [:cmd :flags]))))
    (is (= {:cmd 257, :flags #{}} (-> print-chan <!! (select-keys [:cmd :flags]))))
    (is (= "Diameter session started" (<!! print-chan)))
    ))


(def-cmd a-cmd 11 0 0)
(def a-cmd 
  {:cmd a-cmd-def, :app 100, :flags #{:r} 
       :required-avps #{{:code origin-realm-avp-id, :flags #{:m}, :data "cl"}
                        {:code origin-host-avp-id, :flags #{:m}, :data "localhost"}
                        ;{:code destination-host-avp-id, :flags #{:m}, :data "dia1"}
                        ;{:code destination-realm-avp-id, :flags #{:m}, :data "dr"}
                        {:code auth-application-id-avp-id, :flags #{:m}, :data 100}
                        ;{:code vendor-id-avp-id, :flags #{} :data 9008}
                        #_{:code session-id-avp-id, :flags #{}, :data "asdf"}}})


(deftest verify-local-dest-host
  (let [print-chan (chan)
        print-fn #(put! print-chan %)
        client (start! :transport :local, :print-fn print-fn)]
    (<!! print-chan)
    (<!! print-chan)
    (<!! print-chan)
    (send-cmd! (update a-cmd :required-avps #(conj % {:code destination-host-avp-id :flags #{:m}, :data "localhost"})) client)
    (is (= {:cmd 11, :flags #{:r}} (-> print-chan <!! (select-keys [:cmd :flags])))) ;the request is sent
    (is (= {:cmd 11, :flags #{}} (-> print-chan <!! (select-keys [:cmd :flags]))))   ;the local server has sent an answer and it has been paired with the request
    (is (= {:cmd 11, :flags #{}, :dest :local} (-> print-chan <!! (select-keys [:cmd :flags :dest])))) ;local processing
    (is (= "answer" (<!! print-chan)))
    
    ))