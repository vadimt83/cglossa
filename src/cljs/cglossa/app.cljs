(ns cglossa.app
  (:require [cljsjs.react]
            [reagent.core :as r]
            [cglossa.shared :refer [showing-metadata?]]
            [cglossa.metadata-list :refer [metadata-list]]
            [cglossa.start :refer [start]]
            [cglossa.results :refer [results]]
            [cglossa.react-adapters.bootstrap :as b]))

(defn- header []
  [b/navbar {:fixedTop true :brand "Glossa"}])

(defn- main-area [{{:keys [show-results?]} :results-view :as a} m]
  [:div.container-fluid {:style {:padding-left 50}}
   [:div.row>div#main-content.col-sm-12
    (if @show-results?
      [results a m]
      [start a m])]])

(defn app [a {:keys [corpus] :as m}]
  [:div
   [header]
   (when @corpus
     [:div.table-display
      [:div.table-row
       ^{:key "metadata-list"}
       [:div.table-cell.metadata {:style {:max-width (if (showing-metadata? a m) 170 0)}}
        [metadata-list a m]]
       [:div.table-cell
        [main-area a m]]]])
   [:div.app-footer>img.textlab-logo {:src "img/tekstlab.gif"}]])
