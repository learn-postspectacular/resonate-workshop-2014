(ns resonate.ex03.core
  "This namespace contains the exercises of our last workshop day and
  deals with visualizing a number of open data sets about London as
  both SVG and as 3D scene for the open source Luxrender. The
  functions have been re-arranged and received minor modifications
  after the workshop to make them more flexible and allow them to be
  used with similarly structured CSV files. Please also see the child
  namespaces for further details about SVG & 3D processing. We're
  making frequent use of higher order functions to pre-configure
  various sub-steps of the visualisation procedure. Please see the
  final two functions (and their calls) for concrete usage and as
  entry point for understanding how the different parts all fit
  together."
  (:require
   [resonate2014.ex03.svg :as svg]
   [resonate2014.ex03.lux :as lux]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [thi.ng.common.math.core :as m]
   [thi.ng.geom.core :as g]
   [thi.ng.geom.circle :as c]
   [thi.ng.geom.polygon :as p])
  ;; For our HSB -> RGB color conversion we make use of Java's Color class
  ;; Whereas we use `:require` to refer to Clojure namespaces,
  ;; we must use `:import` to refer to Java classes
  (:import
   [java.awt Color]))

(defn load-csv
  "Takes a file path or input stream to/of a CSV file and returns seq
  of parsed row vectors."
  [path-or-stream]
  (csv/read-csv (io/reader path-or-stream)))

(defn parse-double
  "Attempts to parse given string as double precision number. If this
  fails, returns nil."
  [x]
  (try (Double/parseDouble x) (catch Exception e)))

(defn parse-borough
  "Transformation fn for `csv->map` below. Takes a vector of column
  names and a single data row. Returns 2 elem vector of [borough
  borough-stats-map] for valid data rows or else nil."
  [column-names [ln code borough :as row]]
  (when borough
    (let [cols (map parse-double row)
          pairs (map (fn [date v] [date v]) column-names cols)
          month-values (assoc (into {} pairs)
                         :code code
                         :line-number ln)]
      [borough month-values])))

(defn csv->map
  "Takes a transform fn and a seq of CSV row vectors as returned by
  `load-csv`. Reduces seq into a single hash map using transform fn,
  which must accept two args: the vector of column names (the first
  row of the CSV seq) and a single CSV row vector. The fn must return
  a 2 element vector of [map-key value] or `nil` if a row is to be
  skipped. Only data rows are passed to the transform function (i.e.
  not the row headers)."
  [row-fn [column-names & data]]
  (reduce
   (fn [acc row]
     (let [[k v] (row-fn column-names row)]
       (if k
         (assoc acc k v)
         acc)))
   {} data))

(defn reduce-with
  "Takes a reduction fn, CSV data map and seq of data column names.
  Only selects given column names from all inner (rows) maps,
  concatenates them into single seq and then reduces their values
  with given reduction fn."
  [reduce-fn data-map col-names]
  (->> data-map
       (vals)
       (mapcat
        (fn [borough-map]
          (vals (select-keys borough-map col-names))))
       (reduce reduce-fn)))

(defn minmax-stats
  "Takes a CSV data map and seq of column-names applies `reduce-with`
  to given data and returns 2-elem seq of total min/max values."
  [data-map col-names]
  (map (fn [f] (reduce-with f data-map col-names)) [min max]))

(defn sort-boroughs
  "Takes a CSV data-map and vector of column names. Sorts rows in
  reverse order over the average values of specified columns. Returns
  orginal map as sorted seq of its original values, but each key-value
  pair given as 2 elem vectors [borough borough-stats]."
  [data-map col-names]
  (->> data-map
       (map
        (fn [[_ v :as kv]]
          (let [cols (vals (select-keys v col-names))
                sum  (reduce + cols)
                avg  (/ sum (count cols))]
            [avg kv])))
       (sort-by first (fn [a b] (- (compare a b))))
       (map second)))

(defn make-scale
  "Takes an input & output interval as vectors, returns
  pre-configured fn calling `map-interval` for given arg."
  [in out]
  (fn [v] (m/map-interval v in out)))

(defn make-heatmap
  "Higher order function. Takes an input & output interval as vectors,
  a saturation & brightness value. Returns pre-configured fn, which
  passes given arg to `map-interval` to translate into a color hue
  within the range of the output interval, then creates a HSB color
  with given saturation & brightness. Returns color in RGB space as 3
  element vector of normalized channel values (0.0 .. 1.0)."
  [in out sat bright]
  (let [hue-scale (make-scale in out)]
    (fn [v]
      (let [hue (double (hue-scale v))
            rgb (Color/HSBtoRGB hue sat bright)]
        (mapv
         (fn [bits]
           (-> (bit-shift-right rgb bits)
               (bit-and 255)
               (/ 255.0)))
         [16 8 0])))))

