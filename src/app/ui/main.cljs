(ns app.ui.main
  (:require [keechma.next.helix.core :refer [with-keechma]]
            [keechma.next.helix.lib :refer [defnc]]
            [helix.core :as hx :refer [$ <>]]
            ["react" :as react]
            ["react-dom" :as rdom]
            [helix.dom :as d]
            [app.ui.pages.home :refer [Home]]
            [app.ui.pages.login :refer [Login]]))

(defnc MainRenderer
  [{:keechma/keys [use-sub]}]
  (let [{:keys [page]} (use-sub :router)]
    (d/div
      (case page
        "home" ($ Home)
        "login" ($ Login)
        (d/div "404")))))

(def Main (with-keechma MainRenderer))