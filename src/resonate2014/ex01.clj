(ns resonate2014.ex01
  "This example contains our little Infocom-style text adventure
  exercise, in which we introduced working with nested data structures
  and learned about ways to deal with state in an interactive
  environment. During the development we discussed Clojure's approach
  to manipulating state using different reference types, but settled
  on using atoms and learned how to use higher order functions to
  update their state and illustrate their use in event handlers for
  certain player actions. The example also served as precursor to
  start thinking about using graphs as data structures to encode
  relationships between elements. This mini game setup defines a
  number of rooms which are connected in different directions (east,
  west etc.) and thus is forming a graph. Finally, we introduced the
  concept of dynamic variables and showed how their use could help us
  in defining a tiny DSL to simplify playing the game in the REPL. The
  resulting setup should be flexible enough to add more interactive
  storytelling & game related puzzle features."
  ;; We want to use `take` & `drop` as game specific commands allowing
  ;; the player to pickup/drop items, but the clojure.core namespace
  ;; defines these already as functions. Therefore we need to exclude
  ;; their default import from clojure using this form:
  (:refer-clojure :exclude [take drop]))

;; TODOs / homework / hotelwork
;;
;; error/help messages for take/drop/go
;;   (i.e. wrong item/direction ids given)
;;
;; add constraints to limit max number/weight of items player can carry
;;
;; how to make multi-player?
;;   what are the issues in terms of game state?

(def opposite-dirs
  "This map defines pairs of opposite directions. It is used by the fn
  below to turn our directed graph of rooms into an undirected one."
  {:east :west
   :west :east
   :north :south
   :south :north
   :up :down
   :down :up})

(defn backlinks-single-room
  "Takes a map of rooms and room ID. The room map is encoding a graph
  structure (see further below). Looks up all defined linked rooms for
  given one and for those rooms injects backlinks (in opposite
  direction) to current one. Returns updated map. The backlinks are
  essentially turning the room graph into an undirected one, since the
  final result will be that we're able to navigate the graph from
  any (valid) direction."
  [room-map room-id]
  (reduce
   (fn [accumulator [dir target]]
     (if (get-in accumulator [target :trap?])
       ;; don't process if target is a trap
       accumulator
       ;; else, create back link from target in opposite dir
       (assoc-in
        accumulator
        [target :links (opposite-dirs dir)] room-id)))
   ;; the original room-map is the accumulator for reduce
   room-map
   ;; the links map of the current room is the collection
   ;; to be reduced/iterated over
   (get-in room-map [room-id :links])))

(defn infer-backlinks
  "Takes a map of rooms (the game map) and produces backlinks for each
  room, based on its given outgoing links. Returns transformed room map."
  [rooms]
  (reduce backlinks-single-room rooms (keys rooms)))

