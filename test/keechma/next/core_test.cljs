(ns keechma.next.core-test
  (:require
    [cljs.test :refer-macros [deftest is testing use-fixtures async]]
    [cljs.pprint :as pprint]
    [keechma.next.controller :as ctrl]
    [keechma.next.core :refer [start!]]))

(use-fixtures :once
              {:before (fn [] (js/console.clear))})

(defn log-cmd!
  ([ctrl cmd] (log-cmd! ctrl cmd nil))
  ([{controller-name :keechma.controller/name :keys [cmd-log$]} cmd _]
   (when cmd-log$
     (swap! cmd-log$ conj [controller-name cmd]))))

(derive :counter-1 :keechma/controller)
(derive :counter-2 :keechma/controller)
(derive :counter-3 :keechma/controller)

(defmethod ctrl/start :counter-1 [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  0)

(defmethod ctrl/receive :counter-1 [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :inc (swap! state$ inc)
    nil))

(defmethod ctrl/stop :counter-2 [ctrl state _]
  (log-cmd! ctrl :keechma.lifecycle/stop)
  (* 2 state))

(defmethod ctrl/start :counter-2 [ctrl {:keys [counter-1]}]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc counter-1))

(defmethod ctrl/receive :counter-2 [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :keechma.on/deps-change (reset! state$ (inc (:counter-1 payload)))
    nil))

(defmethod ctrl/start :counter-3 [ctrl {:keys [counter-2]}]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc counter-2))

(deftest send-1
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:counter-1 0} (get-derived-state)))
    (is (= 0 (get-derived-state :counter-1)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 1} (get-derived-state)))
    (is (= 1 (get-derived-state :counter-1)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [:counter-1 :inc]]
           @cmd-log$))
    (stop!)))

(deftest send-2
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}
                                   :counter-2 {:keechma.controller/params true
                                               :cmd-log$ cmd-log$
                                               :keechma.controller/deps [:counter-1]}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:counter-1 0 :counter-2 1} (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 1 :counter-2 2} (get-derived-state)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [:counter-2 :keechma.lifecycle/start]
            [:counter-2 :keechma.on/start]
            [:counter-1 :inc]
            [:counter-2 :keechma.on/deps-change]]
           @cmd-log$))
    (stop!)))

(deftest send-3
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}
                                   [:counter-2 1] {:keechma.controller/params true
                                                   :cmd-log$ cmd-log$
                                                   :keechma.controller/deps [:counter-1]}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:counter-1 0 [:counter-2 1] 1} (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 1] 2} (get-derived-state)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [[:counter-2 1] :keechma.lifecycle/start]
            [[:counter-2 1] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 1] :keechma.on/deps-change]]
           @cmd-log$))
    (stop!)))

(deftest send-4
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}
                                   [:counter-2] {:keechma.controller.factory/produce
                                                 (fn [{:keys [counter-1]}]
                                                   (->> (range counter-1 (+ 2 counter-1))
                                                        (map (fn [i] [(inc i) {:keechma.controller/params {:counter-1 counter-1}}]))
                                                        (into {})))
                                                 :keechma.controller/deps [:counter-1]
                                                 :cmd-log$ cmd-log$}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:counter-1 0 [:counter-2 1] 1 [:counter-2 2] 1} (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 2] 2 [:counter-2 3] 2} (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 2 [:counter-2 3] 3 [:counter-2 4] 3} (get-derived-state)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [[:counter-2 1] :keechma.lifecycle/start]
            [[:counter-2 1] :keechma.on/start]
            [[:counter-2 2] :keechma.lifecycle/start]
            [[:counter-2 2] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 1] :keechma.on/stop]
            [[:counter-2 2] :keechma.on/stop]
            [[:counter-2 2] :keechma.lifecycle/start]
            [[:counter-2 2] :keechma.on/start]
            [[:counter-2 3] :keechma.lifecycle/start]
            [[:counter-2 3] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 2] :keechma.on/stop]
            [[:counter-2 3] :keechma.on/stop]
            [[:counter-2 3] :keechma.lifecycle/start]
            [[:counter-2 3] :keechma.on/start]
            [[:counter-2 4] :keechma.lifecycle/start]
            [[:counter-2 4] :keechma.on/start]]
           @cmd-log$))
    (stop!)))

