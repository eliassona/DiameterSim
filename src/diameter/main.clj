(ns diameter.main
  (:use [clojure.pprint])
  (:require [diameter.codec :refer [def-cmd cmd-flag-map]]
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
            [clojure.core.async :refer [>!! chan <!!]]
            [clojure.test :refer [run-tests is deftest]]
   [diameter.transport :refer []]))

;;A test command
(def-cmd a-cmd 11 0 0)
(def a-cmd 
  {:cmd a-cmd-def, :app 100, :flags #{:r :p} 
       :required-avps #{{:code origin-realm-avp-id, :flags #{:m}, :data "cl"}
                        {:code origin-host-avp-id, :flags #{:m}, :data "localhost"}
                        ;{:code destination-host-avp-id, :flags #{:m}, :data "dia1"}
                        {:code destination-realm-avp-id, :flags #{:m}, :data "dr"}
                        {:code auth-application-id-avp-id, :flags #{:m}, :data 100}
                        ;{:code vendor-id-avp-id, :flags #{} :data 9008}
                        #_{:code session-id-avp-id, :flags #{}, :data "asdf"}}})


(defn answer-code [cmd]
  (if (a-cmda? cmd) 
    (:data (find-avp cmd :required-avps result-code-avp-id))
    -1))


(deftest test-header-bit
  (let [res-chan (chan)
        options (start! :transport :tcp, :res-chan res-chan, :print-fn (fn [_]))]
    (dotimes [i 1]
      (when (= (mod i 1000) 0)
        (println (str "iteration: " i)))
      
      (doseq [[flags res-code] [[#{:r} 2001], 
                                [#{:r :e} 3008], 
                                [#{:r :p :e} 3008], 
                                [#{:r :p} 2001]
                                [#{:r :t} 2001]
                                [#{:r :e :t} 3008]
                                [#{:r :p :e :t} 3008]
                                ;[#{:r :reserved-4} 3008]
                                [#{:r :reserved-4 :reserved-3} 3008]
                                [#{:r :reserved-3} 3008]
                                [#{:r :reserved-2} 3008]
                                [#{:r :reserved-1} 3008]]]
        (send-cmd! (assoc a-cmd :flags flags) options)
        (is (= (answer-code (<!! res-chan)) res-code) (format "flags=%s" flags)))
      )
    (close-session! options)
    ))



(comment
  (def options (start! :transport :tcp))
  (send-cmd! a-cmd options))




;{decoded={Additional_AVPs=null, Vendor_Id=9008, Inband_Security_Id=[0], Origin_Realm=cl, Is_Proxiable=false, Host_IP_Address=[127.0.0.1], Is_Request=true, Is_Error=false, 
;          Origin_State_Id=0, EndToEndIdentifier=-1765801983, Firmware_Revision=-1, Product_Name=Clojure Client, Origin_Host=localhost, Vendor_Specific_Application_Id=null, 
;          Supported_Vendor_Id=null, Is_Retransmit=false, className=D_Capabilities_Exchange_Request, Auth_Application_Id=[100], Acct_Application_Id=[4]}}

;{targeted={Additional_AVPs=null, Vendor_Id=9008, Inband_Security_Id=[0], Origin_Realm=dr, Is_Proxiable=false, Host_IP_Address=[127.0.0.1], Error_Message=Successful handshake, 
;           Result_Code=2001, Is_Request=false, Is_Error=false, Origin_State_Id=1450687934, EndToEndIdentifier=-1765801983, Firmware_Revision=-1, Product_Name=MediationZone, 
;           Origin_Host=dia2, Vendor_Specific_Application_Id=null, Supported_Vendor_Id=null, Is_Retransmit=false, className=D_Capabilities_Exchange_Answer, Auth_Application_Id=[100], 
;           Failed_AVP=null, Acct_Application_Id=null}}
