(ns diameter_sim.codectest
  (:require [diameter_sim.codec :refer [number->ba
                                    ba->number
                                    encode-cmd-flags
                                    decode-cmd-flags
                                    ip-address-ast->encoded ip-address->ast decode-ip
                                    pad
                                    encode-avp
                                    decode-avp
                                    decode-avps
                                    encode-cmd
                                    decode-cmd
                                    def-cmd
                                    def-avp
                                    offset-range-of
                                    calc-grouped-size]]
            [clojure.test :refer [deftest is run-tests]]))



(deftest verify-number->ba
  (is (= [127] (number->ba 127 1)))
  (is (= [-128] (number->ba 128 1)))
  (is (= [1 0] (number->ba 256 2)))
  (is (= [0 1 0] (number->ba 256 3)))
  (is (= [0] (number->ba 256 1)))
  (is (= [-1 -1 -1 -1] (number->ba 0xffffffff 4)))
  (is (= [0 0 0 0 -1 -1 -1 -1] (number->ba 0xffffffff 8)))
  (is (= [0 0 0 0 0 0 -1 -1 -1 -1] (number->ba 0xffffffff 10)))
  )

(defn ba-number-roundtrip [v n]
  (let [ba (number->ba v n)]
    (is (= n (count ba)))
    (is (= v (ba->number ba 0 n)))))

(deftest verify-ba->number
  (ba-number-roundtrip 1 1)
  (ba-number-roundtrip 255 1)
  (ba-number-roundtrip 256 2)
  )

