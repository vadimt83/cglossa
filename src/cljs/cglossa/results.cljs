(ns cglossa.results
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [cglossa.shared :refer [page-size spinner-overlay search! top-toolbar
                                    cleanup-result reset-results! queries->param]]
            [cglossa.search-views.shared :refer [search-inputs]]
            [cglossa.react-adapters.bootstrap :as b]
            geo-distribution-map))

(defn- results-info [{{:keys [total results]} :results-view searching? :searching?}]
  [:span
   [:div.col-sm-6 {:style {:margin-bottom 10}}
    (cond
      (pos? @total)
      (let [npages    (-> (/ @total page-size) Math/ceil int)
            pages-str (if (= npages 1) " page" " pages")]
        (if @searching?
          (str "Showing the first " @total " matches (" npages pages-str "); searching for more...")
          (str "Found " @total " matches (" npages " pages)")))

      (and (zero? @total) (not @searching?))
      "No matches found"

      :else
      (when @searching? "Searching..."))]
   [:div.col-sm-1
    ;; Only show the spinner when we are searching AND have already found some results
    ;; so as to avoid showing spinners both here and over the result table at the same time
    ;; (since we show a spinner over the result table until we have found some results)
    [spinner-overlay {:spin? (and @searching? (seq @results)) :top 10}]]])

(defn- last-page-no [total]
  (-> (/ total page-size) Math/ceil int))

(def ^:private result-window-halfsize 1)