(deftest send-5
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}
                                   [:counter-2] {:keechma.controller.factory/produce
                                                 (fn [{:keys [counter-1]}]
                                                   (->> (range counter-1 (+ 2 counter-1))
                                                        (map (fn [i] [(inc i) {:keechma.controller/params 1}]))
                                                        (into {})))
                                                 :keechma.controller/deps [:counter-1]
                                                 :cmd-log$ cmd-log$}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:counter-1 0 [:counter-2 1] 1 [:counter-2 2] 1} (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 2] 2 [:counter-2 3] 1} (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 2 [:counter-2 3] 3 [:counter-2 4] 1} (get-derived-state)))
    (is (= [[:counter-1 :keechma.lifecycle/start]
            [:counter-1 :keechma.on/start]
            [[:counter-2 1] :keechma.lifecycle/start]
            [[:counter-2 1] :keechma.on/start]
            [[:counter-2 2] :keechma.lifecycle/start]
            [[:counter-2 2] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 1] :keechma.on/stop]
            [[:counter-2 2] :keechma.on/deps-change]
            [[:counter-2 3] :keechma.lifecycle/start]
            [[:counter-2 3] :keechma.on/start]
            [:counter-1 :inc]
            [[:counter-2 2] :keechma.on/stop]
            [[:counter-2 3] :keechma.on/deps-change]
            [[:counter-2 4] :keechma.lifecycle/start]
            [[:counter-2 4] :keechma.on/start]]
           @cmd-log$))
    (stop!)))

(deftest send-6
  (let [cmd-log$ (atom [])
        app {:keechma/controllers
             {:counter-1 {:keechma.controller/params true
                          :cmd-log$ cmd-log$}
              [:counter-2] {:keechma.controller.factory/produce
                            (fn [{:keys [counter-1]}]
                              (->> (range counter-1 (+ 2 counter-1))
                                   (map (fn [i] [(inc i) {:keechma.controller/params {:counter-1 counter-1}}]))
                                   (into {})))
                            :keechma.controller/deps [:counter-1]
                            :cmd-log$ cmd-log$}
              [:counter-3] {:keechma.controller.factory/produce
                            (fn [deps]
                              (->> deps
                                   (map (fn [[[_ counter-2-id] val]]
                                          [counter-2-id {:keechma.controller/params {:counter-2 val}}]))
                                   (into {})))
                            :keechma.controller/deps [[:counter-2]]
                            :cmd-log$ cmd-log$}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:counter-1 0
            [:counter-2 1] 1
            [:counter-2 2] 1
            [:counter-3 1] 2
            [:counter-3 2] 2}
           (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 1
            [:counter-2 2] 2
            [:counter-2 3] 2
            [:counter-3 2] 3
            [:counter-3 3] 3}
           (get-derived-state)))
    (send! :counter-1 :inc)
    (is (= {:counter-1 2
            [:counter-2 3] 3
            [:counter-2 4] 3
            [:counter-3 3] 4
            [:counter-3 4] 4}
           (get-derived-state)))
    (stop!)))

(derive :token :keechma/controller)
(derive :current-user :keechma/controller)
(derive :login :keechma/controller)

(defmethod ctrl/receive :token [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :update-token (reset! state$ payload)
    nil))

(defmethod ctrl/receive :current-user [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :update-user (reset! state$ payload)
    nil))

(defmethod ctrl/receive :login [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd payload)
  (case cmd
    :do-login (js/setTimeout #(ctrl/transact ctrl
                                             (fn []
                                               (ctrl/send ctrl :token :update-token "TOKEN")
                                               (ctrl/send ctrl :current-user :update-user {:id 1 :username "retro"}))))
    nil))

