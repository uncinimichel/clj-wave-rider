(ns clj-wave-rider.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(s/def ::direction #{:left :rigth :west :north-west :north :north-east :east :south-east :south :sout-west})
(s/def ::position #{:low :mid})
(s/def ::movement #{:rising :falling})
(s/def ::crowd #{:crowed :ultracrowd})
(s/def ::danger #{:rips :undertow :localism :sharks})
(s/def ::min-size (s/double-in :min +0 :max 100))
(s/def ::max-size (s/double-in :min +0 :max 100))
(s/def ::unit #{:ft :m})

(s/def :wave/size
  (s/keys :req-un [::min-size ::max-size ::unit]))

(gen/generate (s/gen :wave/size))

;; swell
(s/def :swell/direction
  (s/coll-of :type :into #{}))

(s/def :wind/direction
  (s/coll-of :type :into #{}))


(s/def :tide/position
  (s/coll-of :position))


{:swell/direction 
 :swell/size
 :swell/}

{:wind/direction
 :}

{:tide/position
 :tide/movement}

;; wave
(s/def :wave/direction #{:wave/left :wave/rigth})

{:wave/direction :wave/left
 :wave/type
 :wave/seabed
 :wave/power
 :wave/length }


{
 :surf/id "An id unique for my system"
 :surf/ref ["https://www.wannasurf.com/spot/Europe/Spain/Pais_Vasco/Mundaka/index.html" "https://magicseaweed.com/Mundaka-Surf-Guide/169/"]
 :surf/about "The wave is incredible and very powerful: it's the best left in Europe. It holds big swell. The main risk is that you will have the best wave of your life. Use the rip current to come back to the lineup. When the tide is low, you can sometimes walk on the sandbar. The take off seems to be easy but it's not ! The Estuary is protected by UNESCO: the water is clean, which is unusual for this coast. It requires a good fitness because of strong currents. Localism can be a problem: come and surf in small group."
 :surf/access "The spot is between Guernika ans Bermeo. It's easy to find: the spot is visible from the road. Park your car near the church. Go to the lineup by the little harbour."
 :surf/info {:surf/wave :wave/direction }
 :surf/crowd
 :surf/danger []
 }

{:surf/wave/direction :wave/left}
(s/def :surf/id string?)
(s/def :)
;;

