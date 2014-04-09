(ns resonate2014.ex03.svg)

(defn xml-attribs
  [attribs]
  (reduce
   (fn [s [k v]]
     (str s " " (name k) "=\"" v "\""))
   "" attribs))

(defn ->xml
  [[tag attribs & children]]
  (if (string? tag)
    tag
    (let [tag (name tag)
          attribs (xml-attribs attribs)]
      (str
       "<" tag " " attribs ">"
       (reduce
        (fn [child-str c]
          (str child-str (->xml c)))
        "" children)
       "</" tag ">"))))

(defn svg
  [attribs elements]
  (concat
   [:svg
    (merge
     {"xmlns:svg" "http://www.w3.org/2000/svg"
      "xmlns" "http://www.w3.org/2000/svg"
      "xmlns:xlink" "http://www.w3.org/1999/xlink"
      "version" "1.0"}
     attribs)]
   elements))

(defn circle
  [x y r & {:as attribs}]
  [:circle (merge attribs {:cx x :cy y :r r})])

(defn text
  [x y content & {:as attribs}]
  [:text (merge attribs {:x x :y y}) [content]])

(defn rgb->hex
  ([[r g b]] (rgb->hex r g b))
  ([r g b] (format "#%02x%02x%02x" r g b)))

(comment
  (->> [[50 50 10 :fill "red"] [150 50 20 :fill "green"] [250 50 40 :fill "blue"]]
       (map #(apply circle %))
       (svg {:width 400 :height 100})
       (->xml)
       (spit "rgb-circles.svg"))
  )
