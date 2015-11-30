(ns diameter.transport
  (:require [diameter_sim.codec :refer [Decode decode-cmd encode-cmd dbg ba->number]]
            [diameter_sim.base :refer [init-transport close-transport ip-address-of slide-chan avp-of
                                   origin-host-avp-id]]
            [clojure.core.async :refer [chan go >! <! take! put! go-loop alts! timeout onto-chan pipeline close! thread dropping-buffer]])
  (:import [java.net InetAddress ConnectException Socket ServerSocket SocketException]
           [java.io IOException OutputStream InputStream]))



(defn read-array [#^InputStream in size-or-array]
  (if (integer? size-or-array)
    (read in (byte-array size-or-array))
    (let [expected-size (count size-or-array)
          actual-size (.read in size-or-array)]
      (if (= actual-size -1)
        (throw (IOException. "EOF reached"))
       (do  
         (assert (= actual-size expected-size) (format "Expected size %s, actual size %s" expected-size actual-size))
         size-or-array)))))

(defn read-cmd [#^InputStream in]
  (let [ver-and-size (byte-array 4)]
    (fn []
      (read-array in ver-and-size)
      (let [size (ba->number ver-and-size 1 3)
            cmd (byte-array size)
            ofs-size (- size 4)]
        (System/arraycopy ver-and-size 0 cmd 0 4)
        (let [actual-size (.read in cmd 4 ofs-size)]
          (assert (= actual-size ofs-size) (format "Expected size %s, actual size %s" ofs-size actual-size))
        (vec cmd))))))

(defn read-loop [host #^Socket socket undecoded-chan connections]
  (thread
    (try 
      (let [in (.getInputStream socket)
            ver-and-size (byte-array 4)
            read-cmd-fn (read-cmd in)]
        (while (@connections host)
          (put! undecoded-chan (read-cmd-fn))))
      (catch IOException e
        (.printStackTrace e)
        ))))


(defn write-loop [host #^Socket socket encoded-chan connections]
  (thread
    (try 
      (let [out (.getOutputStream socket)]
        (while (@connections host)
          (let [ba (take! encoded-chan)]
            (.write #^OutputStream out #^bytes ba))))
      (catch Exception e
        (dbg e)
        (swap! connections dissoc host)))))

(defn init-connection [host socket undecoded-chan encoded-chan connections]
  (when (= (@connections host) socket) 
    (read-loop host socket undecoded-chan connections)
    (write-loop host socket encoded-chan connections)))

(defn swap-connections! [host socket connections]
  (swap! connections (fn [conns] (if (nil? (conns host)) (assoc conns host socket) conns ))))

(defn connect? [connections]
  (= (-> connections deref :state) :connect))

(defn init-client-connections [peer-table connections]
  (thread 
    (while (connect? connections) 
      (doseq [p peer-table]
        (try 
          (let [{:keys [port host undecoded-chan encoded-chan send-cer]} (val p)
                c (@connections host)]
            (when (and send-cer (nil? c))
              (let [socket (Socket. ^String host ^int port)]
                (swap-connections! host socket connections)
                (init-connection host socket undecoded-chan encoded-chan connections)
                )
              ))
         (catch java.net.ConnectException e
           (dbg e))))
      (Thread/sleep 3000))))


(defmethod init-transport :tcp [config]
  )


(defmethod ip-address-of :tcp [config] (.getHostAddress (InetAddress/getByName (:host config))))


(defn send-req [cl req]
  (put! (:in-chan cl) req))

;;================== for testing ===========================================================


(require '[diameter_sim.codec :refer [def-cmd]])
(require '[diameter_sim.base :refer [origin-host-avp-id
                                 origin-realm-avp-id
                                 auth-application-id-avp-id
                                 destination-host-avp-id
                                 destination-realm-avp-id
                                 session-id-avp-id
                                 ]])

(comment
  (defn start-peer1 []
    (def-cmd a-cmd 11 0 0)
    (start {:host        "dia1",
            :realm       "r1",
            :in-chan     (chan 1000)
            :out-chan    (chan 1000)
            :user-chan   (chan 10)
            :peer-table  (table-of :host [{:in-chan-client (chan 1000), :out-chan (chan 1000), :undecoded-chan (chan 1000), :encoded-chan (chan 1000), :host "dia2", :port 3869,}
                                          ])
            :realm-table (table-of :realm [{:realm "r2", :app-ids #{100}, :hosts ["dia2"], :strategy :failover, :action :asdf}])
            }))


  (defn start-peer2 []
    (def-cmd a-cmd 11 0 0)
    (start {:in-chan     (chan 1000), :out-chan (chan 1000), :user-chan (chan 1000)
            :host        "dia2", :realm "r2", :port 3869,
            :peer-table  (table-of :host [{, :undecoded-chan (chan 1000), :encoded-chan (chan 1000) :in-chan-client (chan 1000), :out-chan (chan 1000), :host "dia1", :port 3868, :send-cer false}])
            :realm-table (table-of :realm [{:realm "r1", :app-ids #{100}, :hosts ["dia1"], :strategy :failover, :action :asdf}])}))

  )


(comment
  (def-cmd a-cmd 11 0 0)

  (send-req p
            {:cmd           a-cmd-def, :app 100, :flags #{:r}
             :required-avps #{{:code origin-realm-avp-id, :flags #{:m}, :data "dr"}
                              {:code origin-host-avp-id, :flags #{:m}, :data "dia1"}
                              {:code destination-host-avp-id, :flags #{}, :data "dia2"}
                              {:code destination-realm-avp-id, :flags #{:m}, :data "dr"}
                              {:code auth-application-id-avp-id, :flags #{:m}, :data 100}
                              {:code session-id-avp-id, :flags #{}, :data "asdf"}}})
  )

