(ns cglossa.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            cljsjs.jquery
            tooltip
            [devtools.core :as devtools]
            [cglossa.search-engines]    ; just to pull in implementations
            [cglossa.corpora.core]      ; just to pull in implementations
            [cglossa.react-adapters.bootstrap :as b]
            [cglossa.app :refer [app init get-models]])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:import [goog Throttle]))

(devtools/set-pref! :install-sanity-hints true)
(devtools/install!)

(defn narrow-view? []
  (< (.-innerWidth js/window) 768))

(defonce app-state {:narrow-view?        (r/atom (narrow-view?))
                    :show-metadata?      (r/atom nil)
                    :num-selected-texts  (r/atom nil)
                    :num-selected-tokens (r/atom nil)
                    :text-selection-info (r/atom nil)
                    :show-texts?         (r/atom false)
                    :show-results?       (r/atom false)
                    :results-view        {:view-type               (r/atom :concordance)
                                          :results                 (r/atom nil)
                                          :total                   (r/atom nil)
                                          :cpu-counts              (r/atom nil)
                                          :context-size            (r/atom 15)
                                          :page-no                 (r/atom nil)
                                          ;; This is the page selected in the result paginator;
                                          ;; it may differ from the one shown in the result table
                                          ;; until the selected page has been fetched from the server
                                          :paginator-page-no       (r/atom nil)
                                          ;; This is the value shown in the paginator text input.
                                          ;; It may differ from paginator-page-no while we are
                                          ;; manually editing the value, but will be set equal
                                          ;; to paginator-page-no when we hit Enter after editing
                                          ;; or we select a different page using the paging buttons.
                                          :paginator-text-val      (r/atom nil)
                                          ;; Set of result pages currently being fetched
                                          :fetching-pages          (r/atom nil)
                                          :result-showing-metadata (r/atom nil)
                                          :sort-key                (r/atom :position)
                                          :freq-attr               (r/atom #{})
                                          :freq-attr-sorted        (r/atom nil)
                                          :freq-res                (r/atom nil)
                                          :freq-case-sensitive     (r/atom false)
                                          :translations            (r/atom nil)
                                          :showing-download-popup? (r/atom false)
                                          :downloading?            (r/atom false)
                                          :media                   {:showing-media-popup? (r/atom false)
                                                                    :media-obj            (r/atom nil)
                                                                    :player-row-index     (r/atom nil)
                                                                    :current-player-type  (r/atom nil)
                                                                    :current-media-type   (r/atom nil)}
                                          :geo-map                 {:geo-data       (r/atom nil)
                                                                    :colored-phons  (r/atom nil)
                                                                    :selected-color (r/atom nil)}}
                    :search-view         {:view-type        (r/atom :simple)
                                          :queries          (r/atom nil)
                                          :query-ids        (r/atom nil)
                                          :num-random-hits  (r/atom nil)
                                          :random-hits-seed (r/atom nil)}
                    :searching?          (r/atom false)
                    :open-metadata-cat   (r/atom nil)
                    :num-resets          (r/atom 0)})

(defonce model-state {:corpus              (r/atom nil)
                      :metadata-categories (r/atom nil)
                      :search              (r/atom {})
                      :authenticated-user  (r/atom nil)})

;; Set :narrow-view in app-state whenever the window is resized (throttled to 200ms)
(def on-resize-throttle (Throttle. #(reset! (:narrow-view? app-state) (narrow-view?)) 200))
(.addEventListener js/window "resize" #(.fire on-resize-throttle))

(.tooltip (js/$ "body") #js {:selector "[data-toggle='tooltip']" :delay #js {:show 300 :hide 0}})

;; Don't re-init model state on hot reload
(defonce ^:private __init (init app-state model-state))

(defn ^:export main []
  (rdom/render
    [app app-state model-state]
    (.getElementById js/document "app")))

(main)
