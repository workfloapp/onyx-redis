(ns onyx.plugin.redis-lifecycle-test
  (:require [aero.core :refer [read-config]]
            [clojure.core.async :refer [pipe]]
            [clojure.core.async.lab :refer [spool]]
            [clojure.test :refer [deftest is]]
            [onyx api
             [job :refer [add-task]]
             [test-helper :refer [with-test-env]]]
            [onyx.plugin
             [core-async :refer [get-core-async-channels take-segments!]]
             [redis]]
            [onyx.tasks
             [core-async :as core-async]
             [redis :refer [connected-task]]]
            [taoensso.carmine :as car :refer [wcar]]))

(defn build-job [redis-uri batch-size batch-timeout]
  (let [batch-settings {:onyx/batch-size batch-size :onyx/batch-timeout batch-timeout}
        base-job (merge {:workflow [[:in :lookup]
                                    [:lookup :out]]
                         :catalog []
                         :lifecycles []
                         :windows []
                         :triggers []
                         :flow-conditions []
                         :task-scheduler :onyx.task-scheduler/balanced})]
    (-> base-job
        (add-task (core-async/input :in batch-settings))
        (add-task (connected-task :lookup ::my-lookup redis-uri batch-settings))
        (add-task (core-async/output :out batch-settings)))))

(defn my-lookup [conn segment]
  {:results (wcar conn
                  (car/lrange (:key segment) 0 1000))})

(defn ensure-redis! [redis-conn]
  (doseq [n (range 100)]
    (wcar redis-conn
          (car/lpush ::some-key n)
          (car/pfadd ::hll_some-key n))))

(deftest redis-lifecycle-injection-test
  (let [{:keys [env-config peer-config redis-config]}
        (read-config (clojure.java.io/resource "config.edn") {:profile :test})
        redis-uri (get redis-config :redis/uri)
        job (build-job redis-uri 10 1000)
        {:keys [in out]} (get-core-async-channels job)
        redis-conn {:spec {:uri redis-uri}}]
    (try
      (with-test-env [test-env [3 env-config peer-config]]
        (pipe (spool [{:key ::some-key} :done]) in false)
        (ensure-redis! redis-conn)
        (onyx.test-helper/validate-enough-peers! test-env job)
        (onyx.api/submit-job peer-config job)
        (is (= (take-segments! out)
               [{:results (map str (reverse (range 100)))}
                :done])))
      (finally (wcar redis-conn
                     (car/flushall)
                     (car/flushdb))))))
