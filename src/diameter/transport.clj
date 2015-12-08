(ns diameter.transport
  (:require [diameter.codec :refer [Decode decode-cmd encode-cmd dbg ba->number]]
            [diameter.base :refer [connect disconnect slide-chan avp-of
                                   origin-host-avp-id ip-address-of]]
            [clojure.core.async :refer [chan go >! <! <!! >!! go-loop alts! timeout onto-chan pipeline close! thread dropping-buffer]])
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

(defn read-loop [#^Socket socket c]
  (thread
    (try 
      (let [in (.getInputStream socket)
            ver-and-size (byte-array 4)
            read-cmd-fn (read-cmd in)]
        (while true
          (>!! c (read-cmd-fn))))
      (catch Exception e
        ;(.printStackTrace e)
        ))))


(defn write-loop [#^Socket socket c]
  (thread
    (try 
      (let [out (.getOutputStream socket)]
        (loop []
          (let [v (<!! c)]
            (if (= v :disconnect)
              (do 
                (.write #^OutputStream out #^bytes (<!! c))
                (.flush out)
                (.close out))
              (do 
                (.write #^OutputStream out #^bytes v)
                (recur))))))
      (catch Exception e
        (.printStackTrace e)))))


(defn default-options []
  {:port 3869,
   :host "localhost"
   :raw-in-chan (chan)
   :raw-out-chan (chan)
   })


(defmethod ip-address-of :tcp [config] (.getHostAddress (InetAddress/getByName (:host config))))


(defmethod connect :tcp [options]
  (let [om (merge (default-options) options)
        {:keys [host port raw-in-chan raw-out-chan]} om
        s (Socket. host port)]
    (write-loop s raw-out-chan)
    (read-loop s  raw-in-chan)
    (assoc om :socket s)))


(defmethod disconnect :tcp [options]
  (let [{:keys [socket]} options]
    (.close socket)))


;;================== for testing ===========================================================


