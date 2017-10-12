(ns clj-wave-rider.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-wave-rider.common :as common]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [clojure.core.async :as async :refer [pipeline-blocking go go-loop put! take! <! >! <!! timeout chan alt! go to-chan]]))

(def whitelist-airports [:airport/LCY :airport/LHR :airport/LGW :airport/LTN :airport/STN :airport/SEN])

(def fn-airports (fn [lines]
                   (into {} (map (fn [[_ airport-name airport-city country airport-code _ lat longi _ timezone]]
                                   (hash-map (keyword "airport" airport-code)
                                             (hash-map :airport/name airport-name
                                                       :airport/city airport-city
                                                       :airport/country country
                                                       :airport/lat lat
                                                       :airport/long longi
                                                       :airport/timezone timezone)))
                                 lines))))

(def fn-routes (fn [lines]
                 (reduce (fn [p [_ _ from _ to]]
                           (let [kfrom (keyword "airport" from)
                                 kto (keyword "airport" to)]
                             (if (contains? p kfrom)
                               (assoc p kfrom (conj (get p kfrom) kto))
                               (assoc p kfrom [kto]))))
                         {}
                         lines)))

(defn get-clean-lines
  [text]
  (map (fn [line]
         (let [no-quote (str/replace line #"\"" "")
               words (str/split no-quote #",")]
           words))
       (str/split text #"\n")))

(defn process-file
  [file-name fun]
  (-> (slurp (io/resource file-name))
      get-clean-lines
      fun))
(comment
  (def all-airports (process-file "airports.dat" fn-airports))
  (def all-routes (process-file "routes.dat" fn-routes))
  (def my-routes (get-my-routes all-routes all-airports whitelist-airports)))

(defn get-my-routes
  [routes airports whitelist]
  (reduce (fn [p [k v]]
            (assoc p k (hash-map :airport/info (k airports)
                                 :airport/routes v)))
          {}
          (select-keys routes whitelist)))

;;;;;;;;;;;;;;;;;;;;;;;;;
                                        ;All the surfing spot

                                        ; http://api.spitcast.com/api/spot/all
                                        ;https://www.wannasurf.com/spot/Europe/UK/West_Scotland/Machrihanish/index.html
                                        ;HOST : "SPOT" : CONTINENT : COUNTRY : REGION : [SPOT :] "index.html"
(def info (atom {:continents ["Europe"]
                 :countries  []
                 :regions    []
                 :spots      []}))

(def select-urls
  (fn [matches hickory-tree]
    (let [host "https://www.wannasurf.com"
          all (-> (s/select (s/child
                             (s/tag :a))
                            hickory-tree))
          xf (comp
              (map #(get-in % [:attrs :href]))
              (filter some?)
              (filter #(re-matches matches %))
              (map #(str host %)))]
      (into [] xf all))))

(re-matches #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html" "/spot/Africa/Reunion/_--__--black_rocks_right/index.html")

(defn get-href
  [url matches selector]
  (let [response (common/parse-response (<!! (common/http-get url)))
        fn-sel (partial selector matches)]
    (-> response
        hickory/parse
        hickory/as-hickory
        fn-sel)))

(defn pp [r] (clojure.pprint/pprint r))

(def match-countries #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html")
(def match-zones #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html")
(def match-spots #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html")

(def select-is-zone?
  (fn [matches hickory-tree]
    (let [host "https://www.wannasurf.com"
          all (-> (s/select (s/child
                             (s/and
                              (s/class "wanna-item")
                              (s/tag :h3)))
                            hickory-tree))]
      (some (fn [{:keys [content]}]
              (= (first content) "Zones")) all))))

(comment
  (pp (get-href "https://www.wannasurf.com/spot/index.html" match-countries select-urls))
  (pp (get-href "https://www.wannasurf.com/spot/Europe/Spain/index.html" match-zones select-urls))
  (pp (get-href "https://www.wannasurf.com/spot/Africa/Reunion/index.html" match-zones select-urls))
  (pp (get-href "https://www.wannasurf.com/spot/Africa/Reunion/index.html" match-zones select-is-zone?))
  (pp (get-href "https://www.wannasurf.com/spot/Europe/Spain/index.html" match-zones select-is-zone?))
  (pp (get-href "https://www.wannasurf.com/spot/Europe/Spain/Asturias/index.html" match-spots select-urls)))

(def parallelism (+ (.availableProcessors (Runtime/getRuntime)) 1))

(defn amazing-pipeline
  [in]
  (let [out-countries-urls (chan 1)
        split!!
        out- (chan 1)]
    
    (pipeline-blocking parallelism out-surf-urls call-urls-pipeline in)
    (pipeline-blocking parallelism out-transform-response transform-response-pipeline out-surf-urls)
    out-transform-response))

(comment
  (amazing-pipeline (to-chan ["https://www.wannasurf.com/spot/index.html"]))
