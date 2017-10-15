(ns clj-wave-rider.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-wave-rider.common :as common]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [clojure.core.async :as async :refer [pipeline pipeline-blocking go go-loop put! take! <! >! <!! timeout chan alt! go to-chan]]))

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
  (def my-routes (get-my-routes all-routes all-airports whitelist-airports))
  (def all-airports-in-my-routes
    (into #{}
          (comp 
           (mapcat (fn [[k {:keys [:airport/routes]}]]
                     routes))
           (map (fn [ak]
                  (hash-map ak
                            (ak all-airports)))))
          my-routes)))


(comment
  (spit "/tmp/data2.edn" (with-out-str (pp (map (fn [[k {:keys [:airport/lat :airport/long :airport/name]}]]
                                                  (apply str lat "," long "," name)) 
                                                all-airports))))

  (spit "/tmp/data2.json" (with-out-str (pp (map (fn [[k {:keys [:airport/lat :airport/long :airport/name]}]]
                                                   (apply str lat "," long "," name)) 
                                                 all-airports)))))


(defn get-my-routes
  [routes airports whitelist]
  (reduce (fn [p [k v]]
            (assoc p k (hash-map :airport/info (k airports)
                                 :airport/routes v)))
          {}
          (select-keys routes whitelist)))


(def cat-a [1 2 3 4 5 6 7 8 9 10])
(def cat-b [4 9 10])




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
  [matches selector url]
                                        ;  (println url)
  (let [response (common/parse-response (<!! (common/http-get url)))
                                        ;       _ (println "after response")
        fn-sel (partial selector matches)]
    (-> response
        hickory/parse
        hickory/as-hickory
        fn-sel)))

(defn pp [r] (clojure.pprint/pprint r))

