(ns metabase.task.sync-databases
  (:require [clojure.tools.logging :as log]
            [clojurewerkz.quartzite.jobs :as jobs]
            [clojurewerkz.quartzite.triggers :as triggers]
            [clojurewerkz.quartzite.schedule.cron :as cron]
            [metabase.config :as config]
            [metabase.db :as db]
            [metabase.driver :as driver]
            [metabase.models.database :refer [Database]]
            [metabase.task :as task]))

(def sync-databases-job-key "metabase.task.sync-databases.job")
(def sync-databases-trigger-key "metabase.task.sync-databases.trigger")

(defonce ^:private sync-databases-job (atom nil))
(defonce ^:private sync-databases-trigger (atom nil))

;; simple job which looks up all databases and runs a sync on them
;; TODO - skip the sample dataset?
(jobs/defjob SyncDatabases
  [ctx]
  (dorun
    (for [database (db/sel :many Database)]
      (try
        ;; NOTE: this happens synchronously for now to avoid excessive load if there are lots of databases
        (driver/sync-database! database)
        (catch Exception e
          (log/error "Error syncing database: " (:id database) e))))))

;; this is what actually adds our task to the scheduler
(when (config/is-prod?)
  (log/info "Submitting sync-database task to scheduler")
  ;; build our job
  (reset! sync-databases-job (jobs/build
                               (jobs/of-type SyncDatabases)
                               (jobs/with-identity (jobs/key sync-databases-job-key))))
  ;; build our trigger
  (reset! sync-databases-trigger (triggers/build
                                   (triggers/with-identity (triggers/key sync-databases-trigger-key))
                                   (triggers/start-now)
                                   (triggers/with-schedule
                                     ;; run at midnight daily
                                     (cron/schedule (cron/cron-schedule "0 0 0 * * ? *")))))
  ;; submit ourselves to the scheduler
  (task/schedule-task! @sync-databases-job @sync-databases-trigger))

