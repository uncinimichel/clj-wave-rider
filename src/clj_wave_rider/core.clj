(ns clj-wave-rider.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-wave-rider.common :as common]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [clojure.core.async :as async :refer [pipeline pipeline-blocking go go-loop put! take! <! >! <!! timeout chan alt! go to-chan]]))

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

(defn get-href
  [matches selector url]
  (let [response (common/parse-response (<!! (common/http-get url)))
        fn-sel (partial selector matches)]
    (try 
      (-> response
          hickory/parse
          hickory/as-hickory
          fn-sel)
      (catch Exception e
        (println "errors hickory with url" url "and err" (.getMessage e))
        nil))))

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

(def select-spot-info-left
  (fn [_ hickory-tree]
    (let [all (-> (s/select (s/descendant
                             (s/id "wanna-item-specific-2columns-left"))
                            hickory-tree))
          content (:content (last all))])))

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

(def has-zones-pipeline?
  (map (fn [url]
         [url (get-href match-zones select-is-zone? url)])))

(def get-zones-pipeline
  (mapcat (fn [[url _]]
            (get-href match-zones select-urls url))))

(def get-spots-no-zones-pipeline
  (mapcat (fn [[url _]]
            (if (nil? url)
              {}
              (get-href match-spots-no-zones select-urls url)))))

(def get-spots-zones-pipeline
  (mapcat (fn [url]
            (if (nil? url)
              {}
              (get-href match-spots-zones select-urls url)))))

(def get-spots-info-pipeline
  (map (fn [url]
         (if (nil? url)
           {}
           (hash-map url
                     (get-href match-zones select-spot-info url))))))

(def parallelism (+ (.availableProcessors (Runtime/getRuntime)) 1))

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
  (common/pp (<!! (amazing-pipeline (to-chan ["https://www.wannasurf.com/spot/index.html"]))))
  (def ciao (let [a (<!! (async/reduce merge {} (amazing-pipeline (to-chan ["https://www.wannasurf.com/spot/index.html"]))))]
              (common/pp a)
              a))

  (common/pp (take 10 ciao))
  (spit "/tmp/surf-spots.csv" (with-out-str (common/pp (into []
                                                             (comp
                                                              (remove (fn [[k {:keys [lat long]}]]
                                                                        (or (nil? lat)
                                                                            (nil? long))))
                                                              (map (fn [[k {:keys [lat long]}]]
                                                                     {:lat (dms->dec lat)
                                                                      :long (dms->dec long)
                                                                      :url k}))
                                                              (map (fn [{:keys [lat long url]}]
                                                                     (str lat "," long "," url))))
                                                             ciao))))
  )


(comment
  (common/pp (take 10 (get-href match-countries select-urls "https://www.wannasurf.com/spot/index.html")))
  (common/pp (get-href  match-zones select-urls "https://www.wannasurf.com/spot/Europe/Spain/index.html"))
  (common/pp (get-href "https://www.wannasurf.com/spot/Africa/Reunion/index.html" match-zones select-urls))
  (common/pp (get-href "https://www.wannasurf.com/spot/Africa/Burkina_Faso/index.html" match-zones select-is-zone?))
  (common/pp (get-href match-zones select-is-zone? "https://www.wannasurf.com/spot/Africa/Sao_Tome/index.html"))
  (common/pp (get-href match-zones select-is-zone? "https://www.wannasurf.com/spot/Africa/Burkina_Faso/index.html"))
  (common/pp (get-href match-spots select-urls "https://www.wannasurf.com/spot/Africa/Algeria/index.html"))
  (common/pp (get-href match-spots-no-zones select-urls "https://www.wannasurf.com/spot/Africa/Burkina_Faso/index.html"))
  (common/pp (get-href match-spots-no-zones select-spot-info "https://www.wannasurf.com/spot/Africa/Angola/ambriz_beach/index.html"))
  )


(defn deg->rad [deg] (* deg (/ Math/PI 180)))

(defn distance-between-points
  [p1 p2]
  (let [r 6371
        {lat1 :lat long1 :long} p1
        {lat2 :lat long2 :long} p2
        dlat (deg->rad (- lat2 lat1))
        dlong (deg->rad (- long2 long1))
        a (+ (* (Math/sin (/ dlat 2)) (Math/sin (/ dlat 2)))
             (* (Math/cos (deg->rad lat1)) (Math/cos (deg->rad lat2)) (Math/sin (/ dlong 2)) (Math/sin (/ dlong  2))))
        c (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))
        d (* r c)]
    d))

(comment
  (distance-between-points {:lat 37.74 :long -25.69}
                           {:lat 40.69 :long -74.16}))

(defn dms->dec
  [dms]
  (let [reg #"(\d+)° (\d+.?\d*)' (N|S|W|E)"
        d->sign {"N" + "S" - "W" - "E" +}
        [_ d m sign] (re-matches reg dms)]
    ((get d->sign sign)
     0
     (+ (read-string d) (/ (read-string m) 60)))))

(comment
  (dms->dec "38° 58.719' N"))

(comment
  (def airport-closes-to-surf
    (filter (fn [a-p]
              (some (fn [s-p]
                      (<= 100 (distance-between-points a-p s-p))
                      surfing-spots))))))

