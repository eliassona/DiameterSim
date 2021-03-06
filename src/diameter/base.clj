(ns diameter.base
  (:require [diameter.codec :refer [def-avp def-cmd encode-cmd map-of decode-cmd request? answer? proxiable? dbg]]
            [clojure.core.async :refer [chan go >! <! >!! <!! go-loop alts! timeout onto-chan pipeline close! sliding-buffer]]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(def-avp
  session-id 263 :utf-8-string
  origin-host 264 :diameter-identity
  origin-realm 296 :diameter-identity
  host-ip 257 :address
  supported-vendor-id 265 :unsigned32
  vendor-specific-application-id 260 :grouped
  vendor-id 266 :unsigned32
  product-name 269 :utf-8-string
  result-code 268 :unsigned32
  error-message 281 :utf-8-string
  auth-application-id 258 :unsigned32
  inband-security 299 :unsigned32
  firmware-revision 267 :unsigned32
  disconnect-cause 273 :unsigned32
  origin-state-id 278 :unsigned32
  failed-avp 279 :grouped
  destination-realm 283 :diameter-identity
  destination-host 293 :diameter-identity
  acct-application-id 259 :unsigned32
  disconnect-cause 273 :unsigned32
  )

(defn slide-chan []
  (chan (sliding-buffer 1)))

(def-cmd ce 257 0 0)                                        ;Capability Exchange
(def-cmd wd 280 0 0)                                        ;Watchdog
(def-cmd dp 282 0 0)                                        ;Disconnect

(defmulti connect :transport)
(defmulti bind :transport)
(defmulti disconnect :transport)
(defmulti ip-address-of :transport)

(defn find-avps [cmd type code]
  (filter #(= (:code %) code) (cmd type)))

(defn find-avp [cmd type code]
  (first (find-avps cmd type code)))

(defn avps-of [& avps]
  "Make avp maps, flags is by default #{:m}"
  (map (fn [[code data flags]] (let [flags (if flags flags #{:m})] (map-of code data flags))) avps))

(defn avp-of [cmd avp] (:data (find-avp cmd :required-avps avp)))

(def ^:const two-to-twenty (java.lang.Math/pow 2 20))

(def ^:const  current-start-time (bit-shift-left (bit-and (System/currentTimeMillis) 0xfff) 20))

(def e2e-value (atom current-start-time))

(defn create-e2e []
  (swap! e2e-value inc))

(defn cer-req-of [config]
  (let [{:keys [hbh host realm app]} config]
    {:cmd ce-def, :app 0, :hbh hbh, :e2e (create-e2e), :flags #{:r}
     :required-avps
          (into #{} (avps-of
                      [origin-host-avp-id host] [origin-realm-avp-id realm]
                      [host-ip-avp-id (ip-address-of config)] [vendor-id-avp-id 9008]
                      [product-name-avp-id "Clojure Client" #{}] [origin-state-id-avp-id 0]
                      [auth-application-id-avp-id 100]
                      [acct-application-id-avp-id 4]
                      [inband-security-avp-id 0]
                      [firmware-revision-avp-id 0xffffffff #{}]
                      ))}))

(defn cer-ans-of [req config]
  (let [{:keys [host realm]} config]
    {:version 1, :flags #{}, :cmd ce-def, :app 0, :hbh (:hbh req), :e2e (:e2e req)
     :required-avps
              (into #{} (avps-of
                          [host-ip-avp-id (ip-address-of config)] [error-message-avp-id "Successful handshake" #{}]
                          [inband-security-avp-id 0] [auth-application-id-avp-id 100]
                          [origin-host-avp-id host] [firmware-revision-avp-id 4294967295 #{}]
                          [product-name-avp-id "Clojure Server" #{}] [origin-realm-avp-id realm]
                          [origin-state-id-avp-id 1415866624] [vendor-id-avp-id 9009]
                          [result-code-avp-id 2001]))}))

(defn wd-req-of [{:keys [host realm]}]
  {:cmd           wd-def, :app 0, :flags #{:r}
   :required-avps (into #{} (avps-of [origin-host-avp-id host] [origin-realm-avp-id realm] [disconnect-cause-avp-id 0]))})


(defn dp-req-of [{:keys [host realm]}]
  {:cmd           dp-def, :app 0, :flags #{:r}
   :required-avps (into #{} (avps-of [origin-host-avp-id host] [origin-realm-avp-id realm]))})

(defn error-ans-of [req result-code err-msg {:keys [host realm]}]
  (assoc
    (update req :flags disj :r)
    :required-avps (into #{} (avps-of [origin-host-avp-id host]
                                      [origin-realm-avp-id realm]
                                      [result-code-avp-id result-code]
                                      [error-message-avp-id err-msg #{}]))))


(defn standard-answer-of [cmd config]
  (let [{:keys [hbh e2e cmd app flags]} cmd
        {:keys [host realm]} config]
    {:cmd cmd, :app app, :hbh hbh, :e2e e2e, :flags (disj flags :r)
     :required-avps
          (into #{} (avps-of [origin-host-avp-id host] [origin-realm-avp-id realm] [result-code-avp-id 2001]))}))



(def ^:const to-lower-case #(.toLowerCase #^String %))


(defn local-loop! [opts]
  (let [{:keys [local-chan answer req-chan print-fn]} opts]
    (go-loop 
      []
      (let [cmd (<! local-chan)]
        (print-fn (assoc cmd :location :local))
        (if (request? cmd)
          (>! req-chan (answer cmd opts))
          (print-fn "answer")
        ))
      (recur))))

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
   :route-chan (chan)
   :local-chan (chan) ;a cmd is pushed to this channel if the routing loop decides it should be processed locally
   :local-loop-fn local-loop! ;function that implements the local logic, shoule be a loop
   :peer-table {}
   :route-table {}
   :route-fn identity
   :realm-strategy-fn first ;take the first host in for a realm
   }
  )

(comment
  ;peer-table example 
  {"p1" (assoc (default-options) :port 3870)}
  ;route-table example
  {"r1" {100 {:hosts ["h1" "h2"]}}}
  )

(defn successful-cea? [cmd] (avp-of cmd result-code-avp-id))

(defn encode [cmd]
  (byte-array (map byte (encode-cmd cmd))))

(defmulti handshake! :kind)


(defmethod handshake! :client [opts]
  (let [{:keys [cer print-fn ignore-cea]} opts
        {:keys [raw-in-chan raw-out-chan] :as connection} (connect opts)]
    (if cer
      (let [cmd  (cer (assoc opts :hbh 0))]
        (print-fn (assoc cmd :location :cer))
	      (>!! raw-out-chan (encode cmd))
	      (let [cea (decode-cmd (<!! raw-in-chan) false)]
	        (print-fn (assoc cea :location :cea))
	        (when (or (successful-cea? cea) ignore-cea)
           connection)))
      connection)))
  
(defmethod handshake! :server [opts]
  (let [{:keys [print-fn]} opts
        {:keys [raw-in-chan raw-out-chan] :as connection} (bind opts)
        cer (decode-cmd (<!! raw-in-chan) false)]
      (print-fn (assoc cer :location :cer))
      (>!! raw-out-chan (encode (cer-ans-of cer opts)))
      connection))
 
(defn update-outstanding-reqs! [req outstanding-reqs]
  (swap! outstanding-reqs assoc (:e2e req) {:req req, :time (System/currentTimeMillis)}))

(defn match-with-req! [ans outstanding-reqs]
  (let [mr (atom nil)
        e2e (:e2e ans)]
    (swap! 
      outstanding-reqs 
      (fn [m] 
        (reset! mr (m e2e))
        (dissoc m e2e)))
    @mr))
  
(defn host->chan [host peer-table]
  (:req-chan (peer-table host)))



(defn route-loop! [opts]
  (let [{:keys [req-chan route-chan local-chan send-wdr wdr print-fn answer host realm peer-table route-table route-fn realm-strategy-fn]} opts]
    (go-loop 
      []
      (let [{:keys [req cmd]} (route-fn (<! route-chan))]
        (print-fn (assoc req :location :req-route))
        (print-fn (assoc cmd :location :cmd-route))
        (if (proxiable? cmd)
          (if-let [dest-host (avp-of req destination-host-avp-id)]
            (if (= dest-host host)
              (>! local-chan cmd)
              (if-let [c (host->chan dest-host peer-table)]
                (>! c cmd)
                (print-fn (format "%s does not exist in peer-table" dest-host)))
              )
            (if-let [dest-realm (avp-of req destination-realm-avp-id)]
              (if (= dest-realm realm)
                (>! local-chan cmd)
                (if-let [c (-> ((route-table dest-realm) (:app cmd)) :hosts realm-strategy-fn (host->chan peer-table))]
                  (>! c cmd)
                  (println (format "Couldn't find route for realm %s" dest-realm))))))
          (>! local-chan cmd)
        )
      (recur)))))




(defn main-loop! [opts connection outstanding-reqs]
  (let [{:keys [req-chan route-chan send-wdr wdr print-fn answer]} opts
        {:keys [raw-in-chan raw-out-chan]} connection]
    (go-loop
      [hbh 1]
      (let [[v c] (alts! [raw-in-chan req-chan])]
        (if v 
          (do 
            (condp = c
              raw-in-chan 
              (let [dv (-> v (decode-cmd false))]
                (print-fn (assoc dv :location :raw-in-chan))
                (if (request? dv)
                  (>! route-chan {:req dv, :cmd dv})
                  (if-let [mr (match-with-req! dv outstanding-reqs)] 
                     (>! route-chan {:req (:req mr), :cmd dv})
                     (print-fn (format "Could not find matching request for %s" dv)))))
              req-chan 
              (do 
                (print-fn (assoc v :location :req-chan)) 
	              (if (request? v)
	                (do 
	                  (update-outstanding-reqs! v outstanding-reqs)
	                  (>! raw-out-chan (encode (assoc v :hbh hbh))))
	                (>! raw-out-chan (encode v)))))
            (recur (inc hbh)))
          (do 
            (>! raw-out-chan :disconnect)
            (>! raw-out-chan (encode (assoc (dp-req-of opts) :hbh hbh)))
            (print-fn (decode-cmd (<!! raw-in-chan) false))
            (disconnect connection)))))))

(declare start-peer!)

(defn start-peers! [peers]
  (doseq [p peers]
    (start-peer! p)))



(defn start-main! [options]
  (let [outstanding-reqs (atom {})
        opts (merge (default-options) options (map-of outstanding-reqs))
        {:keys [req-chan route-chan send-wdr wdr print-fn answer local-loop-fn peer-table ]} opts]
    (if-let [{:keys [raw-in-chan raw-out-chan] :as connection} (handshake! opts)]
      (do 
        (print-fn "Diameter session started")
        (main-loop! opts connection outstanding-reqs)
        (route-loop! opts)
        (local-loop-fn opts)
        (start-peers! (map #(assoc % :route-chan route-chan) (-> opts :peer-table vals)))
        (assoc opts :connection connection)
        )
      (print-fn "Terminating, conenection not successful"))))

(defn start-peer! [options]
  (let [outstanding-reqs (atom {})
        opts (merge (default-options) options (map-of outstanding-reqs))
        {:keys [req-chan route-chan send-wdr wdr print-fn answer local-loop-fn peer-table ]} opts]
    (if-let [{:keys [raw-in-chan raw-out-chan] :as connection} (handshake! opts)]
      (do 
        (print-fn "Diameter session started")
        (main-loop! opts connection outstanding-reqs)
        (assoc opts :connection connection)
        )
      (print-fn "Terminating, conenection not successful"))))
  
(defn start! [& options]
  (start-main! (apply hash-map options)))


(defn send-cmd! [cmd options]
  (>!! (:req-chan options) (assoc cmd :e2e (create-e2e))))



(defn close-session! [options]
  (close! (:req-chan options)))

(defmethod ip-address-of :local [config] "127.0.0.1")


(defmethod connect :local [options]
   (let [raw-in-chan (chan)
         raw-out-chan (chan)]
     (go-loop 
       [connected false]
       (let [req (-> (<! raw-out-chan) vec (decode-cmd false))]
         (>! raw-in-chan 
           (encode-cmd 
             (if (not connected)
               (do 
                 (assert (cer? req))
                 (cer-ans-of req options))
                 (standard-answer-of req options))))
       (recur true)))
     (map-of raw-in-chan raw-out-chan)))
     



