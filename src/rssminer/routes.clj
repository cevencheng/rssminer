(ns rssminer.routes
  (:use [compojure.core :only [defroutes GET POST DELETE ANY context]]
        (ring.middleware [keyword-params :only [wrap-keyword-params]]
                         [file-info :only [wrap-file-info make-http-format]]
                         [params :only [wrap-params]]
                         [session :only [wrap-session]]
                         [multipart-params :only [wrap-multipart-params]]
                         [file :only [wrap-file]]
                         [session :only [wrap-session]])
        (rssminer.handlers [proxy :only [handle-proxy]]
                           [favicon :only [get-favicon]])
        (rssminer [middleware :only [wrap-auth wrap-cache-header
                                     wrap-reload-in-dev wrap-failsafe
                                     wrap-request-logging-in-dev
                                     JPOST JPUT JDELETE JGET]]
                  [redis :only [redis-store]]
                  [util :only [get-expire]]))
  (:require [clojure.string :as str]
            [compojure.route :as route]
            [rssminer.import :as import]
            (rssminer.handlers [reader :as reader]
                               [subscriptions :as subs]
                               [users :as user]
                               [dashboard :as dashboard]
                               [feeds :as feed]))
  (:import clojure.lang.Namespace))

(let [views-ns '[rssminer.views.reader
                 rssminer.views.layouts]
      all-rss-ns (filter
                  #(re-find #"^rssminer" (str %)) (all-ns))
      ns-to-path (fn [clj-ns]
                   (str
                    (str/replace
                     (str/replace (str clj-ns) #"-" "_")
                     #"\." "/") ".clj"))]
  (def reload-meta
    (apply merge (conj
                  (map (fn [clj-ns]
                         {(str "src/" (ns-to-path clj-ns))
                          [(.getName ^Namespace clj-ns)]}) all-rss-ns)
                  {"src/templates" views-ns}))))

(defroutes api-routes
  (context "/dashboard" []
           (JGET "/stat" [] dashboard/get-data)
           (JPOST "/" [] dashboard/settings))
  (context "/subs" []
           (JPOST "/add" [] subs/add-subscription)
           (JGET "/:rss-id" [] feed/get-by-subscription)
           (JGET "/p/:rss-id" [] subs/polling-subscription)
           (JPOST "/:id" [] subs/customize-subscription)
           (JDELETE "/:id" [] subs/unsubscribe))
  (JGET "/search" [] reader/search)
  (JPOST "/user/settings" [] user/save-settings)
  (JGET "/user/welcome" [] user/welcome-list)
  (context "/feeds/:feed-id" []
           (JGET "/" [] feed/get-by-id)
           (JPOST "/vote" [] feed/user-vote)
           (JPOST "/read" [] feed/mark-as-read))
  (JPOST "/import/opml-import" [] import/opml-import)
  (JGET "/export/opml-export" [] "TODO"))

(defroutes all-routes
  (GET "/" [] reader/landing-page)
  (GET "/p" []  handle-proxy)
  (GET "/fav" [] get-favicon)
  (GET "/f/o/:id" [] feed/get-orginal)
  (GET "/oauth2callback" [] import/oauth2callback)
  (GET "/a" [] reader/app-page)
  (context "/dashboard" []
           (GET "/" [] reader/dashboard-page))
  (context "/login" []
           (GET "/" [] user/show-login-page)
           (POST "/" [] user/login)
           (GET "/google" [] user/google-openid)
           (GET "/checkauth" [] user/checkauth))
  (GET "/import/google" [] import/greader-import)
  (context "/signup" []
           (GET "/" [] user/show-signup-page)
           (POST "/" [] user/signup))
  (context "/api" [] api-routes)
  (route/files "") ;; files under public folder
  (route/not-found "<h1>Page not found.</h1>" ))

;;; The last one in the list is the first one get the request,
;;; the last one get the response
(defn app []
  (-> #'all-routes
      wrap-auth
      (wrap-session {:store (redis-store (* 3600 24 3))
                     :cookie-name "rm_id"
                     :cookie-attrs {:http-only true
                                    :expires (get-expire 3)}})
      wrap-cache-header
      wrap-request-logging-in-dev
      wrap-keyword-params
      wrap-multipart-params
      wrap-params
      (wrap-reload-in-dev reload-meta)
      wrap-failsafe))
