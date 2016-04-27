(ns cglossa.shared
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cljs.core.async :as async :refer [<!]]
            [cljs-http.client :as http]
            react-spinner
            [cglossa.react-adapters.bootstrap :as b]
            [reagent.core :as r :include-macros true]))

;; TODO: Make this configurable?
(def page-size 50)

;; If the search returns fewer results than we asked for, we assume that no more can be found.
;; However, since CQP may actually return fewer results than we asked for (even though more can
;; actually be found), we subtract this margin to be on the safe side.
(def result-margin 200)

;; Adapt https://github.com/chenglou/react-spinner to Reagent
(def spinner (r/adapt-react-class js/Spinner))

(defn spinner-overlay
  "Container component that covers its child components with a semi-transparent overlay
  that also shows a spinner.

  Supported options are :spin?, which shows the overlay and spinner when true, and CSS
  styles that will override the default styles for the spinner.

  Note: Although it would seem more natural to have :spin? and the various
  styles as separate arguments, it seems that Reagent only supports a single
  argument when child components are provided as well; hence we need to put
  everything into the 'options' argument."
  [options]
  ;; Wrap everything in a relatively positioned div (whose width and height will be
  ;; determined by the child components of c) and insert an absolutely positioned div
  ;; with a high z-index that will fill the same space, thus overlaying the child
  ;; components.
  [:div {:style {:position "relative"}}
   (when (:spin? options)
     [:div {:style {:position         "absolute"
                    :top              0
                    :right            0
                    :bottom           0
                    :left             0
                    :background-color "white"
                    :opacity          0.7
                    :z-index          1000}}
      [spinner {:style (dissoc options :spin?)}]])
   (map-indexed (fn [i child]
                  (r/as-element (with-meta child {:key i})))
                (r/children (r/current-component)))])

(defn reset-queries! [{{:keys [queries]} :search-view} {:keys [corpus]}]
  (let [language-code (-> @corpus :languages first :code)]
    (reset! queries [{:query "" :lang language-code}])))

(def ^:private cancel-search-ch
  "Core.async channel used to cancel any already ongoing search when we start a new one."
  (async/chan))

(defmulti cleanup-result
  "Multimethod that accepts two arguments - a model/domain state map and a
  single search result - and dispatches to the correct method based on
  the value of :search-engine in the corpus map found in the
  model/domain state map. The :default case implements CWB support."
  (fn [{corpus :corpus} _] (:search-engine @corpus)))

(defn- do-search-steps! [{:keys                   [searching?]
                          {:keys [results total]} :results-view}
                         {:keys [corpus search] :as m}
                         url search-params nsteps]
  (let [sizes       (get-in @corpus [:extra-info :size])
        corpus-size (or (get sizes (keyword (:code @corpus)))
                        (get sizes (keyword (str (:code @corpus) "_"
                                                 (-> search-params :queries first :lang)))))]
    (go
      (dotimes [step nsteps]
        (let [json-params (cond-> search-params
                                  true (assoc :step (inc step))
                                  @total (assoc :last-count @total)
                                  (:id @search) (assoc :search-id (:id @search)))
              ;; Fire off a search query
              results-ch  (http/post url {:json-params json-params})
              ;; Wait for either the results of the query or a message to cancel the query
              ;; because we have started another search
              [val ch] (async/alts! [cancel-search-ch results-ch] :priority true)]
          (when (= ch results-ch)
            (let [{:keys [status success] {resp-search  :search
                                           resp-results :results
                                           resp-count   :count} :body} val]
              (if-not success
                (.log js/console status)
                (do
                  (swap! search merge resp-search)
                  ;; Only the first request actually returns results; the others just save the
                  ;; results on the server to be fetched on demand and return an empty result list
                  ;; (but a non-zero resp-count), unless the first result did not find enough
                  ;; results to fill up two result pages - in that case, later requests will
                  ;; continue filling them. Thus, we set the results if we either receive a
                  ;; non-empty list of results or a resp-count of zero (meaning that there were
                  ;; actually no matches).
                  (if (or (seq resp-results) (zero? resp-count))
                    (let [old-results (apply concat (map second @results))]
                      (reset! results (into {} (map (fn [page-index res]
                                                      [(inc page-index)
                                                       (map (partial cleanup-result m) res)])
                                                    (range)
                                                    (partition-all page-size
                                                                   (concat old-results
                                                                           resp-results)))))
                      (reset! total (or resp-count (count resp-results))))
                    (reset! total resp-count))))))))))
  (reset! searching? false))

