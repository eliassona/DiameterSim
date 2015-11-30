(ns diameter_sim.base
  (:require [diameter_sim.codec :refer [def-avp def-cmd encode-cmd map-of decode-cmd request? answer? dbg]]
            [clojure.core.async :refer [chan go >! <! >!! <!! go-loop alts! timeout onto-chan pipeline close! sliding-buffer]]))

(def-avp 
	 session-id 263 :utf-8-string
	 origin-host 264 :diameter-identity
	 origin-realm 296 :diameter-identity
	 host-ip 257 :address
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

(def-cmd ce 257 0 0) ;Capability Exchange 
(def-cmd wd 280 0 0) ;Watchdog
(def-cmd dp 282 0 0) ;Disconnect

(defmulti init-transport :protocol)
(defmulti ip-address-of :protocol)
(defmulti close-transport :protocol)

(defmulti client 
  (fn [config peer-name] 
    (let [state (dbg (:state config))] (go (>! (:state ((:peer-table config) peer-name)) state)) state)))

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

(defn cer-req-of [config _]
  (let [{:keys [hbh host realm peer-table]} config]
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
  {:cmd wd-def, :app 0, :flags #{:r}
   :required-avps (into #{} (avps-of [origin-host-avp-id host] [origin-realm-avp-id realm] [disconnect-cause-avp-id 0]))})


(defn dp-req-of [{:keys [host realm]}]
  {:cmd dp-def, :app 0, :flags #{:r}
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

