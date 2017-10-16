(ns clj-wave-rider.airports
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-wave-rider.common :as common]
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

(defn extend-routes-with-airport-info
  [routes airports whitelist]
  (reduce (fn [p [k v]]
            (assoc p k (hash-map :airport/info (k airports)
                                 :airport/routes v)))
          {}
          (select-keys routes whitelist)))

(def all-airports (process-file "airports.dat" fn-airports))
(def all-routes (process-file "routes.dat" fn-routes))
(def my-routes (extend-routes-with-airport-info all-routes all-airports whitelist-airports))
(def all-surfs (process-file "surf-spots.csv" identity))

(def all-airports-info-in-my-routes
  (into {}
        (comp 
         (mapcat (fn [[k {:keys [:airport/routes]}]]
                   routes))
         (map (fn [ak]
                (hash-map ak
                          (ak all-airports)))))
        my-routes))


(take 2 all-surfs)

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

(def airport-closes-to-surf
  (filter (fn [[k {:keys [:airport/lat :airport/long]}]]
            (and (some? lat)
                 (some (fn [[lat-s lng-s]]
                         (>= 100 (distance-between-points {:lat (read-string lat) :long (read-string long)}
                                                          {:lat (read-string lat-s) :long (read-string lng-s)})))
                       all-surfs)))
          all-airports-info-in-my-routes))


(comment
  (spit "/tmp/airports-from-london-closest-to-surf.csv" (with-out-str (common/pp (map (fn [[code {:keys [:airport/lat :airport/long :airport/name]}]]
                                                                        (apply str lat "," long "," name "," (clojure.core/name code))) 
                                                                                      all-airports-info-in-my-routes))))
  )

(comment
  (time (println (count airport-closes-to-surf)))
  (count airport-closes-to-surf)
  (common/pp (take 10 airport-closes-to-surf))
  (common/pp (take 10 all-airports-info-in-my-routes))
  (common/pp (first all-surfs)))