(defn list-items
  "Takes a seq of values, a prefix string and default message. Formats
  items as comma separated string prefixed with prefix and the last
  item itself prefixed with the word `and`. If seq only has a single
  item, returns prefixed item. If seq is empty, returns default
  message. Example:

      (list-items [1 2 3] \"You have: \" \"Nothing\")
      => \"You have: 1,2 and 3\"

      (list-items [] \"You have: \" \"Nothing\")
      => \"Nothing\" "
  [coll prefix not-found]
  (let [coll (sort coll)]
    (if (seq coll)
      (if (= 1 (count coll))
        ;; for single item, simply add prefix
        (str prefix (first coll))
        ;; for >1 items, form comma separated list and
        ;; insert `and` before last item
        (apply
         str
         (concat
          prefix
          (interpose ", " (butlast coll))
          " and "
          [(last coll)])))
      ;; no items in seq, return not-found message
      not-found)))

;; Clojure has a single pass compiler, so if we want to refer to
;; symbols not yet defined, we need to declare them first...
(declare item-props room-ids)

(defn describe-room
  "Takes a game state map and returns a string fully describing the room
  the player is currently in. We list all items in the room and the
  possible directions for the player to go to (but don't tell which
  rooms these directions lead to)."
  [{:keys [rooms player]}]
  (let [curr-room-id (:room player)
        curr-room (rooms curr-room-id)]
    (str
     "You are in " (room-ids curr-room-id) ". "
     (list-items
      (map (comp :desc item-props) (:items curr-room))
      "You can see "
      "There's nothing else here")
     ".\n"
     (list-items
      (map name (keys (:links curr-room)))
      "You can go "
      "It's a trap, you can't go anywhere")
     ".")))

(defn move-player
  "Takes a game state map and a direction ID. If the direction is
  valid for the player's current room, moves player in that direction.
  Returns updated game map."
  [{:keys [rooms player] :as state} dir]
  ;; check if dir is valid
  ;; if valid, update :room in player
  (if-let [target-room (get-in rooms [(:room player) :links dir])]
    (assoc-in state [:player :room] target-room)
    state))

(defn take-item
  "Takes a game state map and item ID. If item is located in the room
  and can be picked up, removes it from there and places it into the
  player's inventory. Returns updated game map. If item's spec also
  has an associated `:take-fn`, calls fn with current game state
  *after* updating it. This means this fn must return a valid game
  state map itself (or just the original if no further modifications
  should take place as a result of picking up an item)."
  [{:keys [rooms player] :as state} item-id]
  (let [item-spec (item-props item-id)]
    (if (and (get-in rooms [(:room player) :items item-id])
             (get item-spec :take? true))
      (-> state
          (update-in [:rooms (:room player) :items] disj item-id)
          (update-in [:player :items] conj item-id)
          ((get item-spec :take-fn identity)))
      state)))

(defn drop-item
  "Takes a game state map and item ID. If player carries item, removes
  it from inventory and places it in current room. Returns updated
  game map. If item has an associated `:drop-fn` calls fn with current
  game state *before* updating it. (see `take-item` for details)"
  [{:keys [rooms player] :as state} item-id]
  (if (get-in player [:items item-id])
    (-> ((get-in item-props [item-id :drop-fn] identity) state)
        (update-in [:player :items] disj item-id)
        (update-in [:rooms (:room player) :items] (fnil conj #{}) item-id))
    state))

(defn inventory
  "Takes a game state map and returns string listing all items carried
  by the player."
  [{:keys [player]}]
  (str
   (list-items
    (map (comp :desc item-props) (:items player))
    "You carry "
    "You carry nothing")
   "."))

(defn make-link-fn
  "Higher order utility fn. Takes a room ID, direction and target room
  ID. Returns fn, which when called accepts a game state map and
  injects a new bi-directional link between the given rooms, then
  returns updated game map. See :rope item spec for concrete use case."
  [from dir to]
  (fn [game]
    (-> game
        (update-in [:rooms from :links] assoc dir to)
        (update-in [:rooms to :links] assoc (opposite-dirs dir) from))))

(defn make-unlink-fn
  "Similar to `make-link-fn`, only destroys a connection between rooms."
  [from dir to]
  (fn [game]
    (-> game
        (update-in [:rooms from :links] dissoc dir)
        (update-in [:rooms to :links] dissoc (opposite-dirs dir)))))

;; The following functions are all event handlers associated with
;; various player actions and attached to taking/dropping items.
;; Each fn accepts the current game state map and manipulates (or even
;; replaces it).

(defn take-mushrooms
  "Event handler for (take :mushrooms) action. Replaces entire game
  state with single room death trap."
  [game]
  (println
   "And there a little voice is telling you:\n"
   "\"One should never mix these with whiskey!\"...")
  {:rooms {:fairyland {:trap? true}}
   :player {:room :fairyland :items #{}}})

(defn take-flashlight
  "Called when player takes :flashlight item. Adds :rope item to :cave
  room (without flashlight the rope isn't visible by default)."
  [game]
  (println "You're thinking, this might come in handy in dark corners...")
  (update-in game [:rooms :cave :items] conj :rope))

(defn drop-flashlight
  "Undo effect of `take-flashlight`, removes :rope
  from :cave (therefore hiding it)."
  [game]
  (println "Maybe you don't need this anymore, after all?")
  (update-in game [:rooms :cave :items] disj :rope))

(defn separator [char] (apply str (repeat 60 char)))

(defn player-wins
  "Prints out winning/game over message."
  [game]
  (println
   (str
    (separator \*)
    "\nYou've found the treasure!"
    "\nGame over!\n"
    (separator \*)))
  game)

(defn player-death-by-frog
  "Prints out death/game over message."
  [game]
  (println
   (str
    (separator \!)
    "\nMaybe you thought this frog was a prince, but it's poisonous."
    "\nYou're dead. Game over!\n"
    (separator \!)))
  game)

(def room-ids
  "This map is associating room IDs with their human readable, longer
  descriptions. The room IDs are used later on to define the actual
  graph forming the entire game environment."
  {:hut           "your hut"
   :cave-entrance "the entrance of a cave"
   :cave          "the darkest corner of the cave"
   :garden        "the garden"
   :well          "the well infront of the house"
   :well-bottom   "the bottom of the well"
   :forest        "the deep forest"
   :swamp         "a treacherous swamp"
   :meadow        "the magical forest meadow"
   :fairyland     "a magical land of fluorescent butterflies & spiralling sugar cones"})

(def item-props
  "Similar to the `room-ids` map, only here the value for each item is
  a map itself. This way we can specify further attributes for each
  item, e.g. we want to add a restriction for some items in order to
  forbid the player to pick them up. An item can be picked up by the
  player unless an item's spec has the `:take?` key set to `false`.
  Furthermore, a `:take-fn` and `:drop-fn` can be given, which are
  called with the current game state when the item is being picked up
  or dropped by the player. These fns can manipulate the game
  state (e.g. add new rooms/connections) or just print a message, but
  they MUST return a valid game state."
  {:frog         {:desc "a croaking frog"
                  :take-fn player-death-by-frog}
   :bed          {:desc "an unmade bed"
                  :take? false}
   :bottle       {:desc "an empty bottle of whiskey"}
   :bats         {:desc "some vampire bats hanging above"
                  :take? false}
   :trees        {:desc "the tallest trees you've ever seen"
                  :take? false}
   :mushrooms    {:desc "some magic mushrooms"
                  :take-fn take-mushrooms}
   :flowers      {:desc "some beautiful flowers"}
   :gold         {:desc "a pot of gold coins"
                  :take-fn player-wins}
   :rope         {:desc "a long piece of old rope"
                  :take-fn (make-link-fn :well :down :well-bottom)
                  :drop-fn (make-unlink-fn :well :down :well-bottom)}
   :picnic       {:desc "a picnic basket"}
   :flashlight   {:desc "a flashlight"
                  :take-fn take-flashlight
                  :drop-fn drop-flashlight}
   :clj-workshop {:desc "a Clojure workshop (OMG!)"
                  :take? false}})

(def game
  "This map constitutes the main game state, i.e. player information
  and the dynamically updateable network of rooms and items located
  within them. To simplify editing the room graph, only
  uni-directional links need to be specified, but the network is then
  passed to the `infer-backlinks` fn to produce bi-directional
  connections for all rooms which are not traps.

  It would also make sense (and be trivial) to move the above
  maps (room-ids & item-props) into this game state map, but we
  haven't done so for documentation reasons."
  {:player {:room :hut :items #{}}
   :rooms (infer-backlinks
           {:hut           {:links {:east :garden}
                            :items #{:bottle :bed}}

            :cave-entrance {:links {:west :cave}
                            :items #{:bats}}
            :cave          {:items #{}}

            :garden        {:links {:north :forest, :east :swamp, :south :well}
                            :items #{:flowers}}

            :well          {:items #{:frog}}
            :well-bottom   {:items #{:gold}}

            :forest        {:links {:north :cave-entrance}
                            :items #{:trees}}

            :meadow        {:links {:east :forest}
                            :items #{:mushrooms :flashlight :picnic}}

            :swamp         {:trap? true
                            :items #{:clj-workshop}}})})

;; REPL game commands with dynamically bound game state var
;; Unlike lexical scoping created with `let`, dynamic binding
;; is depending on threads of execution and can be altered on a
;; thread-by-thread basis (not important here)...

(def ^:dynamic *game* (atom game))

(comment
  ;; demo use of `binding` form to temporarily shadow default binding
  ;; of *game* var for currently running thread
  (binding [*game* (atom {:rooms {:hut {}} :player {:room :hut}})]
    (describe))
  ;; Results in this output:
  ;; "You are in your hut. There's nothing else here.
  ;;  It's a trap, you can't go anywhere.
  ;;  You carry nothing."
  )

;; The functions below are our actual game commands and constitute
;; a very basic domain-specific language for playing the game
;;
;; Each command is utilizing the current binding of the dynamic *game*
;; var holding the complete game state (player & room specs)
;; This way the player does not need to provide the game state
;; manually and only needs to specify the arguments needed for the
;; actual game action (e.g. an item ID)
;; NOTE: directions & item IDs still need to be given as :keyword
;; We could have written these functions as macros to avoid this, but
;; we didn't discuss Clojure's macro system in the workshop

(defn describe []
  (println (separator \-))
  (println (describe-room @*game*)) ;; @ = deref
  (println (inventory @*game*)))

(defn go
  [dir]
  ;; The swap! function applies another function to the current value
  ;; of an atom, but can also take further parameters (as in this
  ;; case). The return value of that fn (here `move-player`) is then
  ;; used as the new value for the atom
  (swap! *game* move-player dir)
  (describe))

(defn take
  [id]
  (swap! *game* take-item id)
  (describe))

(defn drop
  [id]
  (swap! *game* drop-item id)
  (describe))

(defn restart
  []
  ;; unlike swap!, the reset! function merely replaces an atom's value
  (reset! *game* game)
  (println "Welcome to the Resonate 2014 Clojure adventure.")
  (println "Can you find the golden treasure?")
  (println "Type (help) for a list of commands...")
  (describe))

(defn help
  []
  (let [{:keys [rooms player]} @*game*
        curr-room (rooms (:room player))
        links (keys (:links curr-room))
        items (filter #(not (false? (get-in item-props [% :take?]))) (:items curr-room))
        indent (apply str (repeat 18 " "))]
    (println "Okay, you can try some of these:")
    (println (separator \-))
    (println "(describe)      - show room description & inventory")
    (println "(go :direction) - go to next room, possible directions: " "")
    (println (list-items links indent "(can't go anywhere from here)"))
    (println "(take :item)    - carry an item, possible items:")
    (println (list-items items indent "(no items to take from here)"))
    (println "(drop :item)    - stop carrying an item")
    (println (list-items (:items player) indent (str indent "(not possible right now)")))
    (println "(restart)       - restart game")))

;; Trigger the welcome message
(restart)

;; Complete walkthrough
(comment
  (restart)
  (go :east)
  (go :north)
  (go :west)
  (take :flashlight)
  ;; (take :mushrooms)
  (go :east)
  (go :north)
  (go :west)
  (take :rope)
  (go :east)
  (go :south)
  (go :south)
  (go :south)
  (go :down)
  (take :gold))
