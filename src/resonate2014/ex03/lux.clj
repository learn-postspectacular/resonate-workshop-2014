(ns resonate2014.ex03.lux
  (:require
   [thi.ng.luxor.core :as luxor]
   [thi.ng.luxor.io :as luxio]
   [thi.ng.geom.core :as g]
   [thi.ng.geom.aabb :refer [aabb]]))

(def base-scene
  "Default Luxrender scene setup including base configuration for
  renderer, camera, film, lights, materials and a ground box."
  (-> (luxor/lux-scene)
      (luxor/renderer-sampler)
      (luxor/sampler-ld {})
      (luxor/integrator-bidir {})
      (luxor/camera
       {:eye [0 3 3.5] :target [0 -0.125 0] :up [0 0 1] :fov 50 :lens-radius 0.05})
      (luxor/film
       {:width 1280 :height 720
        :response :agfachrome-rsx2-200cd
        :display-interval 10
        :halt-spp 3000})
      (luxor/tonemap-linear
       {:iso 100 :exposure 0.5 :f-stop 8 :gamma 2.2})
      (luxor/material-matte
       :white {:diffuse [0.8 0.8 0.8]})
      (luxor/light-groups
       {:top  {:gain 32.0}
        :fill {:gain 3.0}})
      (luxor/area-light
       :top   {:p [0 0 5] :size [8 8] :group :top})
      (luxor/area-light
       :left  {:p [0 0 4] :size [2 4] :group :fill :tx {:translate [-3 0 0] :ry -30}})
      (luxor/area-light
       :right {:p [0 0 4] :size [2 4] :group :fill :tx {:translate [3 0 0] :ry 30}})
      (luxor/ply-mesh
       :base  {:material :white
               :mesh (-> (aabb [10 10 0.1])
                         (g/center (g/vec3 0 0 -0.05))
                         (g/as-mesh))})))

(defn make-lux-material
  "Higher order fn. Takes a coloring fn and returns a fn accepting two
  args: a lux scene and a statistics value. If needed this fn then
  produces a new material for the given value (each mapped to a
  different color) and returns a 2 element vector of [updated-scene
  material-id]."
  [color-fn]
  (fn [scene v]
    (let [mat-id (str "mat-data" v)]
      (if (get-in scene [:materials mat-id])
        [scene mat-id]
        [(luxor/material-matte scene mat-id {:diffuse (color-fn v)}) mat-id]))))

(defn add-data-meshes-to-scene
  "Takes a lux scene, material fn, translation offset and seq of mesh
  specs. Returns updated scene with all meshes added and for each
  calls material fn to produce a related material mapping. Each mesh
  spec is a 4 element vector of [x y stats-value mesh]"
  [scene material-fn meshes]
  (reduce
   (fn [scene [x y v m]]
     (let [[scene mat-id] (material-fn scene v)]
       (luxor/ply-mesh
        scene (format "grid-%1.2f-%1.2f" x y)
        {:mesh m :material mat-id})))
   scene meshes))

(defn export-scene
  "Takes a base file path (without file extension) and a lux scene.
  Exports scene (incl. all meshes) to given path and returns
  serialized scene as string."
  [path scene]
  (-> scene
      (luxio/serialize-scene path false)
      (luxio/export-scene)))
