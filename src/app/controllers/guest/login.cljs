(ns app.controllers.guest.login
  (:require [keechma.next.controller :as ctrl]
            [keechma.next.controllers.pipelines :as pipelines]
            [keechma.next.toolbox.pipeline :refer [pswap! preset!] :refer-macros [pipeline!]]
            [app.api :as api]
            [promesa.core :as p]
            [keechma.next.controllers.form :as form]
            [app.validators :as v]))



(derive :guest/login ::pipelines/controller)

(def pipelines
  {:keechma.form/get-data (pipeline! [value ctrl]
                            {:email "conduit123@mailinator.com"
                             :password "1234567890"})
   :keechma.form/submit-data (pipeline! [value ctrl]
                               (api/login value)
                               (ctrl/send ctrl :jwt :set (:token value))
                               (ctrl/send ctrl :router :redirect! {:page "home"}))})

(defmethod ctrl/prep :guest/login [ctrl]
  (pipelines/register ctrl (form/wrap pipelines (v/to-validator {:email [:email :not-empty]
                                                                 :password [:not-empty :ok-password]}))))