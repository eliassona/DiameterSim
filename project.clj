(defproject diameter-sim "0.1.0-SNAPSHOT"
  :description "Diameter simulator"
  :url "https://github.com/eliassona/DiameterSim"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [instaparse "1.4.1"]
                 [defun "0.1.0"]
                 [org.clojure/tools.logging "0.3.1"]]
  :profiles {:dev {:repositories {"project" "file:maven_repository"}
                   :dependencies [[local/sctp "1.0.0"]
                                  [org.clojure/tools.trace "0.7.5"]
                                  [criterium "0.3.1"]
                                  [rhizome "0.2.5"]],
                   }}
  )
