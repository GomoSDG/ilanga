(ns ilanga.test-runner
  "Zero-dependency test runner. Use: clj -M:test -m ilanga.test-runner"
  (:require [clojure.test :as t]
            [ilanga.protocol.replay-test]
            [ilanga.system-test])
  (:gen-class))

(defn -main [& _]
  (let [res (t/run-tests
             'ilanga.protocol.replay-test
             'ilanga.system-test)]
    (System/exit (if (or (pos? (:fail res 0)) (pos? (:error res 0))) 1 0))))