(defn reset-results!
  [{{:keys [results page-no paginator-page-no paginator-text-val]} :results-view}]
  (reset! results nil)
  (reset! page-no 1)
  (reset! paginator-page-no 1)
  (reset! paginator-text-val 1))

(defn search!
  ([a {:keys [corpus search] :as m}]
   (search! a m (if (= (:search-engine @corpus) "cwb") 3 1)))
  ([{{queries :queries}                     :search-view
     {:keys [show-results? total sort-key]} :results-view
     searching?                             :searching?
     :as                                    a}
    {:keys [corpus search] :as m}
    nsteps]
   (let [first-query (:query (first @queries))]
     (when (and first-query
                (not (str/blank? first-query))
                (not= first-query "\"\""))
       ;; Start by cancelling any already ongoing search.
       (async/offer! cancel-search-ch true)
       (let [q      (if (= (-> @corpus :languages first :code) "zh")
                      ;; For Chinese: If the tone number is missing, add a pattern
                      ;; that matches all tones
                      (for [query @queries]
                        (update query :query
                                str/replace #"\bphon=\"([^0-9\"]+)\"" "phon=\"$1[1-4]?\""))
                      ;; For other languages, leave the queries unmodified
                      @queries)
             q*     (for [qu q]
                      (update qu :query str/replace "\"__QUOTE__\"" "'\"'"))
             url    "/search"
             params {:corpus-id    (:id @corpus)
                     :queries      q*
                     :metadata-ids (->> (:metadata @search) (filter #(second %)) (into {}))
                     :page-size    page-size
                     :sort-key     @sort-key}]
         (reset! show-results? true)
         (reset! searching? true)
         (reset! total nil)
         (reset! sort-key :position)
         (reset-results! a)
         (do-search-steps! a m url params nsteps))))))

(defn showing-metadata? [{:keys                   [show-metadata? narrow-view?]
                          {:keys [show-results?]} :results-view}
                         {:keys [metadata-categories]}]
  (cond
    ;; Don't show metadata if the corpus doesn't have any (duh!)
    (empty? @metadata-categories) false
    ;; If show-metadata is non-nil, the user has explicitly chosen whether to see metadata,
    ;; so we respect that unconditionally
    (some? @show-metadata?) @show-metadata?
    ;; Now we know that we have metadata, and that the user has not explicitly chosen
    ;; whether to see them. If we are showing search results, we hide the metadata if the
    ;; window is narrow; if instead we are showing the start page, we show the metadata
    ;; regardless of window size.
    @show-results? (not @narrow-view?)
    :else true))

(defn on-key-down [event a m & params]
  (when (= "Enter" (.-key event))
    (.preventDefault event)
    (apply search! a m params)))

(defn remove-row-btn [show? wrapped-query]
  [:div.table-cell.remove-row-btn-container
   [b/button {:bs-style "danger"
              :bs-size  "xsmall"
              :title    "Remove row"
              :on-click #(reset! wrapped-query nil)
              :style    {:margin-right 5
                         :padding-top  3
                         :visibility   (if show?
                                         "visible"
                                         "hidden")}}
    [b/glyphicon {:glyph "remove"}]]])

(defn headword-search-checkbox [wrapped-query]
  [b/input {:type      "checkbox"
            :value     "1"
            :checked   (:headword-search @wrapped-query)
            :on-change #(swap! wrapped-query assoc :headword-search (.-target.checked %))
            :id        "headword_search"
            :name      "headword_search"} " Headword search"])

(defn top-toolbar [{:keys                            [num-resets show-metadata?]
                    {:keys [queries]}                :search-view
                    {:keys [show-results? sort-key]} :results-view
                    :as                              a}
                   {:keys [search metadata-categories] :as m}]
  [:div.col-sm-5
   [b/buttontoolbar {:style {:margin-bottom 20}}
    (when (seq @metadata-categories)
      (if (showing-metadata? a m)
        [b/button {:bs-size  "small"
                   :title    "Hide search criteria"
                   :on-click (fn [e]
                               (reset! show-metadata? false)
                               (.preventDefault e))}
         "Hide filters"]
        [b/button {:bs-size  "small"
                   :title    "Show search criteria"
                   :on-click (fn [e]
                               (reset! show-metadata? true)
                               (.preventDefault e))}
         "Filters"]))
    [b/button {:bs-style "primary"
               :bs-size  "small"
               :title    "Reset form"
               :on-click (fn []
                           (reset-queries! a m)
                           (reset! search {})
                           (reset! show-results? false)
                           (reset! sort-key :position)
                           (swap! num-resets inc))} ; see comments in the start component
     "Reset form"]]])