(def match-countries #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html")
(def match-zones #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html")
(def match-spots-no-zones #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html")
(def match-spots-zones #"/spot/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/[a-zA-Z0-9-_]+/index.html")

(def select-is-zone?
  (fn [matches hickory-tree]
    (let [host "https://www.wannasurf.com"
          all (-> (s/select (s/child
                             (s/and
                              (s/class "wanna-item")
                              (s/tag :h3)))
                            hickory-tree))]
      (some? (some (fn [{:keys [content]}]
                     (= (first content) "Zones")) all)))))

(def select-spot-info
  (fn [_ hickory-tree]
    (let [all (-> (s/select (s/descendant
                             (s/id "wanna-item-2columns-left")
                             (s/tag :table)
                             (s/tag :p))
                            hickory-tree))
          content (:content (last all))]
      (if content
        (hash-map :lat  (.trim (get content 1))
                  :long (.trim (get content 5)))
        (hash-map :lat  nil
                  :long nil)
        ))))

(def get-countries-pipeline
  (comp
   (mapcat (partial get-href match-countries select-urls))))
                                        ;   (take 500)))

(def has-zones-pipeline?
  (map (fn [url]
         [url (get-href match-zones select-is-zone? url)])))

(def get-zones-pipeline
  (mapcat (fn [[url _]]
            (println "This is an urrl" url)
            (let [r (get-href match-zones select-urls url)]
              (println "ZONES" r)
              r))))

(def get-spots-no-zones-pipeline
  (mapcat (fn [[url _]]
            (if (nil? url)
              {}
              (get-href match-spots-no-zones select-urls url)))))

(def get-spots-zones-pipeline
  (mapcat (fn [url]
                                        ;         (println url "dssaddassd")
            (if (nil? url)
              {}
              (let [r (get-href match-spots-zones select-urls url)]
                                        ;            (println r "rrrrrrrrrrrrrrrrr")
                r
                )))))

(def get-spots-info-pipeline
  (map (fn [url]
         (if (nil? url)
           {}
           (hash-map url
                     (get-href match-zones select-spot-info url))))))

(def parallelism (+ (.availableProcessors (Runtime/getRuntime)) 1))
                                        ;(def parallelism 1)

(defn amazing-pipeline
  [in]
  (let [out-countries-urls (chan 1)
        out-zones (chan 1)
        [out-countries-no-zones-urls out-countries-with-zones-urls] (async/split (fn [[url boo]](false? boo)) out-zones)
        out-zone-urls (chan 1)
        out-spot-zone-urls (chan 1)
        out-spot-no-zone-urls (chan 1)
        out-spot-urls (async/merge [out-spot-zone-urls out-spot-no-zone-urls] 1)
        out-spot-info (chan 1)]
    
    (pipeline-blocking parallelism out-countries-urls      get-countries-pipeline      in)
    (pipeline-blocking parallelism out-zones               has-zones-pipeline?         out-countries-urls)
    (pipeline-blocking parallelism out-spot-no-zone-urls   get-spots-no-zones-pipeline out-countries-no-zones-urls)

    (pipeline-blocking parallelism out-zone-urls           get-zones-pipeline          out-countries-with-zones-urls)
    (pipeline-blocking parallelism out-spot-zone-urls      get-spots-zones-pipeline    out-zone-urls)
    
    
    (pipeline-blocking parallelism out-spot-info           get-spots-info-pipeline     out-spot-urls)
    out-spot-info))

(comment
  (pp (<!! (amazing-pipeline (to-chan ["https://www.wannasurf.com/spot/index.html"]))))
  (def ciao (let [a (<!! (async/reduce merge {} (amazing-pipeline (to-chan ["https://www.wannasurf.com/spot/index.html"]))))]
              (pp a)
              a))

  (spit "/tmp/data.edn" (with-out-str (pp (into []
                                                (comp
                                                 (map (fn [[k {:keys [lat long]}]]
                                                        (apply str lat "," long)))
                                                 (remove (fn [v]
                                                           (= v ","))))
                                                ciao
                                                ))))

  (pp
   (into []
         (comp
          (map (fn [[k {:keys [lat long]}]]
                 (apply str lat "," long)))
          (remove (fn [v]
                    (= v ","))))
         (take 10 ciao)
         )))


(comment
  (let [o1 (chan 1)
        t (chan 1)
        o2 (chan 1)
        o3 (chan 1)]
    (pipeline-blocking 1 o1 get-zones-pipeline           (to-chan [["https://www.wannasurf.com/spot/Europe/Spain/index.html" true]]))
                                        ;    (pipeline-blocking 1 t  (map (fn [%] (println %) %)) o1)
    (pipeline-blocking 1 o2  get-spots-zones-pipeline    o1)
    (pipeline-blocking 1 o3  get-spots-info-pipeline    o2)
    (go-loop []
      (let [a (<!! o3)]
        (pp a)
        (if (nil? a)
          a
          (recur)))))
  )



(comment
  (pp (take 10 (get-href match-countries select-urls "https://www.wannasurf.com/spot/index.html")))
  (pp (get-href  match-zones select-urls "https://www.wannasurf.com/spot/Europe/Spain/index.html"))
  (pp (get-href "https://www.wannasurf.com/spot/Africa/Reunion/index.html" match-zones select-urls))
  (pp (get-href "https://www.wannasurf.com/spot/Africa/Burkina_Faso/index.html" match-zones select-is-zone?))
  (pp (get-href match-zones select-is-zone? "https://www.wannasurf.com/spot/Africa/Sao_Tome/index.html"))
  (pp (get-href match-zones select-is-zone? "https://www.wannasurf.com/spot/Africa/Burkina_Faso/index.html"))
  (pp (get-href match-spots select-urls "https://www.wannasurf.com/spot/Africa/Algeria/index.html"))
  (pp (get-href match-spots-no-zones select-urls "https://www.wannasurf.com/spot/Africa/Burkina_Faso/index.html"))
  (pp (get-href match-spots select-spot-info "https://www.wannasurf.com/spot/Africa/Angola/ambriz_beach/index.html")))