(deftest transactions
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:token {:keechma.controller/params true
                                           :cmd-log$ cmd-log$}
                                   :current-user {:keechma.controller/params true
                                                  :keechma.controller/deps [:token]
                                                  :cmd-log$ cmd-log$}
                                   :login {:keechma.controller/params (fn [{:keys [token]}] (not token))
                                           :keechma.controller/deps [:token :current-user]
                                           :cmd-log$ cmd-log$}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (async done
      (is (= {:token nil :current-user nil :login nil} (get-derived-state)))
      (is (= [[:token :keechma.on/start]
              [:current-user :keechma.on/start]
              [:login :keechma.on/start]]
             @cmd-log$))
      (send! :login :do-login)
      (js/setTimeout
        (fn []
          (is (= {:current-user {:id 1 :username "retro"}
                  :token "TOKEN"}
                 (get-derived-state)))
          (is (= [
                  ;; Start phase
                  [:token :keechma.on/start]
                  [:current-user :keechma.on/start]
                  [:login :keechma.on/start]
                  ;; Sending :do-login cmd to the :login controller
                  [:login :do-login]
                  ;; Wrapping :do-login action in transact block ensures correct ordering of cmds
                  [:token :update-token]
                  [:current-user :update-user]
                  ;; Only after the actions in the transact block are done, keechma resumes control and sends pending actions
                  [:current-user :keechma.on/deps-change]
                  [:login :keechma.on/stop]]
                 @cmd-log$))
          (done))))))

(derive :causal-1 :keechma/controller)
(derive :causal-2 :keechma/controller)
(derive :causal-3 :keechma/controller)

