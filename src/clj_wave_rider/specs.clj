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

(s/def :wave/many-size
  (s/coll-of :wave/size))

(s/def :wave/direction
  (s/coll-of ::direction :into #{}))

(s/def :wind/direction
  (s/coll-of ::direction :into #{}))

(s/def :surf/id string?)
(s/def :surf/ref (s/coll-of string?))
(s/def :surf/about string?)
(s/def :surf/access string?)
(s/def :surf/info
  (s/keys :req [:wave/many-size :wave/direction :wind/direction ::crowd ::danger]))

(s/def :surf/spot
  (s/keys :req [:surf/id :surf/info]
          :opt [:surf/ref :surf/about :surf/access]))

(comment
  (gen/generate (s/gen :surf/spot))
  )

