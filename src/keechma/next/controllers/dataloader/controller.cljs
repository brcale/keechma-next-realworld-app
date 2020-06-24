(ns keechma.next.controllers.dataloader.controller
  (:require [keechma.next.controller :as ctrl]
            [keechma.next.controllers.dataloader.protocols :as pt :refer [IDataloaderApi]]
            [keechma.next.toolbox.pipeline :as pp :refer [make-runtime in-pipeline?] :refer-macros [pipeline!]]
            [cljs.core.async :refer [alts! timeout <! close! chan]]
            [goog.object :as gobj]
            [promesa.core :as p]
            [medley.core :refer [dissoc-in]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(def default-config
  {:keechma.dataloader/evict-interval (* 1000 60)           ;; Evict every minute
   :keechma.dataloader/cache-size 1000})

(def default-request-options
  {:keechma.dataloader/max-age                0
   :keechma.dataloader/max-stale              0
   :keechma.dataloader/no-store               true
   :keechma.dataloader/stale-while-revalidate false
   :keechma.dataloader/stale-if-error         false})

(defn get-time-now []
  (js/Math.floor (/ (js/Date.now) 1000)))

(defn assoc-inflight [cache loader req-opts req]
  (assoc-in cache [:inflight [loader req-opts]] req))

(defn dissoc-inflight [cache loader req-opts]
  (dissoc-in cache [:inflight [loader req-opts]]))

(defn assoc-cache [cache time-now loader req-opts res]
  (assoc-in cache [:cache [loader req-opts]] {:data res :resolved-at time-now :touched-at time-now}))

(defn dissoc-cache [cache loader req-opts]
  (dissoc-in cache [:cache [loader req-opts]]))

(defn make-req [cache* loader req-opts dataloader-opts]
  (let [current-req (get-in @cache* [:inflight [loader req-opts]])
        req (or current-req (loader req-opts))]
    (swap! cache* assoc-inflight loader req-opts req)
    (->> req
         (p/map (fn [res]
                  (let [time-now (get-time-now)]
                    (if (:keechma.dataloader/no-store dataloader-opts)
                      (swap! cache* (fn [cache]
                                      (-> cache
                                          (dissoc-inflight loader req-opts)
                                          (dissoc-cache loader req-opts))))
                      (swap! cache* (fn [cache]
                                      (-> cache
                                          (dissoc-inflight loader req-opts)
                                          (assoc-cache time-now loader req-opts res)))))
                    res)))
         (p/error (fn [err]
                    (let [cached (get-in @cache* [:cache [loader req-opts]])]
                      (if (and cached (:keechma.dataloader/stale-if-error dataloader-opts))
                        cached
                        (throw err))))))))

(defn pp-anonymize-interpreter-state [interpreter-state]
  (mapv
    (fn [stack]
      (assoc stack :ident [(keyword (gensym `pipeline)) (keyword (gensym `pipeline/instance))]))
    interpreter-state))

(defn pp-set-interpreter-value [interpreter-state value]
  (assoc-in interpreter-state [0 :state :value] value))

(defn pp-set-revalidate [interpreter-state req]
  (let [interpreter-state-without-last (vec (drop-last interpreter-state))
        last-stack (last interpreter-state)
        state (:state last-stack)
        revalidate-interpreter-state (pp-set-interpreter-value interpreter-state req)]

    (conj interpreter-state-without-last
          (-> last-stack
              (update-in [:state :pipeline (:block state)]
                         #(concat % [(-> revalidate-interpreter-state
                                         pp/interpreter-state->pipeline
                                         pp/detach
                                         constantly)]))))))

(defn make-req-stale-while-revalidate [cache* cached loader req-opts dataloader-opts]
  (pp/fn->pipeline
    (fn [_ runtime _ _]
      (let [{:keys [get-state]} runtime
            {:keys [interpreter-state*]} (get-state)
            interpreter-state @interpreter-state*]
        (pp/interpreter-state->resumable
          (-> interpreter-state
              (pp-anonymize-interpreter-state)
              (pp-set-interpreter-value cached)
              (pp-set-revalidate (make-req cache* loader req-opts dataloader-opts))))))))

(defn loading-strategy [cached dataloader-opts]
  (let [{:keechma.dataloader/keys [max-age max-stale stale-while-revalidate]} dataloader-opts
        resolved-at (:resolved-at cached)
        age (- (get-time-now) resolved-at)
        is-fresh (< age max-age)
        is-stale-usable (if (true? max-stale) true (< age (+ max-stale max-age)))]
    (cond
      is-fresh :cache
      (and is-stale-usable stale-while-revalidate (in-pipeline?)) :stale-while-revalidate
      is-stale-usable :cache
      :else :req)))

(deftype DataloaderApi [ctrl]
  IDataloaderApi
  (req [this loader req-opts]
    (pt/req this loader req-opts {}))
  (req [this loader req-opts dataloader-opts]
    (let [cache* (::cache* ctrl)
          cached (pt/cached this loader req-opts)]
      (if-not cached
        (make-req cache* loader req-opts dataloader-opts)
        (case (loading-strategy cached dataloader-opts)
          :cache (:data cached)
          :req (make-req cache* loader req-opts dataloader-opts)
          :stale-while-revalidate (make-req-stale-while-revalidate cache* (:data cached) loader req-opts dataloader-opts)
          nil))))
  (cached [_ loader req-opts]
    (let [cache* (::cache* ctrl)]
      (get-in @cache* [:cache [loader req-opts]]))))

(defn request-idle-callback-chan! []
  (let [cb-chan (chan)]
    (if-let [req-idle-cb (gobj/get js/window "requestIdleCallback")]
      (req-idle-cb #(close! cb-chan))
      (close! cb-chan))
    cb-chan))

(defn evict-lru [cache cache-size]
  (->> (map identity cache)
       (sort-by #(get-in % [1 :touched-at]))
       reverse
       (take cache-size)
       (into {})))

(defn start-evict-lru! [ctrl]
  (let [cache* (::cache* ctrl)
        interval (:keechma.dataloader/evict-interval ctrl) ;; Vacuum EntityDB every 10 minutes
        cache-size (:keechma.dataloader/cache-size ctrl)
        poison-chan (chan)]
    (go-loop []
             (let [[_ c] (alts! [poison-chan (timeout interval)])]
               (when-not (= c poison-chan)
                 (<! (request-idle-callback-chan!))
                 (swap! cache* evict-lru cache-size)
                 (recur))))
    (fn []
      (close! poison-chan))))

(defmethod ctrl/init :keechma/dataloader [ctrl]
  (let [ctrl' (-> (merge default-config ctrl)
                  (update :keechma.dataloader/request-options #(merge default-request-options %))
                  (assoc ::cache* (atom {})))]
    (assoc ctrl'
      ::stop-evict-lru! (start-evict-lru! ctrl'))))

(defmethod ctrl/api :keechma/dataloader [ctrl]
  (let [{:keys [invoke]} (::pipeline-runtime ctrl)]
    (->DataloaderApi ctrl)))

(defmethod ctrl/terminate :keechma/dataloader [ctrl]
  (let [stop-evict-lru! (::stop-evict-lru! ctrl)
        cache* (::cache* ctrl)]
    (stop-evict-lru!)
    (reset! cache* nil)))