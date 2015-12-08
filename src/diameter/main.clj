(ns diameter.main
  (:require [diameter.codec :refer [def-cmd cmd-flag-map]]
            [diameter.base :refer [origin-realm-avp-id origin-host-avp-id
                                   destination-host-avp-id auth-application-id-avp-id
                                   result-code-avp-id
                                   session-id-avp-id
                                   start!
                                   send-cmd!
                                   find-avp
                                   close-session!]]
            [clojure.core.async :refer [>!! chan <!!]]
            [clojure.test :refer [run-tests is deftest]])
   (:use [diameter.transport]))

;;A test command
(def-cmd a-cmd 11 0 0)
(def a-cmd 
  {:cmd a-cmd-def, :app 100, :flags #{:r} 
       :required-avps #{{:code origin-realm-avp-id, :flags #{:m}, :data "cl"}
                        {:code origin-host-avp-id, :flags #{:m}, :data "localhost"}
                        #_{:code destination-host-avp-id, :flags #{:m}, :data "dia2"}
                        {:code auth-application-id-avp-id, :flags #{:m}, :data 100}
                        {:code session-id-avp-id, :flags #{}, :data "asdf"}}})


(defn answer-code [cmd]
  (if (a-cmda? cmd) 
    (:data (find-avp cmd :required-avps result-code-avp-id))
    -1))


(deftest test-header-bit
  (let [res-chan (chan)
        options (start! :transport :tcp, :res-chan res-chan, :print-fn (fn [_]))]
    (dotimes [i 1]
      (println (str "iteration: " i))
      
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