(defn- fetch-result-window!
  "Fetches a window of search result pages centred on centre-page-no. Ignores pages that have
  already been fetched or that are currently being fetched in another request (note that such
  pages can only be located at the edges of the window, and not as 'holes' within the window,
  since they must have been fetched as part of an earlier window)."
  [{{:keys [results total cpu-counts fetching-pages sort-key]} :results-view}
   {:keys [corpus search] :as m}
   centre-page-no]
  ;; Enclose the whole procedure in a go block. This way, the function will return the channel
  ;; returned by the go block, which will receive the value of the body of the go block when
  ;; it is done parking on channels. Hence, by reading from that channel we know when the
  ;; results have arrived.
  (go
    (let [;; Make sure the edges of the window are between 1 and last-page-no
          start-page (max (- centre-page-no result-window-halfsize) 1)
          end-page   (min (+ centre-page-no result-window-halfsize) (last-page-no @total))
          page-nos   (as-> (range start-page (inc end-page)) $
                           ;; Ignore pages currently being fetched by another request
                           (remove #(contains? @fetching-pages %) $)
                           ;; Ignore pages that have already been fetched
                           (remove #(contains? @results %) $)
                           ;; Create a new sequence to make sure we didn't create any "holes" in
                           ;; it (although that should not really happen in practice since we
                           ;; always fetch whole windows of pages)
                           (if (empty? $)
                             $
                             (range (first $) (inc (last $)))))]
      (if (empty? page-nos)
        ;; All pages are either being fetched or already fetched, so just return a keyword on the
        ;; channel returned by the go block and hence the function
        ::no-pages-to-fetch

        (let [;; Register the pages as being fetched
              _            (swap! fetching-pages #(apply conj % page-nos))
              ;; Calculate the first and last result index (zero-based) to request from the server
              first-result (* page-size (dec (first page-nos)))
              last-result  (dec (* page-size (last page-nos)))
              results-ch   (http/get "/results" {:query-params {:corpus-id  (:id @corpus)
                                                                :search-id  (:id @search)
                                                                :start      first-result
                                                                :end        last-result
                                                                :cpu-counts (str (vec @cpu-counts))
                                                                :sort-key   (name @sort-key)}})
              ;; Park until results are available on the core.async channel
              {:keys [status success] req-result :body} (<! results-ch)]
          ;; Remove the pages from the set of pages currently being fetched
          (swap! fetching-pages #(apply disj % page-nos))
          (if-not success
            (.log js/console status)
            ;; Add the fetched pages to the top-level results ratom
            (swap! results merge (zipmap page-nos
                                         (partition-all page-size
                                                        (map (partial cleanup-result m)
                                                             req-result)))))
          ;; This keyword will be put on the channel returned by the go block
          ;; and hence the function
          ::fetched-pages)))))

(defn- sort-button [{{:keys [total sort-key]} :results-view
                     :keys                    [searching?] :as a} m]
  (let [sk        @sort-key
        on-select (fn [_ event-key]
                    (reset! sort-key (keyword event-key))
                    (reset-results! a)
                    (fetch-result-window! a m 1))]
    [b/dropdownbutton {:id       "sort-button"
                       :title    "Sort"
                       :bs-size  "small"
                       :disabled (or @searching?
                                     (nil? @total)
                                     (zero? @total))
                       :style    {:margin-bottom 10}}
     [b/menuitem {:event-key :position, :on-select on-select}
      (when (= sk :position) [b/glyphicon {:glyph "ok"}]) "  By corpus position"]
     [b/menuitem {:event-key :match, :on-select on-select}
      (when (= sk :match) [b/glyphicon {:glyph "ok"}]) "  By match"]
     [b/menuitem {:event-key :left-immediate, :on-select on-select}
      (when (= sk :left-immediate) [b/glyphicon {:glyph "ok"}]) "  By immediate left context"]
     [b/menuitem {:event-key :left-wide, :on-select on-select}
      (when (= sk :left-wide) [b/glyphicon {:glyph "ok"}]) "  By wider left context (10 tokens)"]
     [b/menuitem {:event-key :right-immediate, :on-select on-select}
      (when (= sk :right-immediate) [b/glyphicon {:glyph "ok"}]) "  By immediate right context"]
     [b/menuitem {:event-key :right-wide, :on-select on-select}
      (when (= sk :right-wide) [b/glyphicon {:glyph "ok"}]) "  By wider right context (10 tokens)"]]))


(defn- statistics-button [{{freq-attr :freq-attr} :results-view} m]
  (let [on-select #(reset! freq-attr (keyword %1))]
    [b/dropdownbutton {:title "Statistics"}
     [b/menuitem {:header true} "Frequencies"]
     [b/menuitem {:event-key :word, :on-select on-select} "Word forms"]
     [b/menuitem {:event-key :lemma, :on-select on-select} "Lemmas"]
     [b/menuitem {:event-key :pos, :on-select on-select} "Parts-of-speech"]]))


(defn- pagination [{{:keys [results total page-no paginator-page-no
                            paginator-text-val fetching-pages]} :results-view :as a} m]
  (let [fetching?   (seq @fetching-pages)
        set-page    (fn [e n]
                      (.preventDefault e)
                      ;; Don't allow switching to a new page while we are in the processing of
                      ;; fetching one or more pages, since the user may start clicking lots of
                      ;; times, generating lots of concurrent requests
                      (when-not fetching?
                        (let [new-page-no (js/parseInt n)
                              last-page   (last-page-no @total)]
                          (when (<= 1 new-page-no last-page)
                            ;; Set the value of the page number shown in the paginator; it may
                            ;; differ from the page shown in the result table until we have
                            ;; actually fetched the data from the server
                            (reset! paginator-page-no new-page-no)
                            (reset! paginator-text-val new-page-no)
                            (if (contains? @results new-page-no)
                              (do
                                ;; If the selected result page has already been fetched from the
                                ;; server, it can be shown in the result table immediately
                                (reset! page-no new-page-no)
                                ;; If necessary, fetch any result pages in a window centred around
                                ;; the selected page in order to speed up pagination to nearby
                                ;; pages. No need to wait for it to finish though.
                                (fetch-result-window! a m new-page-no))
                              (go
                                ;; Otherwise, we need to park until the results from the server
                                ;; arrive before setting the page to be shown in the result table
                                (<! (fetch-result-window! a m new-page-no))
                                ;; Don't show the fetched page if we have already selected another
                                ;; page in the paginator while we were waiting for the request
                                ;; to finish
                                (when (= new-page-no @paginator-page-no)
                                  (reset! page-no new-page-no))))))))
        on-key-down (fn [e]
                      (when (= "Enter" (.-key e))
                        (if (str/blank? @paginator-text-val)
                          ;; If the text field is blank when we hit Enter, set its value
                          ;; to the last selected page number instead
                          (reset! paginator-text-val @paginator-page-no)
                          ;; Otherwise, make sure the entered number is within valid
                          ;; bounds and set it to be the new selected page
                          (let [last-page (last-page-no @total)
                                new-val   (as-> @paginator-text-val v
                                                (js/parseInt v)
                                                (if (pos? v) v 1)
                                                (if (<= v last-page) v last-page))]
                            (set-page e new-val)))))]
    (when (> @total page-size)
      [:div.pull-right
       [:nav
        [:ul.pagination.pagination-sm
         [:li {:class-name (when (or (= @paginator-page-no 1) fetching?)
                             "disabled")}
          [:a {:href       "#"
               :aria-label "First"
               :title      "First"
               :on-click   #(set-page % 1)}
           [:span {:aria-hidden "true"} "«"]]]
         [:li {:class-name (when (or (= @paginator-page-no 1) fetching?)
                             "disabled")}
          [:a {:href       "#"
               :aria-label "Previous"
               :title      "Previous"
               :on-click   #(set-page % (dec @paginator-page-no))}
           [:span {:aria-hidden "true"} "‹"]]]
         [:li
          [:input.form-control.input-sm
           {:style       {:text-align    "right"
                          :width         60
                          :float         "left"
                          :height        29
                          :border-radius 0}
            :value       @paginator-text-val
            :on-click    #(.select (.-target %))
            :on-change   (fn [e]
                           (let [v (.-target.value e)]
                             ;; Allow the text field to contain a number or be blank while
                             ;; we are editing it
                             (when (or (integer? (js/parseInt v)) (str/blank? v))
                               (reset! paginator-text-val v))))
            :on-key-down on-key-down}]]
         [:li {:class-name (when (or (= @paginator-page-no (last-page-no @total)) fetching?)
                             "disabled")}
          [:a {:href       "#"
               :aria-label "Next"
               :title      "Next"
               :on-click   #(set-page % (inc @paginator-page-no))}
           [:span {:aria-hidden "true"} "›"]]]
         [:li {:class-name (when (or (= @paginator-page-no (last-page-no @total)) fetching?)
                             "disabled")}
          [:a {:href       "#"
               :aria-label "Last"
               :title      "Last"
               :on-click   #(set-page % (last-page-no @total))}
           [:span {:aria-hidden "true"} "»"]]]]]])))

(defn- concordance-toolbar [a m]
  [:div.row {:style {:margin-top 15}}
   [:div.col-sm-12
    [b/buttontoolbar
     [sort-button a m]
     [pagination a m]]]])

(defmulti result-links
  "Multimethod for links shown to the left of each search result in a
  concordance table. It accepts a model/domain state map and the result as
  arguments and dispatches to the correct method based on the value of :code in
  the corpus map found in the model/domain state map. The :default case returns
  nil."
  (fn [_ {corpus :corpus} _] (:code @corpus)))

(defmethod result-links :default [_ _ _ _] nil)

(defmulti concordance-table
  "Multimethod that accepts two arguments - an app state map and a
  model/domain state map - and dispatches to the correct method based
  on the value of :search-engine in the corpus map found in the
  model/domain state map. The :default case implements CWB support."
  (fn [_ {corpus :corpus}] (:search-engine @corpus)))

(defn- concordances [a m]
  [:div.container-fluid {:style {:padding-left 0 :padding-right 0}}
   [concordance-toolbar a m]
   [concordance-table a m]
   [:div.row
    [:div.col-sm-12
     [pagination a m]]]])

(defn- geo-map-colorpicker [selected-colorpicker color]
  [:button.btn.btn-xs.colorpicker
   {:style    {:background-color (name color)
               :border-color     (if (#{:white :red :orange :yellow} color) "black" "red")
               :border-width     (if (= @selected-colorpicker color) "4px" "1px")}
    :on-click #(reset! selected-colorpicker color)}])

(defn- geo-map [{{:keys [queries]} :search-view} {:keys [corpus search]} view-type]
  (r/with-let
    [selected-colorpicker (r/atom nil)]
    ;; react-bootstrap renders and mounts the contents of all tabs immediately (i.e., no
    ;; lazy rendering), but instantiating a Google Map while its container is hidden doesn't
    ;; work. Hence, we don't render the GeoDistributionMap component unless the use has
    ;; actually selected the geo-map tab.
    (when (= @view-type "geo-map")
      (let [q          (queries->param corpus @queries)
            results-ch (http/post "/geo-distr" {:json-params {:corpus-id (:id @corpus)
                                                              :search-id (:id @search)
                                                              :queries q
                                                              }})])
      [:div.geo-map
       [:div {:style {:padding 5}}
        [geo-map-colorpicker selected-colorpicker :white]
        [geo-map-colorpicker selected-colorpicker :red]
        [geo-map-colorpicker selected-colorpicker :orange]
        [geo-map-colorpicker selected-colorpicker :yellow]
        [geo-map-colorpicker selected-colorpicker :green]
        [geo-map-colorpicker selected-colorpicker :blue]
        [geo-map-colorpicker selected-colorpicker :purple]
        [geo-map-colorpicker selected-colorpicker :black]]
       [:> js/GeoDistributionMap {:initLat 64 :initLon 3 :initZoom 4 :width 640 :height 460}]])))

(defn results [{:keys                       [searching? num-resets]
                {:keys [view-type results]} :results-view :as a} m]
  [:div
   [:div.row
    [top-toolbar a m]
    [results-info a]]
   ^{:key @num-resets} [search-inputs a m] ; See comments in cglossa.start
   [spinner-overlay {:spin? (and @searching? (empty? @results)) :width 45 :height 45 :top 75}
    [b/tabs {:style      {:margin-top 15}
             :animation  false
             :active-key @view-type
             :on-select  #(reset! view-type %)}
     [b/tab {:title "Concordance" :event-key :concordance}
      [concordances a m]]
     [b/tab {:title "Map" :event-key :geo-map}
      [geo-map a m view-type]]
     [b/tab {:title "Statistics" :event-key :statistics :disabled true}]]]])