(defmethod ctrl/start :causal-1 [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  0)

(defmethod ctrl/receive :causal-1 [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (when (= :inc cmd)
    (swap! state$ inc)))

(defmethod ctrl/start :causal-2 [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc (:causal-1 deps-state)))

(defmethod ctrl/receive :causal-2 [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (when (= :keechma.on/deps-change cmd)
    (reset! state$ (inc (:causal-1 payload)))))

(defmethod ctrl/start :causal-3 [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  (inc (:causal-2 deps-state)))

(defmethod ctrl/receive :causal-3 [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (when (= :keechma.on/deps-change cmd)
    (reset! state$ (inc (:causal-2 payload)))))

(deftest causal
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:causal-1 {:keechma.controller/params true
                                              :cmd-log$ cmd-log$}
                                   :causal-2 {:keechma.controller/params true
                                              :keechma.controller/deps [:causal-1]
                                              :cmd-log$ cmd-log$}
                                   :causal-3 {:keechma.controller/params true
                                              :keechma.controller/deps [:causal-2]
                                              :cmd-log$ cmd-log$}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:causal-1 0 :causal-2 1 :causal-3 2} (get-derived-state)))
    (send! :causal-1 :inc)
    (is (= {:causal-1 1 :causal-2 2 :causal-3 3} (get-derived-state)))
    (send! :causal-1 :inc)
    (is (= {:causal-1 2 :causal-2 3 :causal-3 4} (get-derived-state)))
    (is (= [[:causal-1 :keechma.lifecycle/start]
            [:causal-1 :keechma.on/start]
            [:causal-2 :keechma.lifecycle/start]
            [:causal-2 :keechma.on/start]
            [:causal-3 :keechma.lifecycle/start]
            [:causal-3 :keechma.on/start]
            [:causal-1 :inc]
            [:causal-2 :keechma.on/deps-change]
            [:causal-3 :keechma.on/deps-change]
            [:causal-1 :inc]
            [:causal-2 :keechma.on/deps-change]
            [:causal-3 :keechma.on/deps-change]]
           @cmd-log$))))

(derive :user-role :keechma/controller)
(derive :user-posts :keechma/controller)
(derive :public-posts :keechma/controller)
(derive :user-role-tracker :keechma/controller)
(derive :current-post-id :keechma/controller)
(derive :post-detail :keechma/controller)
(derive :static :keechma/controller)

(defmethod ctrl/start :user-role [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :guest)

(defmethod ctrl/receive :user-role [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :login (reset! state$ :user)
    :logout (reset! state$ :guest)
    nil))

(defmethod ctrl/start :user-posts [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :user-posts)

(defmethod ctrl/start :public-posts [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :public-posts)

(defmethod ctrl/start :user-role-tracker [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  [[(:keechma.controller/name ctrl) (:user-role deps-state)]])

(defmethod ctrl/receive :user-role-tracker [{:keys [state$ deps-state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :keechma.on/deps-change (swap! state$ conj [(:keechma.controller/name ctrl) (:user-role payload)])
    nil))

(defmethod ctrl/receive :current-post-id [{:keys [state$] :as ctrl} cmd payload]
  (log-cmd! ctrl cmd)
  (case cmd
    :open (swap! state$ inc)
    :close (reset! state$ nil)
    nil))

(defmethod ctrl/start :post-detail [ctrl _ deps-state _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  [:post-detail (:current-post-id deps-state)])

(defmethod ctrl/start :static [ctrl _ _ _]
  (log-cmd! ctrl :keechma.lifecycle/start)
  :static)

(deftest subapps-1
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:user-role {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}}
             :keechma/apps {:public {:keechma/controllers {:posts {:keechma.controller/params true
                                                                   :keechma.controller/type :public-posts
                                                                   :cmd-log$ cmd-log$}}
                                     :keechma.app/should-run? (fn [{:keys [user-role]}] (= :guest user-role))
                                     :keechma.app/deps [:user-role]}
                            :user {:keechma/controllers {:posts {:keechma.controller/params true
                                                                 :keechma.controller/type :user-posts
                                                                 :cmd-log$ cmd-log$}}
                                   :keechma.app/should-run? (fn [{:keys [user-role]}] (= :user user-role))
                                   :keechma.app/deps [:user-role]}
                            :always-on {:keechma/controllers {:user-role-tracker {:keechma.controller/params true
                                                                                  :keechma.controller/deps [:user-role]
                                                                                  :cmd-log$ cmd-log$}
                                                              :user-role-tracker-guest {:keechma.controller/params (fn [{:keys [user-role]}] (= :guest user-role))
                                                                                        :keechma.controller/deps [:user-role]
                                                                                        :keechma.controller/type :user-role-tracker
                                                                                        :cmd-log$ cmd-log$}
                                                              :user-role-tracker-user {:keechma.controller/params (fn [{:keys [user-role]}] (= :user user-role))
                                                                                       :keechma.controller/deps [:user-role]
                                                                                       :keechma.controller/type :user-role-tracker
                                                                                       :cmd-log$ cmd-log$}}
                                        :keechma.app/should-run? (fn [{:keys [user-role]}] user-role)
                                        :keechma.app/deps [:user-role]}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:user-role :guest,
            :posts :public-posts,
            :user-role-tracker-guest [[:user-role-tracker-guest :guest]],
            :user-role-tracker [[:user-role-tracker :guest]]}
           (get-derived-state)))

    (send! :user-role :login)
    (is (= {:user-role :user,
            :posts :user-posts,
            :user-role-tracker [[:user-role-tracker :guest] [:user-role-tracker :user]],
            :user-role-tracker-user [[:user-role-tracker-user :user]]}
           (get-derived-state)))

    (send! :user-role :logout)
    (is (= {:user-role :guest,
            :user-role-tracker-guest [[:user-role-tracker-guest :guest]],
            :user-role-tracker
            [[:user-role-tracker :guest]
             [:user-role-tracker :user]
             [:user-role-tracker :guest]],
            :posts :public-posts}
           (get-derived-state)))

    (is (= [[:user-role :keechma.lifecycle/start]
            [:user-role :keechma.on/start]
            [:posts :keechma.lifecycle/start]
            [:user-role-tracker-guest :keechma.lifecycle/start]
            [:user-role-tracker-guest :keechma.on/start]
            [:user-role-tracker :keechma.lifecycle/start]
            [:user-role-tracker :keechma.on/start]
            [:user-role :login]
            [:user-role-tracker-user :keechma.lifecycle/start]
            [:user-role-tracker-user :keechma.on/start]
            [:user-role-tracker-guest :keechma.on/stop]
            [:user-role-tracker :keechma.on/deps-change]
            [:posts :keechma.lifecycle/start]
            [:user-role :logout]
            [:user-role-tracker-user :keechma.on/stop]
            [:user-role-tracker-guest :keechma.lifecycle/start]
            [:user-role-tracker-guest :keechma.on/start]
            [:user-role-tracker :keechma.on/deps-change]
            [:posts :keechma.lifecycle/start]]
           @cmd-log$))
    (stop!)))

(deftest subapps-2
  (let [cmd-log$ (atom [])
        app {:keechma/controllers {:user-role {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}}
             :keechma/apps
             {:user
              {:keechma/controllers {:posts {:keechma.controller/params true
                                             :keechma.controller/type :public-posts
                                             :cmd-log$ cmd-log$}
                                     :current-post-id {:keechma.controller/params true
                                                       :cmd-log$ cmd-log$}}
               :keechma.app/should-run? (fn [{:keys [user-role]}] (= :user user-role))
               :keechma.app/deps [:user-role]
               :keechma/apps
               {:post-details
                {:keechma/controllers {:post-detail {:keechma.controller/params (fn [{:keys [current-post-id]}] current-post-id)
                                                     :keechma.controller/deps [:current-post-id]
                                                     :cmd-log$ cmd-log$}
                                       :static {:keechma.controller/params true
                                                :cmd-log$ cmd-log$}}
                 :keechma.app/should-run? (fn [{:keys [current-post-id]}] current-post-id)
                 :keechma.app/deps [:current-post-id]}}}}}
        {:keys [send! get-derived-state stop!]} (start! app)]
    (is (= {:user-role :guest} (get-derived-state)))

    (send! :user-role :login)
    (is (= {:user-role :user
            :posts :public-posts
            :current-post-id nil} (get-derived-state)))

    (send! :current-post-id :open)
    (is (= {:user-role :user
            :posts :public-posts
            :current-post-id 1
            :static :static
            :post-detail [:post-detail 1]}
           (get-derived-state)))

    (send! :current-post-id :open)
    (is (= {:user-role :user
            :posts :public-posts
            :current-post-id 2
            :static :static
            :post-detail [:post-detail 2]}
           (get-derived-state)))

    (send! :current-post-id :close)
    (is (= {:user-role :user,
            :posts :public-posts,
            :current-post-id nil}
           (get-derived-state)))

    (is (= [[:user-role :keechma.lifecycle/start]
            [:user-role :keechma.on/start]
            [:user-role :login]
            [:posts :keechma.lifecycle/start]
            [:current-post-id :keechma.on/start]
            [:current-post-id :open]
            [:static :keechma.lifecycle/start]
            [:post-detail :keechma.lifecycle/start]
            [:current-post-id :open]
            [:post-detail :keechma.lifecycle/start]
            [:current-post-id :close]]
           @cmd-log$))
    (stop!)))

(deftest subscriptions-1
  (let [cmd-log$ (atom [])
        state$ (atom {})
        app {:keechma/controllers {:counter-1 {:keechma.controller/params true
                                               :cmd-log$ cmd-log$}
                                   [:counter-2] {:keechma.controller.factory/produce
                                                 (fn [{:keys [counter-1]}]
                                                   (->> (range counter-1 (+ 2 counter-1))
                                                        (map (fn [i] [(inc i) {:keechma.controller/params 1}]))
                                                        (into {})))
                                                 :keechma.controller/deps [:counter-1]
                                                 :cmd-log$ cmd-log$}}}
        {:keys [send! get-derived-state stop! subscribe!]} (start! app)
        s! (fn [controller] (subscribe! controller #(swap! state$ assoc controller %)))]

    (s! :counter-1)
    (s! [:counter-2 1])
    (s! [:counter-2 2])
    (s! [:counter-2 3])
    (s! [:counter-2 4])

    (send! :counter-1 :inc)
    (is (= {:counter-1 1 [:counter-2 2] 2 [:counter-2 3] 1} (get-derived-state)))
    (is (= {:counter-1 1, [:counter-2 1] nil, [:counter-2 2] 2, [:counter-2 3] 1, [:counter-2 4] nil} @state$))
    (send! :counter-1 :inc)
    (is (= {:counter-1 2 [:counter-2 3] 3 [:counter-2 4] 1} (get-derived-state)))
    (is (= {:counter-1 2, [:counter-2 1] nil, [:counter-2 2] nil, [:counter-2 3] 3, [:counter-2 4] 1} @state$))

    (stop!)))

(derive :causal-a :keechma/controller)
(derive :causal-b :keechma/controller)

(defmethod ctrl/start :causal-a [_ _ _ _]
  1)

(defmethod ctrl/receive :causal-a [{:keys [state$ meta-state$] :as ctrl} cmd payload]
  (swap! meta-state$ update :commands #(vec (conj (or % []) cmd)))
  (case cmd
    :inc (swap! state$ inc)
    nil))

(defmethod ctrl/start :causal-b [_ _ {:keys [causal-a]} _]
  1)

(defmethod ctrl/receive :causal-b [{:keys [state$ meta-state$] :as ctrl} cmd payload]
  (swap! meta-state$ update :commands #(vec (conj (or % []) cmd)))
  (case cmd
    :inc (swap! state$ inc)
    :update-meta (swap! meta-state$ assoc :updated-meta? true)
    nil))

(defmethod ctrl/derive-state :causal-b [_ state {:keys [causal-a]}]
  (+ state causal-a))

(deftest subscriptions-2
  (let [state$ (atom {})
        meta-sub-called-count$ (atom {})
        app {:keechma/controllers {:causal-a {:keechma.controller/params true}
                                   :causal-b {:keechma.controller/params true
                                              :keechma.controller/deps [:causal-a]}}}
        {:keys [send! get-derived-state stop! subscribe! subscribe-meta!]} (start! app)
        s! (fn [controller] (subscribe! controller #(swap! state$ assoc controller %)))
        sm! (fn [controller] (subscribe-meta! controller (fn [val]
                                                           (swap! meta-sub-called-count$ update controller inc)
                                                           (swap! state$ assoc [:meta controller] val))))]
    (s! :causal-a)
    (s! :causal-b)
    (sm! :causal-a)
    (sm! :causal-b)

    (is (= {:causal-a 1 :causal-b 2} (get-derived-state)))

    (send! :causal-a :inc)

    (is (= {:causal-a 2 :causal-b 3} (get-derived-state)))
    (is (= {:causal-a 2
            :causal-b 3
            [:meta :causal-a] {:commands [:keechma.on/start :inc]}
            [:meta :causal-b] {:commands [:keechma.on/start :keechma.on/deps-change]}}
           @state$))
    (is (= {:causal-a 1 :causal-b 1} @meta-sub-called-count$))

    (send! :causal-b :update-meta)

    (is (= {:causal-a 2,
            :causal-b 3,
            [:meta :causal-a] {:commands [:keechma.on/start :inc]},
            [:meta :causal-b]
            {:commands [:keechma.on/start :keechma.on/deps-change :update-meta],
             :updated-meta? true}}
           @state$))

    (stop!)))

