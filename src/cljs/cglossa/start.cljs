(ns cglossa.start
  (:require [cglossa.search-views.shared :refer [search-inputs]]
            [cglossa.react-adapters.bootstrap :as b]))

(defn- top []
  [:div.row>div.col-md-3
   [b/buttontoolbar {:style {:margin-bottom 20}}
    [b/button {:bs-style "primary" :bs-size "xsmall" :title "Reset form"} "Reset form"]]])

(defn- corpus-info [_ {:keys [corpus]}]
  ;; Remove the background image (gradient, really) set by bootstrap-theme,
  ;; since the unthemed well is actually nicer.
  [:div.row.corpus-info>div.col-md-12
   [:div.well {:style {:background-image "url()"}}
    [:h2
     (:name @corpus)
     (when-let [logo (:logo @corpus)]
       [:img.corpus-logo {:src (str "img/" logo)}])]]])

(defn start [a m]
  [:div
   [top]
   [corpus-info a m]
   [search-inputs a m]])
