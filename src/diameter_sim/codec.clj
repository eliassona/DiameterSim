(ns diameter_sim.codec
  (:require [instaparse.core :as insta]
            [clojure.core.match :refer [match]])
  (:use [clojure.pprint])
  (:import [java.util List])
  )


(defmacro dbg[x] `(let [x# ~x] (println '~x "=" x#) x#))
(defmacro map-of [& args] (apply hash-map (mapcat (fn [arg] [(keyword arg) arg])  args)))

;;------------Diameter decoding/encoding 

(defn number->twos-compl [v]
  (if (> v 127) (- v 256) v))

(defn twos-compl->number [v]
  (if (neg? v)
    (+ v 256)
    v)
  )
(defn number->ba [v n]
  "transform a number (v) into an byte vector of (n) values"
  (:res 
    (reduce 
      (fn [acc _] 
        (update 
          (update acc :res #(conj % (-> acc :v (bit-and 0xff) number->twos-compl))) :v #(bit-shift-right % 8))) 
      {:res '() :v v} 
      (range n))))
  

(defn ba->number [ba offset len]
  {:pre [(<= (+ offset len) (count ba))]}
  "transform a byte array into number"
  (let [ba (if (vector? ba) ba (vec ba))]
    (reduce + (map-indexed (fn [ix v] (bit-shift-left (twos-compl->number v) (* 8 (- len ix 1)))) (subvec ba offset (+ offset len))))))

(defn encode-flags-impl [flag-set flag-map]
  (number->twos-compl (reduce bit-or 0 (map #(if (contains? (->> flag-map keys (into #{})) %) (flag-map %) 0) flag-set)))
  )

(def encode-flags (memoize encode-flags-impl))

(defn bit-value->keyword [v]
  (map (fn [e] 
    (let [k (key e) 
          mask (val e)] 
      (when (= (bit-and v mask) mask) k)))))


(defn decode-flags-impl [v flag-map]
  (into #{} (comp (bit-value->keyword v) (filter identity)) flag-map))

(def decode-flags (memoize decode-flags-impl))

(def ^:const version 1)

(def cmd-flag-map {:r 0x80, :p 0x40, :e 0x20, :t 0x10})

(defn encode-cmd-flags [flag-set]
  (encode-flags flag-set cmd-flag-map))

(defn decode-cmd-flags [v]
  (decode-flags v cmd-flag-map))

(def ip-address->ast
  (insta/parser
     "<IP-ADDRESS> = IPV4 | IPV6
      IPV4 = DEC-INT <'.'> DEC-INT <'.'> DEC-INT <'.'> DEC-INT
      IPV6 = HEX-INT <':'> HEX-INT <':'> HEX-INT <':'> HEX-INT <':'> HEX-INT <':'> HEX-INT <':'> HEX-INT <':'> HEX-INT
      DEC-INT = #'[0-9]+'
      HEX-INT = #'[0-9a-fA-F]+'"

     ))

(defn validate-ip4 [v]
  (map #(and (assert (>= % 0) (<= v 255)) %) v))

(def ip-address-ast->encoded-map 
  {:DEC-INT read-string
   :HEX-INT (fn [v] (read-string (str "0x" v))) 
   :IPV4 (fn [a b c d] (map (comp byte number->twos-compl) [0 1 a b c d]))
   :IPV6 (fn [& values] (concat [0 2] (mapcat (fn [v] (number->ba v 2)) values)))
})
  

(defn ip-address-ast->encoded [ast]
  (first 
    (insta/transform
      ip-address-ast->encoded-map 
      ast)))

(defn sub-arr [l offset len]
  (byte-array (subvec l offset (+ offset len))))

(defn decode-ip [ba offset _] 
  (let [type (ba->number ba offset 2)
        data-ix (+ offset 2)]
   (condp = type
     1 (let [[a b c d] (map twos-compl->number (subvec ba data-ix (+ data-ix 4)))] (format "%s.%s.%s.%s" a b c d)))))

(defn int-map-of [size] 
  {:size (fn [_] size), :encode (fn [v] (number->ba v size)), :decode (fn [ba offset _] (ba->number ba offset size))})


(declare offset-range-of decode-avps avp-types avp-type-of encode-avp check-vendor-flag calc-avp-length)

(defn calc-grouped-size [v]
  ;should I use pad here???
  (reduce +
    (map       
      (fn [{:keys [code vendor flags data]}]
         (let [vendor-id-is-set (check-vendor-flag flags)
               type (avp-type-of code)]
           (calc-avp-length vendor-id-is-set type data))) v)))

(defn decode-grouped [ba offset len]
  (decode-avps ba false (offset-range-of ba offset len)))


(defn encode-grouped-avp [v]
  (reduce concat (map encode-avp v)))

(def avp-types {:octet-string {:size (fn [v] (count (.getBytes #^String v))), :encode (fn [v] (.getBytes #^String v))},
                :utf-8-string {:size (fn [v] (count (.getBytes #^String v))), :encode (fn [v] (.getBytes #^String v)), :decode (fn [ba offset len] (String. #^bytes (sub-arr ba offset len)))}
                :diameter-identity {:size (fn [v] (count (.getBytes #^String v))), :encode (fn [v] (.getBytes #^String v)), :decode (fn [ba offset len] (.toLowerCase (String. #^bytes (sub-arr ba offset len))))},
                :address {:size (fn [v] (condp = (-> v ip-address->ast first first)
                                          :IPV4 6
                                          :IPV6 18),)
                          :encode (comp ip-address-ast->encoded ip-address->ast), 
                          :decode decode-ip},
                :integer32 (int-map-of 4), 
                :integer64 (int-map-of 8), 
                :unsigned32 (int-map-of 4), 
                :unsigned64 (int-map-of 8), 
                :float32 {:size (fn [_] 4), :encode (fn [v] (number->ba v 0 4))}, 
                :float64 {:size (fn [_] 8), :encode (fn [v] (number->ba v 0 8))}, 
                :grouped {:size calc-grouped-size, :encode encode-grouped-avp, :decode decode-grouped}
                :identity {:size (fn [ba] (count ba)), :encode identity, :decode identity}
                })

(defn calc-avp-length [vendor-id-is-set type value]
  (+ 8 (if vendor-id-is-set 4 0) ((-> avp-types type :size) value)))

(def avp-flag-map {:v 0x80, :m 0x40, :p 0x20 :r5 0x10, :r4 0x8, :r3 0x4, :r2 0x2 :r1 0x1})

(defn encode-avp-flags [flag-set]
  (encode-flags flag-set avp-flag-map))

(defn decode-avp-flags [v]
  (decode-flags v avp-flag-map))

  
(defn check-vendor-flag [flags]
  (contains? flags :v))

(defprotocol Pad 
  (pad [this]))

(extend-protocol Pad
  Number 
  (pad [v] (let [m (mod v 4)] (if (= m 0) v (+ (- 4 m) v))))
  java.util.List  
  (pad [a]
    (let [m (mod (count a) 4)]
      (if (= m 0)
        a
        (concat a (map (constantly 0) (range (- 4 m))))))))


(defmulti avp-type-of identity)


(defn encode-avp [{:keys [code flags vendor data]}]
  {:pre [(or 
           (and (contains? flags :v) vendor)
           (not (contains? flags :v)) (nil? vendor))
         ]}
  "encode an avp to a vector of bytes"
  (let [vendor-id-is-set (check-vendor-flag flags)
        type (avp-type-of code)]
      (pad 
        (concat
          (number->ba code 4)
          [(encode-avp-flags flags)] (number->ba (calc-avp-length vendor-id-is-set type data) 3)
          (if vendor-id-is-set (number->ba vendor 4) [])
          ((-> avp-types type :encode) data)
          )))
  )


(defn add-length [version data]
  "adds the length of the cmd to the vector"
  (concat 
    [version]
    (number->ba (+ (count data) 4) 3)
    data))

(defn encode-cmd [{:keys [cmd flags app hbh e2e fixed-avps required-avps]}]
  {:pre [(not (some nil? [cmd flags app hbh e2e]))]}
  "encode a diameter command as vector of bytes"
   (into 
     [] (add-length
          version 
          (concat 
            [(encode-cmd-flags flags)] (number->ba cmd 3)
            (number->ba app 4)
            (number->ba hbh 4)
            (number->ba e2e 4)
            (mapcat encode-avp fixed-avps)
            (mapcat encode-avp required-avps)
            ))))

(defmulti cmd-def identity)
(defn nr-of-fixed-of [cmd-id flags] ((cmd-def cmd-id) (if (contains? flags :r) :req-nr-of-fixed :ans-nr-of-fixed)))


;TODO change this to use defmethod instead of atom  
(def code->avp-map (atom {})) 

(defn code->avp []
  @code->avp-map)

(defprotocol Prettyfy 
  (prettyfy [this]))

(defn prettyfy-avps [avps]
  (map #(update % :code (code->avp)) avps))

(extend-protocol Prettyfy
  nil
  (prettyfy [_] nil)
  java.util.List
  (prettyfy [d] 
    (match d
           [:data cmd] (prettyfy cmd)
           [:timeout _] d
           :else
           (prettyfy-avps d)))
  java.util.Set
  (prettyfy [avps] (into #{} (prettyfy-avps avps)))
  java.util.Map
  (prettyfy [cmd]
    (update 
      (update 
        (update cmd :required-avps prettyfy)
        :fixed-avps prettyfy)
      :cmd (fn [v] (:name (cmd-def v)))))
  )

(defmacro def-avp [& nvs]
  "Define avps, each avp consists of name id and type"
  `(do
     ~@(map 
         (fn [[name value type]] 
           (let [s# (symbol (str name "-avp-id"))]
             `(do 
                (def ~s# ~value)
                (swap! code->avp-map assoc ~value '~s#)  
                (defmethod avp-type-of ~s# [~'_] ~(if (string? type) (keyword type) type))))) 
         (partition 3 nvs)))) 

(defn avp-offset-of [flags]
  (if (check-vendor-flag flags)
    12
    8))

(defn assoc-if [m & args]
  (reduce (fn [acc [c k v]] (if c (assoc acc k v) acc)) m (partition 3 args)))

(defn decode-avp [ba offset include-len filter-fn]
  (let [code (ba->number ba offset 4)]
    (when (filter-fn code)
      (let [flags (decode-avp-flags (ba->number ba (+ offset 4) 1))
            len (ba->number ba (+ offset 5) 3)
            type (avp-type-of code)
            avp-offset (avp-offset-of flags)]
        (assoc-if 
          {:code code,
           :flags flags
           :data ((-> avp-types type :decode) ba (+ offset avp-offset) (- len avp-offset))
          } (check-vendor-flag flags) :vendor (ba->number ba (+ offset 8) 4)
            include-len :len len)))))

(defn offset-range-of [ba offset max-length]
  (loop [res [offset]]
    (let [ix (last res)
          l (ba->number ba (+ ix 5) 3)
          pl (pad l)]
      (if (< (+ ix pl) max-length)
        (recur (conj res (+ ix pl)))
        res))))
       
     
(defn decode-avps 
  ([ba include-len indexes]
    (decode-avps ba include-len indexes (fn [_] true)))
  ([ba include-len indexes filter-fn]
    (into [] (comp (map (fn [ofs] (decode-avp ba ofs include-len filter-fn))) (filter identity)) indexes)))   

(defn request? [cmd] (-> cmd :flags (contains? :r)))
(defn answer? [cmd] (not (request? cmd)))



(defmacro def-cmd [name id req-nr-of-fixed ans-nr-of-fixed]
  (let [n (symbol (str name "-def"))
        cmd? (symbol (str name "?"))
        req? (symbol (str name "r?"))
        ans? (symbol (str name "a?"))
        ]
    `(do 
       (def ~n ~id)
       (defn ~cmd? [cmd#] (= (:cmd cmd#) ~id))
       (defn ~req? [cmd#] (and (~cmd? cmd#) (request? cmd#)))
       (defn ~ans? [cmd#] (and (~cmd? cmd#) (answer? cmd#)))
       (defmethod cmd-def ~id [~'_] {:req-nr-of-fixed ~req-nr-of-fixed, :ans-nr-of-fixed ~ans-nr-of-fixed :name '~n})))) 


(defprotocol Decode
  (decode-cmd [this include-len]))

(def header-size 20)


(extend-type List
  Decode
  (decode-cmd [ba include-len]
    (assert (= (first ba) version))
    (let [l (ba->number ba 1 3)
          cmd (ba->number ba 5 3)
          flags (-> ba (ba->number 4 1) decode-cmd-flags)
          [fixed-indexes required-indexes] (split-at (nr-of-fixed-of cmd flags) (offset-range-of ba header-size l))] 
          (assoc-if 
            {:version version,
             :flags flags
             :cmd cmd
             :app (ba->number ba 8 4)
             :hbh (ba->number ba 12 4)
             :e2e (ba->number ba 16 4)
             :fixed-avps (decode-avps ba include-len fixed-indexes)
             :required-avps (into #{} (decode-avps ba include-len required-indexes))
             }
            include-len :len l))))
