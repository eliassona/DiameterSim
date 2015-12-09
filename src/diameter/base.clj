(ns diameter.base
  (:require [diameter.codec :refer [def-avp def-cmd encode-cmd map-of decode-cmd request? answer? dbg]]
            [clojure.core.async :refer [chan go >! <! >!! <!! go-loop alts! timeout onto-chan pipeline close! sliding-buffer]]))

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

(defn create-e2e []
  (bit-or
    (bit-shift-left (System/currentTimeMillis) 20)
    (-> two-to-twenty rand int)))

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
  (let [{:keys [hbh e2e cmd app]} cmd
        {:keys [host realm]} config]
    {:cmd cmd, :app app, :hbh hbh, :e2e e2e, :flags #{}
     :required-avps
          (into #{} (avps-of [origin-host-avp-id host] [origin-realm-avp-id realm] [result-code-avp-id 2001]))}))



(def ^:const to-lower-case #(.toLowerCase #^String %))


(defn default-options []
  {:transport :tcp
   :host "localhost"
   :realm "cl"
   :app 100
   :cer       #(cer-req-of %)
   :send-wdr  true
   :send-wda  true
   :print-fn println
   :wdr       #(wd-req-of %)
   :req-chan  (chan)
   :res-chan  (slide-chan)}
  )

(defn successful-cea? [cmd]
  (and (cea? cmd) (= (:data (find-avp cmd :required-avps result-code-avp-id)) 2001)))


(defn encode [cmd]
  (byte-array (map byte (encode-cmd cmd))))


(defn handshake!
  "Perform CER if cer fn is not nil"
  [raw-out-chan raw-in-chan {:keys [cer print-fn ignore-cea] :as opts}]
  (if cer
    (let [cmd  (cer (assoc opts :hbh 0))]
      (print-fn cmd)
	    (>!! raw-out-chan (encode cmd))
	    (let [cea (decode-cmd (<!! raw-in-chan) false)]
	      (print-fn cea)
	      (or (successful-cea? cea) ignore-cea)))
    true))
  

(defn start! [& options]
  (let [opts (merge (default-options) (apply hash-map options))
        {:keys [req-chan res-chan send-wdr wdr print-fn]} opts
        {:keys [raw-in-chan raw-out-chan] :as connection} (connect opts)]
    (if (handshake! raw-out-chan raw-in-chan opts)
      (do 
        (print-fn "Diameter session started")
        (go-loop
          [hbh 1]
          (let [[v c] (alts! [raw-in-chan req-chan])]
            (if v 
              (do
                (condp = c
                  raw-in-chan 
                  (let [dv (-> v (decode-cmd false))]
                    (cond 
                      (wdr? dv)
                      (>! raw-out-chan (encode (standard-answer-of dv opts)))
                      (dpr? dv)
                      (do 
                        (>! raw-out-chan (encode (standard-answer-of dv opts)))
                        (close! req-chan))
                      :else
                      (do 
                        (print-fn dv)
                        (>! res-chan dv))
                    ))
                  req-chan (>! raw-out-chan (encode (assoc v :hbh hbh, :e2e (create-e2e)))))
              (recur (inc hbh)))
              (do 
                (>! raw-out-chan :disconnect)
                (>! raw-out-chan (encode (assoc (dp-req-of opts) :hbh hbh, :e2e (create-e2e))))
                (print-fn (decode-cmd (<!! raw-in-chan) false))
                (disconnect connection)
                )))))
      (print-fn "Terminating, CEA not successful"))
  (assoc opts :connection connection)
  ))


(defn send-cmd! [cmd options]
  (>!! (:req-chan options) cmd))

(defn close-session! [options]
  (close! (:req-chan options)))

(defmethod ip-address-of :local [config] "127.0.0.1")


(defmethod connect :local [options]
   (let [raw-in-chan (chan)
         raw-out-chan (chan)]
     (go-loop 
       [connected false]
       (let [req (-> (<! raw-out-chan) (decode-cmd false))]
         (>! raw-in-chan 
           (encode-cmd 
             (if (not connected)
               (do 
                 (assert (cer? req))
                 (cer-ans-of req options))
                 (standard-answer-of req options))))
       (recur true)))
     (map-of raw-in-chan raw-out-chan)))
     

