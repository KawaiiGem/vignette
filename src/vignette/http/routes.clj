(ns vignette.http.routes
  (:require [cheshire.core :refer :all]
            [clojure.java.io :as io]
            [clout.core :refer [route-compile route-matches]]
            [compojure.core :refer [routes GET ANY]]
            [compojure.route :refer [files not-found]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [response status charset header]]
            [slingshot.slingshot :refer (try+ throw+)]
            [vignette.api.legacy.routes :as alr]
            [vignette.media-types :as mt]
            [vignette.protocols :refer :all]
            [vignette.storage.core :refer :all]
            [vignette.storage.protocols :refer :all]
            [vignette.util.image-response :refer :all]
            [vignette.util.query-options :refer :all]
            [vignette.util.regex :refer :all]
            [vignette.util.thumbnail :as u]
            [wikia.common.logger :as log])
  (:import [java.io FileInputStream FileInputStream]
           [java.net InetAddress]
           [java.nio ByteBuffer]))

(def hostname (.getHostName (InetAddress/getLocalHost)))

(def original-route
  (route-compile "/:wikia/:top-dir/:middle-dir/:original/revision/:revision"
                 {:wikia wikia-regex
                  :top-dir top-dir-regex
                  :middle-dir middle-dir-regex
                  :revision revision-regex}))

(def thumbnail-route
  (route-compile "/:wikia/:top-dir/:middle-dir/:original/revision/:revision/:thumbnail-mode/width/:width/height/:height"
                 {:wikia wikia-regex
                  :top-dir top-dir-regex
                  :middle-dir middle-dir-regex
                  :original original-regex
                  :revision revision-regex
                  :thumbnail-mode thumbnail-mode-regex
                  :width size-regex
                  :height size-regex}))

(defn exception-catcher
  [handler]
  (fn [request]
    (try+
      (handler request)
      (catch [:type :vignette.util.thumbnail/convert-error] {:keys [exit err]}
        (log/warn "thumbnailing error" {:path (:uri request) :code exit :err err})
        (error-response 500))
      (catch Exception e
        (log/warn (str e) {:path (:uri request)})
        (error-response 500)))))

(defn add-headers
  [handler]
  (fn [request]
    (let [response (handler request)]
      (reduce (fn [response [h v]]
                (header response h v))
              response {"Varnish-Logs" "vignette"
                        "X-Served-By" hostname
                        "X-Cache" "ORIGIN"
                        "X-Cache-Hits" "ORIGIN"}))))

(defn image-params
  [request request-type]
  (let [route-params (assoc (:route-params request) :request-type request-type)
        options (extract-query-opts request)]
    (assoc route-params :options options)))

; /lotr/3/35/Arwen.png/resize/10/10?debug=true
(defn app-routes
  [system]
  (-> (routes
        (GET thumbnail-route
             request
             (let [image-params (image-params request :thumbnail)]
               (if-let [thumb (u/get-or-generate-thumbnail system image-params)]
                 (create-image-response thumb)
                 (error-response 404 image-params))))
        (GET original-route
             request
             (let [image-params (image-params request :original)]
               (if-let [file (get-original (store system) image-params)]
                 (create-image-response file)
                 (error-response 404 image-params))))

        ; legacy routes
        (GET alr/thumbnail-route
             {route-params :route-params}
             (let [image-params (alr/route->thumb-map route-params)]
               (if (:unsupported image-params)
                 (status (response "unsupported thumbnail request") 302)
                 (if-let [thumb (u/get-or-generate-thumbnail system image-params)]
                   (create-image-response thumb)
                   (error-response 404 image-params)))))
        (GET alr/original-route
             {route-params :route-params}
             (let [image-params (alr/route->original-map route-params)]
               (if-let [file (get-original (store system) image-params)]
                 (create-image-response file)
                 (error-response 404 image-params))))
        (GET "/ping" [] "pong")
        (files "/static/")
        (not-found "Unrecognized request path!\n"))
      (wrap-params)
      (exception-catcher)
      (add-headers)))