(deftest verify-encode-flags
  (is (= -128 (encode-cmd-flags #{:r})))
  (is (= -96 (encode-cmd-flags #{:r :e})))
  (is (= -32 (encode-cmd-flags #{:r :e :p})))
  (is (= -16 (encode-cmd-flags #{:r :e :p :t})))
  )

(defn encode-cmd-flags-roundtrip [flags]
  (is (= flags (-> flags encode-cmd-flags decode-cmd-flags))))

(deftest verify-encode-cmd-flags-roundtrip
  (encode-cmd-flags-roundtrip #{:r})
  (encode-cmd-flags-roundtrip #{:r :e})
  (encode-cmd-flags-roundtrip #{:r :e :p})
  (encode-cmd-flags-roundtrip #{:r :e :p :t})
  )

(deftest verify-ip-parsing
  (let [ip-parser (comp ip-address-ast->encoded ip-address->ast)]
    (is (= [0 1 127 0 0 1] (ip-parser "127.0.0.1")))
    (is (= [0 1 10 -128 1 -1] (ip-parser "10.128.1.255")))
    )
  )

(defn ip-parsing-roundtrip [address]
  (let [ip-parser (comp ip-address-ast->encoded ip-address->ast)]
    (is (= address (-> address ip-parser vec (decode-ip 0 nil))))
    )
  )

(deftest verify-ip-parsing-roundtrip
  (ip-parsing-roundtrip "127.0.0.1")
  (ip-parsing-roundtrip "255.255.255.255")
  (ip-parsing-roundtrip "10.1.255.127")
  )

(deftest verify-pad
  (is (= 12 (pad 10)))
  (is (= 12 (pad 11)))
  (is (= 12 (pad 12)))
  (is (= 16 (pad 13)))
  (is (= [] (pad [])))

  (is (= [1 0 0 0] (pad [1])))
  (is (= [1 2 0 0] (pad [1 2])))
  (is (= [1 2 3 0] (pad [1 2 3])))
  (is (= [1 2 3 4] (pad [1 2 3 4])))
  (is (= [1 2 3 4 5 0 0 0] (pad [1 2 3 4 5])))

  )

(deftest verify-encode-avp
  (let [v-fn
        (fn [exp m]
          (is (= exp (encode-avp m)))
          (is (= (mod (count exp) 4) 0)))]

    (def-avp session-id 263 :utf-8-string)
    (v-fn [0 0 1 7 0 0 0 12 97 115 100 102] {:code session-id-avp-id, :flags #{}, :data "asdf"})
    (v-fn [0 0 1 7 64 0 0 12 97 115 100 102] {:code session-id-avp-id, :flags #{:m}, :data "asdf"})
    (v-fn [0 0 1 7 -64 0 0 16 0 0 0 100 97 115 100 102] {:code session-id-avp-id, :flags #{:m :v}, :data "asdf" :vendor 100})))

(defn encode-avp-roundtrip [avp]
  (is (= avp (-> avp encode-avp vec (decode-avp 0 false (fn [_] true) ))))
  )

(deftest verify-encode-avp-roundtrip
  (encode-avp-roundtrip {:code session-id-avp-id, :flags #{}, :data "asdf"})
  (encode-avp-roundtrip {:code session-id-avp-id, :flags #{:m}, :data "asdf"})
  (encode-avp-roundtrip {:code session-id-avp-id, :flags #{:m :v}, :data "asdf" :vendor 100})

  (def-avp my-avp1 50 :unsigned32)
  (def-avp my-grouped-avp1 60 :grouped)
  (encode-avp-roundtrip {:code my-grouped-avp1-avp-id, :flags #{} :data [{:code my-avp1-avp-id, :flags #{} :data 200}]})
  )


(deftest verify-encode-cmd
  (is (= [1 0 0 20 -128 0 1 1 0 0 0 100 0 0 0 0 0 0 0 1]
         (encode-cmd {:cmd 257, :app 100, :hbh 0, :e2e 1, :flags #{:r}})))
  (is (= [1 0 0 20 0 0 1 1 0 0 0 100 0 0 0 0 0 0 0 1]
         (encode-cmd {:cmd 257, :app 100, :hbh 0, :e2e 1, :flags #{} :required-avps #{}})))
  (is (= [1 0 0 20 0 0 1 1 0 0 0 100 0 0 0 0 0 0 0 1]
         (encode-cmd {:cmd 257, :app 100, :hbh 0, :e2e 1, :flags #{} :required-avps #{}, :fixed-avps []})))

  )

(defn encode-cmd-roundtrip [cmd]
  (is (= cmd (-> cmd encode-cmd (decode-cmd false))))
  )

(deftest verify-encode-cmd-roundtrip 
  (def-avp my-avp1 50 :unsigned32)
  (def-cmd my-cmd1 100 0 0)
  (encode-cmd-roundtrip {:version 1, :cmd my-cmd1-def, :app 100, :hbh 0, :e2e 1, :flags #{} 
                       :required-avps #{{:code my-avp1-avp-id, :flags #{} :data 10}}
                       :fixed-avps []})
  (def-cmd my-cmd2 101 1 1)
  (def-avp my-avp2 51 :utf-8-string)
  (encode-cmd-roundtrip {:version 1, :cmd my-cmd2-def, :app 100, :hbh 0, :e2e 1, :flags #{} 
                       :required-avps #{{:code my-avp1-avp-id, :flags #{} :data 10}}
                       :fixed-avps [{:code my-avp2-avp-id, :flags #{} :data "test"}]})
  (def-avp my-grouped-avp1 60 :grouped)
  ;simple group
  (encode-cmd-roundtrip {:version 1, :cmd my-cmd2-def, :app 100, :hbh 0, :e2e 1, :flags #{} 
                       :required-avps #{{:code my-grouped-avp1-avp-id, :flags #{} :data [{:code my-avp1-avp-id, :flags #{} :data 200}]}}
                       :fixed-avps [{:code my-avp2-avp-id, :flags #{} :data "test"}]})
  ;group in group
  (encode-cmd-roundtrip {:version 1, :cmd my-cmd2-def, :app 100, :hbh 0, :e2e 1, :flags #{} 
                       :required-avps #{{:code my-grouped-avp1-avp-id, :flags #{} :data [{:code my-grouped-avp1-avp-id, :flags #{} :data [{:code my-avp1-avp-id :flags #{} :data 1}]}]}}
                       :fixed-avps [{:code my-avp2-avp-id, :flags #{} :data "test"}]})
  ;group in group in group
  (encode-cmd-roundtrip {:version 1, :cmd my-cmd2-def, :app 100, :hbh 0, :e2e 1, :flags #{} 
                       :required-avps #{{:code my-grouped-avp1-avp-id, :flags #{} 
                                         :data [{:code my-grouped-avp1-avp-id, :flags #{} 
                                                 :data [{:code my-grouped-avp1-avp-id :flags #{:m} :data [{:code my-avp2-avp-id :flags #{:m} :data "hej"}]}]}]}}
                       :fixed-avps [{:code my-avp2-avp-id, :flags #{} :data "test"}]})
  )



