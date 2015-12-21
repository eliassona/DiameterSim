(ns diameter.basetest
  (:require
    [clojure.core.async :refer [chan >! <! >!! <!! put!]]
    [diameter.codec :refer [def-cmd dbg encode-cmd prettyfy]]
    [clojure.test :refer [deftest is run-tests]])
  (:use [diameter.base]
    ))





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

(defn do-cer [print-chan]
  (<!! print-chan)
  (<!! print-chan)
  (<!! print-chan))


(deftest verify-local-dest-host
  (let [print-chan (chan)
        print-fn #(put! print-chan %)
        client (start! :transport :local, :print-fn print-fn)]
    (do-cer print-chan)
    (send-cmd! (update a-cmd :required-avps #(conj % {:code destination-host-avp-id :flags #{:m}, :data "localhost"})) client)
    (is (= {:cmd 11, :flags #{:r} :location :req-chan} (-> print-chan <!! (select-keys [:cmd :flags :location])))) ;the request is sent
    (is (= {:cmd 11, :flags #{} :location :raw-in-chan} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer and this is the answer
    (is (= {:cmd 11, :flags #{:r} :location :req-route} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer this is its request
    (is (= {:cmd 11, :flags #{} :location :cmd-route} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer and this is the answer
    (is (= {:cmd 11, :flags #{}, :location :local} (-> print-chan <!! (select-keys [:cmd :flags :location])))) ;local processing
    (is (= "answer" (<!! print-chan)))
    
    ))




(deftest verify-non-existing-dest-host-non-proxiable
  (let [print-chan (chan)
        print-fn #(put! print-chan %)
        client (start! :transport :local, :print-fn print-fn)]
    (do-cer print-chan)
    (send-cmd! (update a-cmd :required-avps #(conj % {:code destination-host-avp-id :flags #{:m}, :data "unknown"})) client)
    (is (= {:cmd 11, :flags #{:r} :location :req-chan} (-> print-chan <!! (select-keys [:cmd :flags :location])))) ;the request is sent
    (is (= {:cmd 11, :flags #{} :location :raw-in-chan} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer and this is the answer
    (is (= {:cmd 11, :flags #{:r} :location :req-route} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer this is its request
    (is (= {:cmd 11, :flags #{} :location :cmd-route} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer and this is the answer
    (is (= {:cmd 11, :flags #{}, :location :local} (-> print-chan <!! (select-keys [:cmd :flags :location])))) ;local processing
    (is (= "answer" (<!! print-chan)))
    
    ))

(deftest verify-non-existing-dest-host-proxiable
  (let [print-chan (chan)
        print-fn #(put! print-chan %)
        client (start! :transport :local, :print-fn print-fn)]
    (do-cer print-chan)
    (send-cmd! (-> a-cmd 
                 (update :required-avps #(conj % {:code destination-host-avp-id :flags #{:m}, :data "unknown"}))
                 (update :flags conj :p)) client)
    (is (= {:cmd 11, :flags #{:r :p} :location :req-chan} (-> print-chan <!! (select-keys [:cmd :flags :location])))) ;the request is sent
    (is (= {:cmd 11, :flags #{:p} :location :raw-in-chan} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer and this is the answer
    (is (= {:cmd 11, :flags #{:r :p} :location :req-route} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer this is its request
    (is (= {:cmd 11, :flags #{:p} :location :cmd-route} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent an answer and this is the answer
    (is (= "unknown does not exist in peer-table" (-> print-chan <!!))) ;local processing
    ))


(defn send-raw-cmd! [cmd opts]
  (let [c (-> opts :connection :raw-in-chan)]
    (>!! c (encode-cmd (assoc cmd :e2e (create-e2e), :hbh 10)))))

(deftest verify-existing-remote-dest-host
  (let [print-chan (chan)
        print-fn #(put! print-chan %)
        dest-print-chan (chan)
        dest-print-fn #(put! dest-print-chan %)
        client (start! :transport :local, :print-fn print-fn, :peer-table {"dia1", (assoc (default-options) :host "dia1", :print-fn dest-print-fn, :transport :local)})]
    (do-cer print-chan)
    (send-raw-cmd! (-> a-cmd 
                     (update :required-avps #(conj % {:code destination-host-avp-id :flags #{:m}, :data "dia1"}))
                     (update :flags conj :p)) client)
    (do-cer dest-print-chan)
    (is (= {:cmd 11, :flags #{:r :p} :location :raw-in-chan} (-> print-chan <!! (select-keys [:cmd :flags :location]))))   ;the local server has sent a request and this is it
    (is (= {:cmd 11, :flags #{:r :p} :location :req-route} (-> print-chan <!! (select-keys [:cmd :flags :location])))) 
    (is (= {:cmd 11, :flags #{:r :p} :location :cmd-route} (-> print-chan <!! (select-keys [:cmd :flags :location])))) 
;    (is (= {:cmd 11, :flags #{:r :p}} (-> dest-print-chan <!! (select-keys [:cmd :flags])))) ;the request is sent
;    (is (= {:cmd 11, :flags #{:p}} (-> dest-print-chan <!! (select-keys [:cmd :flags])))) ;the answer
    (println (<!! dest-print-chan))
;    (println (<!! dest-print-chan))
    ))

