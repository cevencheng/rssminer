(ns rssminer.admin
  (:gen-class)
  (:use (rssminer [search :only [index-feed use-index-writer!
                                 close-global-index-writer!]]
                  [util :only [user-id-from-session to-int]]
                  [classify :only [on-feed-event]]
                  [database :only [mysql-query with-mysql mysql-insert]])
        (clojure.tools [logging :only [info]]
                       [cli :only [cli]])
        [clojure.java.jdbc :only [with-query-results]])
  (:require [rssminer.database :as db])
  (:import [java.util.concurrent BlockingQueue ArrayBlockingQueue]
           [java.util.concurrent Executors TimeUnit]))

(def step 1000)

(defn- max-feed-id []
  (-> (mysql-query ["select max(id) m from feeds"]) first :m))

(def select-sql "SELECT f.*, s.summary FROM feeds f LEFT JOIN feed_data s ON f.id = s.id WHERE f.id >= ? AND f.id < ?")

(defn rebuild-index []
  (info "Clear all lucene index and rebuild it")
  (let [max (max-feed-id)
        stop-setinal (Object.)
        index-queue (ArrayBlockingQueue. 300)
        processor-count (.. Runtime getRuntime availableProcessors)
        threads (Executors/newFixedThreadPool processor-count)
        worker (fn [] (loop [feed (.take index-queue)]
                       (when (not= feed stop-setinal)
                         (index-feed (:id feed) (:rss_link_id feed) feed)
                         (recur (.take index-queue)))))]
    (doseq [i (range processor-count)]
      (.submit threads ^Runnable worker))
    (.shutdown threads)
    (info "max feed id " max)
    (doseq [start (range 0 (+ max step) step)]
      (when (= 0 (rem start (* step 10)))
        (info "doing feed" (str "[" start ", " (+ start step) ")")))
      (with-mysql
        (with-query-results rs [select-sql start (+ start step)]
          (doseq [feed rs]
            (.put index-queue feed)))))
    (doseq [i (range (* 2 processor-count))]
      (.put index-queue stop-setinal))
    (.awaitTermination threads 1000 TimeUnit/MINUTES)) ; allow stop
  (close-global-index-writer! :optimize true)
  (info "Rebuild index OK"))

(defn recompute-scores [req]
  (if (= 1 (:session req)) ;; 1 is myself, who is admin
    (if-let [id (-> req :params :u)]
      (do
        (on-feed-event (to-int id) (to-int -1))
        {:status 200 :body id})
      (let [users (map :id (mysql-query ["select id from users"]))]
        (doseq [id users]
          (on-feed-event (to-int id) (to-int -1)))
        {:status 200 :body (map str (interpose ", " users))}))
    {:status 401 :body "error"}))

(defn -main [& args]
  "Rssminer admin"
  (let [[options _ banner]
        (cli args
             ["-c" "--command" "rebuild-index" :parse-fn keyword]
             ["--db-url" "Mysql Database url"
              :default "jdbc:mysql://localhost/rssminer"]
             ["--db-user" "Mysql Database user name" :default "feng"]
             ["--index-path" "Path to store lucene index"
              :default "/var/rssminer/index"]
             ["--[no-]help" "Print this help"])]
    (when (:help options) (println banner) (System/exit 0))
    (if (= (:command options) :rebuild-index)
      (do (db/use-mysql-database! (:db-url options) (:db-user options))
          (use-index-writer! (:index-path options))
          (rebuild-index)))))