(defn make-svg-cell
  "Higher order function. Takes a sizing fn, coloring fn and
  normalized shape opacity value. Returns a fn accepting a single
  arg (a vector of x/y and statistics value), which is then used to
  produce a SVG circle element in an intermediate format (as Clojure
  vector). The given arg is applied to size & color fn's to compute
  the radius and color."
  [size-fn color-fn opacity]
  (fn [[x y v]]
    (let [rgb (map #(int (* % 255)) (color-fn v))]
      (svg/circle
       x y (size-fn v)
       :fill (svg/rgb->hex rgb)
       :fill-opacity opacity))))

(defn svg-label
  "Takes a y coordinate & single map entry (a [key value] vector).
  Returns a SVG text element in intermediate format (as Clojure
  vector) with key as text content."
  [y [key]]
  (svg/text 50 y (name key)))

(defn make-3d-cell
  "Higher order fn. Takes an 2D offset vector, radius fn and height fn.
  Returns fn accepting a single arg (as in `make-svg-cell`).
  Returns 3D mesh data structure defining a hollow, walled cylinder
  along the Z axis with its radius defined by radius-fn, its height
  by height-fn and translated in XY plane by offset."
  [offset radius-fn height-fn]
  (prn offset)
  (fn [[x y v]]
    [x y v
     (-> (c/circle (g/+ offset x y) (radius-fn v))
         (g/as-polygon 12)
         (g/extrude-shell {:depth (height-fn v) :wall 0.004}))]))

(defn make-coords
  "Produces a seq of `n` 1D grid coordinates, each scaled by `size`
  and offset to be in the center of each grid cell. Function is called
  individually to compute X & Y cell coordinates."
  [n size]
  (map (fn [x] (* (+ x 0.5) size)) (range n)))

(defn compute-grid
  "This function is the heart of our example and is responsible for
  transforming the data map into a flat seq of visualization elements
  for further processing. It takes a number of keyword arguments
  (described below) and transforms the data into a 2D grid layout.
  The arguments are:

      :data      - map of data rows/stats (each map value is a map of stats itself)
      :col-names - seq of column names of the actual data points
      :width     - grid layout width (X axis, default 100)
      :height    - grid layout height (Y axis, default 100)
      :cell-fn   - function applied to each stats value, produces viz element
      :label-fn  - function applied to each row, produces label element (optional)
      :filter    - function applied to each stats value,
                   removes viz elements if returns non-truthy value"
  [& {:keys [data col-names width height cell-fn label-fn filter-fn]
      :or   {width 100, height 100, filter-fn identity}}]
  (let [rows     (count data)
        cols     (count col-names)
        cw       (/ (double width) cols)
        ch       (/ (double height) rows)
        x-coords (make-coords cols cw)
        y-coords (make-coords rows ch)
        cells    (mapcat
                  (fn [y [k stats-map]]
                    (let [cell-values (map stats-map col-names)]
                      (map (fn [x cval] [x y cval]) x-coords cell-values)))
                  y-coords data)]
    (concat
     (->> cells
          (filter (fn [[_ _ v]] (filter-fn v)))
          (map cell-fn))
     (when label-fn (map label-fn y-coords data)))))

(defn visualize-svg
  [csv-path svg-path threshold]
  (let [data (load-csv csv-path)
        months (vec (drop 3 (first data)))
        data-map (csv->map parse-borough (drop-last 2 data))
        stats-range (minmax-stats data-map months)
        grid-w 1485
        grid-h 1050]
    (prn :range stats-range)
    (->> (compute-grid
          :data (sort-boroughs data-map months)
          :col-names months
          :width grid-w :height grid-h
          :cell-fn (make-svg-cell
                    (make-scale stats-range [1 50])
                    (make-heatmap stats-range [1/6 1] 1.0 1.0)
                    0.5)
          :label-fn svg-label
          :filter-fn #(> % threshold))
         (svg/svg {:width grid-w :height grid-h})
         (svg/->xml)
         (spit svg-path))))

(defn visualize-lux
  [csv-path lux-path threshold]
  (let [data (load-csv csv-path)
        months (vec (drop 3 (first data)))
        data-map (csv->map parse-borough (drop-last 2 data))
        [_ max :as stats-range] (minmax-stats data-map months)
        grid-w 3
        grid-h 2.25]
    (->> (compute-grid
          :data (sort-boroughs data-map months)
          :col-names months
          :width grid-w :height grid-h
          :cell-fn (make-3d-cell
                    (g/div (g/vec2 grid-w grid-h) -2.0)
                    (make-scale stats-range [0.008 0.05])
                    (make-scale stats-range [0.03 0.63]))
          :filter-fn #(> % threshold))
         (lux/add-data-meshes-to-scene
          lux/base-scene
          (lux/make-lux-material
           (make-heatmap [threshold max] [1/6 1.0] 0.8 0.8)))
         (lux/export-scene lux-path))
    nil))

(comment

  (visualize-svg "data/binge.csv" "export/binge.svg" 50)
  (visualize-lux "data/binge.csv" "export/binge" 50)

  (visualize-svg "data/assaults-women.csv" "export/women.svg" 10)
  (visualize-lux "data/assaults-women.csv" "export/women" 10)

  (visualize-svg "data/injuries-knife.csv" "export/knife.svg" 0)
  (visualize-lux "data/injuries-knife.csv" "export/knife" 0)

)
