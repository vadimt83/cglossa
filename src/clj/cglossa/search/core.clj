(ns cglossa.search.core
  (:require [korma.db :as kdb]
            [korma.core :refer [defentity table select where insert values]]
            [clojure.edn :as edn]
            [cglossa.shared :refer [core-db]]
            [cglossa.db.corpus :refer [get-corpus]]
            [cglossa.search.download :as download]))

(defentity search)

(defmulti run-queries
  "Multimethod for actually running the received queries in a way that is
  appropriate for the search engine of the corpus in question."
  (fn [corpus _ _ _ _ _ _ _] (:search_engine corpus)))

(defmulti get-results
  (fn [corpus _ _ _ _ _ _ _] [(:search_engine corpus) (seq (:multicpu_bounds corpus))]))

(defmulti transform-results
  "Multimethod for transforming search results in a way that is
  appropriate for the search engine of the corpus in question."
  (fn [corpus _ _] (:search_engine corpus)))

(defmulti geo-distr-queries
  "Multimethod for running a query and returning geographical distribution of
   results."
  (fn [corpus _ _] (:search_engine corpus)))

(defn- create-search! [corpus-id queries]
  (kdb/with-db core-db
    (insert search (values {:corpus_id corpus-id
                            :user_id   1
                            :queries   (pr-str queries)}))))

(defn- search-by-id [id]
  (kdb/with-db core-db
    (first (select search (where {:id id})))))

(defn search-corpus [corpus-id search-id queries metadata-ids step page-size last-count sort-key]
  (let [corpus     (get-corpus {:id corpus-id})
        search-id* (or search-id (:generated_key (create-search! corpus-id queries)))
        [hits cnt cnts] (run-queries corpus search-id* queries metadata-ids step
                                     page-size last-count sort-key)
        results    (transform-results corpus queries hits)
        s          (search-by-id search-id*)]
    {:search     s
     :results    results
     ;; Sum of the number of hits found by the different cpus in this search step
     :count      cnt
     ;; Number of hits found by each cpus in this search step
     :cpu-counts cnts}))

(defn results [corpus-id search-id start end cpu-counts sort-key]
  (let [corpus      (get-corpus {:id corpus-id})
        s           (search-by-id search-id)
        queries     (edn/read-string (:queries s))
        start*      (Integer/parseInt start)
        end*        (Integer/parseInt end)
        cpu-counts* (edn/read-string cpu-counts)
        [results _] (get-results corpus s queries start* end* cpu-counts* sort-key nil)]
    (transform-results corpus queries results)))


(defn geo-distr [corpus-id search-id metadata-ids]
  (let [corpus  (get-corpus {:id corpus-id})
        results (geo-distr-queries corpus search-id metadata-ids)
        s       (search-by-id search-id)]
    {:search  s
     :results results}))

(defn download-results [corpus-id search-id cpu-counts format headers? attrs]
  (let [corpus   (get-corpus {:id corpus-id})
        s        (search-by-id search-id)
        queries  (edn/read-string (:queries s))
        start    0
        end      (when (= format "excel") 49999)
        sort-key "position"
        [results _] (get-results corpus s queries start end cpu-counts sort-key attrs)
        rows     (for [line results]
                   ;; Extract corpus position, sentence/utterance ID, left context, match and right
                   ;; context from the result line
                   (or (next (re-find #"^\s*(\d+):\s*<.+?\s(.+?)>:\s*(.+?)\s*\{\{(.+?)\}\}\s+(.+)"
                                      line))
                       [nil nil line nil nil]))]
    (case format
      "excel" (download/excel-file search-id headers? rows)
      "tsv" (download/csv-file :tsv search-id headers? rows)
      "csv" (download/csv-file :csv search-id headers? rows))))
