(ns keechma.next.controllers.entitydb
  (:require [entitydb.entitydb :as edb :refer-macros [export-api!]]
            [entitydb.query :as q]
            [keechma.next.controller :as ctrl]
            [cljs.core.async :refer [<! timeout chan alts! close!]]
            [goog.object :as gobj])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(derive :keechma/entitydb :keechma/controller)

(defprotocol IEntityDbApi
  (insert-entity! [_ entity-type data])
  (insert-many! [_ entity-type entities])
  (insert-named! [_ entity-type entity-name data] [_ entity-type entity-name data n-meta])
  (insert-collection! [_ entity-type collection-name data] [_ entity-type collection-name data c-meta])
  (remove-entity! [_ entity-type id])
  (remove-named! [_ entity-name])
  (remove-collection! [_ collection-name])
  (get-entity* [_ entity-type id] [_ entity-type id queries])
  (get-named* [_ entity-name] [_ entity-name queries])
  (get-collection* [_ collection-name] [_ collection-name queries]))

(def insert-entity edb/insert)
(def insert-many edb/insert-many)
(def insert-named edb/insert-named-item)
(def insert-collection edb/insert-collection)
(def remove-entity edb/remove-by-id)
(def remove-named edb/remove-named-item)
(def remove-collection edb/remove-collection)
(def get-entity edb/get-by-id)
(def get-named edb/get-named-item)
(def get-collection edb/get-collection)

(def rel-include q/include)
(def rel-reverse-include q/reverse-include)
(def rel-recur-on q/recur-on)
(def rel-switch q/switch)

(defn get-read-only-cursor [state* query-fn]
  (reify
    IDeref
    (-deref [_] (query-fn @state*))))

(defn request-idle-callback-chan! []
  (let [cb-chan (chan)]
    (if-let [req-idle-cb (gobj/get js/window "requestIdleCallback")]
      (req-idle-cb #(close! cb-chan))
      (close! cb-chan))
    cb-chan))

(defn start-vacuum! [{:keys [state*] :as ctrl}]
  (let [interval (or (:keechma.entitydb.vacuum/interval ctrl) (* 10 60 1000)) ;; Vacuum EntityDB every 10 minutes
        poison-chan (chan)]
    (go-loop []
      (let [[_ c] (alts! [poison-chan (timeout interval)])]
        (when-not (= c poison-chan)
          (<! (request-idle-callback-chan!))
          (swap! state* edb/vacuum)
          (recur))))
    (fn []
      (close! poison-chan))))

(defmethod ctrl/init :keechma/entitydb [ctrl]
  (assoc ctrl :keechma.entitydb.vacuum/stop! (start-vacuum! ctrl)))

(defmethod ctrl/api :keechma/entitydb [{:keys [state*] :as ctrl}]
  (reify
    IEntityDbApi
    (insert-entity! [_ entity-type data]
      (ctrl/transact ctrl #(swap! state* insert-entity entity-type data))
      nil)
    (insert-many! [_ entity-type entities]
      (ctrl/transact ctrl #(swap! state* insert-many entity-type entities))
      nil)
    (insert-named! [_ entity-type entity-name data]
      (ctrl/transact ctrl #(swap! state* insert-named entity-type entity-name data))
      nil)
    (insert-named! [_ entity-type entity-name data n-meta]
      (ctrl/transact ctrl #(swap! state* insert-named entity-type entity-name data n-meta))
      nil)
    (insert-collection! [_ entity-type collection-name data]
      (ctrl/transact ctrl #(swap! state* insert-collection entity-type collection-name data))
      nil)
    (insert-collection! [_ entity-type collection-name data c-meta]
      (ctrl/transact ctrl #(swap! state* insert-collection entity-type collection-name data c-meta))
      nil)
    (remove-entity! [_ entity-type id]
      (ctrl/transact ctrl #(swap! state* remove-entity entity-type id))
      nil)
    (remove-named! [_ entity-name]
      (ctrl/transact ctrl #(swap! state* remove-named entity-name))
      nil)
    (remove-collection! [_ collection-name]
      (ctrl/transact ctrl #(swap! state* remove-collection collection-name))
      nil)
    (get-entity* [_ entity-type id]
      (get-read-only-cursor state* #(get-entity % entity-type id)))
    (get-entity* [_ entity-type id queries]
      (get-read-only-cursor state* #(get-entity % entity-type id queries)))
    (get-named* [_ entity-name]
      (get-read-only-cursor state* #(get-named % entity-name)))
    (get-named* [_ entity-name queries]
      (get-read-only-cursor state* #(get-named % entity-name queries)))
    (get-collection* [_ collection-name]
      (get-read-only-cursor state* #(get-collection % collection-name)))
    (get-collection* [_ collection-name queries]
      (get-read-only-cursor state* #(get-collection % collection-name queries)))))

(defmethod ctrl/start :keechma/entitydb [ctrl _ _ _]
  (edb/insert-schema {} (:keechma.entitydb/schema ctrl)))

(defmethod ctrl/terminate :keechma/entitydb [ctrl]
  (when-let [stop-vacuum! (:keechma.entitydb.vacuum/stop! ctrl)]
    (stop-vacuum!)))