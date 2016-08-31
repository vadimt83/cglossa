(ns cglossa.result-views.cwb.written
  (:require [clojure.string :as str]
            [reagent.core :as r :include-macros true]
            [cglossa.react-adapters.bootstrap :as b]
            [cglossa.results :refer [concordance-table]]
            [cglossa.result-views.cwb.shared :as shared]))

(defn- process-field [field]
  "Processes a pre-match, match, or post-match field."
  (as-> field $
        (str/split $ #"\s+")
        (keep-indexed (fn [index token]
                        (if (str/includes? token \/)
                          (let [attrs    (str/split token #"/")
                                tip-text (str/join " " (as-> attrs $
                                                             (rest $)
                                                             (remove #(get #{"__UNDEF__" "-"} %) $)
                                                             (vec $)
                                                             ;; Show the lemma in quotes
                                                             (update $ 0 #(str "\"" % "\""))))]
                            ^{:key index}
                            [:span {:data-toggle "tooltip"
                                    :title       tip-text}
                             (first attrs) " "])
                          ;; With multi-word expressions, the non-last parts become simple strings
                          ;; without any attributes (i.e., no slashes) when we split the text on
                          ;; whitespace. Just print out those non-last parts and leave the tooltip
                          ;; to be attached to the last part.
                          ^{:key index} [:span token " "]))
                      $)))

(defn- non-first-multilingual [index line]
  ;; Extract the IDs of all s-units (typically sentences)
  ;; and put them in front of their respective s-units.
  (let [matches    (re-seq #"<(\w+_id)\s*(.+?)>(.*?)</\1>" line)
        components (if matches
                     (map (fn [m]
                            (list [:span.aligned-id (nth m 2)] ": " (nth m 3)))
                          matches)
                     (process-field line))]
    ^{:key index} [:tr [:td] [:td {:col-span 3} components]]))

(defn- extract-fields [res]
  (let [m (re-find #"^<s_id\s+(.*?)>:\s+(.*)\{\{(.+?)\}\}(.*?)$" res)]
    (let [[_ s-id pre match post] m]
      [(str/trim s-id) [pre match post]])))

(defn- main-row [result index a {:keys [corpus] :as m}]
  ^{:key (hash result)}
  [:tr
   (shared/id-column a m result index)
   (shared/text-columns result)])

(defn single-result-rows [a m res index]
  "Returns one or more rows representing a single search result."
  (let [[main-line & other-lines] (:text res)
        [s-id fields] (extract-fields main-line)
        [pre match post] (map process-field fields)
        res-info {:s-id       s-id
                  :pre-match  pre
                  :match      match
                  :post-match post}
        main     (main-row res-info index a m)
        others   (map-indexed non-first-multilingual other-lines)]
    (cons main others)))

(defmethod concordance-table :default [{{:keys [results page-no]} :results-view :as a} m]
  (let [res (get @results @page-no)]
    [:div.row>div.col-sm-12.search-result-table-container
     [b/table {:striped true :bordered true}
      [:tbody
       (doall (map (partial single-result-rows a m)
                   res
                   (range (count res))))]]]))
