(ns metamorphosys.core
  (:require [clojure.set :refer [union subset?]]
            [malli.core :as m]))

(def SysMap 
  "schema: data can become `system`"
  (m/schema [:schema
             {:registry {::nested-map
                         [:map-of :any [:or
                                        [:not :map]
                                        [:ref ::nested-map]]]}}
             [:ref ::nested-map]]))

(def SysAtom
  "schema: access path against `system`"
  [:fn (fn [x] (and (= (type (atom {})) (type x))
                    (m/validate SysMap @x)))])

(def Path
  "schema: access path against `system`"
  [:vector {:min 1} :any])

(def Paths
  "schema: access paths against `system`"
  [:vector {:min 1} Path])

(def React
  "schema: `system`'s (pure) action function

  Input must be single seq, whose minimal length is 2 (1 out, 1+ in(s))."
  [:=> [:cat [:* :any]] :any])

(def Reaction
  "schema: `observer`'s reaction"
  [:map
   [:react React]
   [:from Paths]
   [:to Path]])

(def Observer
  "schema: `system`'s observer"
  [:map-of Path Reaction])

(def Observers
  "schema: `system`'s observers (map)"
  [:map-of Path [:or Observer :nil]])

(def Observed
  "schema: `system`'s observed `path` pool"
  [:set Path])

(def Triggered
  "schema: `system`'s triggered `path` queue"
  [:sequential Path])

(defmulti ->sys
  "from map or atom to `system`

  Example:
  ```
  (map->sys {:tree {:a {:b 0} :c {[:d 0] 0} :f 0}})
  (map->sys (atom {:tree {:a {:b 0} :c {[:d 0] 0}}}))
  ```

  In metamorphosys, all data you want to enable `observation`
  must be stored in a big map atom = `system`.
  Thus all data can be represented by key vector = `path` (e.g. [:a :b]).
  Thanks to clojure's Abstract Data Type, data field can also be accessed by path.
  This path representation and its interaction is core functionality of this library."
  {:malli/schema [:=> [:cat [:or SysMap SysAtom]] SysAtom]}
  (fn [x] (cond
            (map? x) :map
            (= (type (atom {})) (type x)) :atom
            :else :not-implemented)))

(defmethod ->sys :map [m] (atom (with-meta m {::observers {}
                                              ::observed #{}
                                              ::triggered []})))

(defmethod ->sys :atom [a] (swap! a with-meta {::observers {}
                                               ::observed #{}
                                               ::triggered []}) a)

(defn get-observers
  {:malli/schema [:=> [:cat SysAtom] Observers]}
  [sys] (-> @sys meta ::observers))

(defn get-observed
  {:malli/schema [:=> [:cat SysAtom] Observed]}
  [sys] (-> @sys meta ::observed))

(defn get-triggered
  {:malli/schema [:=> [:cat SysAtom] Triggered]}
  [sys] (-> @sys meta ::triggered))

(defn assoc-in!
  "assoc *data* in *sys* at *path*

  Example:
  ```
  (let [sys (->sys {:tree {:a {:b 0}}})]
    (assoc-in! sys [:tree :a :c] 1))
  ;; => {:tree {:a {:b 0 :c 1}}}
  ```

  Similar to clojure.core/assoc-in, but it's against map atom."
  {:malli/schema [:=> [:cat SysAtom Path :any] :any]}
  [sys path data] (swap! sys assoc-in path data))

(defn dissoc-in!
  "dissoc *data* in *sys* at *path*

  Example:
  ```
  (let [sys (->sys {:tree {:a {:b 0}}})]
    (dissoc-in! (sys {:tree {:a {:b 0}}}) [:tree :a :b]))
  ;; => {:tree {:a {}}}
  ```

  Similar to clojure.core/dissoc!, but it's against map atom."
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys path] (swap! sys update-in (butlast path) dissoc (last path)))

(defn hook
  "hook *sys*'s observer: *from* --[*reaction*]--> *to*

  Note:
  - *in* is a input `path` of *reaction* chain.
  - *out* is a output path of *reaction* chain.
  - *reaction* is a keyword vector represents what `action` will occur.
  - *paths* is a path vector used as reference inputs of *reaction* chain.
  - composed reaction will be pure against *sys*, because action are so.

  Example:
  ```
  (let [sys (->sys {:tree {:a {:b 0} :c {:d 0} :e 0}})]
    (hook sys
          (comp printer (fn [[b d]] (+ b d)))
          [[:tree :a :b] [:tree :c :d]]
          [:tree :e])
    (get-observer sys))
  ;; => {[:tree :a :b] {[:tree :e] {:react ()
                                    :from [[:tree :a :b] [:tree :c :d]]
                                    :to [:tree :e]}}}
  ```

  Now system can trigger `observer` and dependency graph by `observe!`."
  {:malli/schema [:=> [:cat SysAtom React Paths Path] :any]}
  [sys react from to]
  (mapv #(swap! sys vary-meta assoc-in [::observers % to] {:react react
                                                           :from from
                                                           :to to}) from))

(defn- unhook-single
  {:malli/schema [:function
                  [:=> [:cat SysAtom Path] :any]
                  [:=> [:cat SysAtom Path Path] :any]]}
  ([sys in] (swap! sys vary-meta update-in [::observers] dissoc in))
  ([sys in out] (swap! sys vary-meta update-in [::observers in] dissoc out)))

(defn unhook
  "unhook observer(s) from *sys*

  Note:
  - 1-arity: Unhook observer against *in* completely
  - 2-arity: Unhook specific *out* from observer against *in*

  Example:
  ```
  (let [sys (me/->sys {:a 0 :b 0 :c 0})]
    (me/hook sys (fn [a b] (+ a b)) [[:a] [:b]] [:c])
    (println (me/get-observers sys))
    ;; => {[:a] {[:c] {:react #function[user/eval17170/fn--17171],
                       :from [[:a] [:b]], :to [:c]}},
    ;;     [:b] {[:c] {:react #function[user/eval17170/fn--17171],
                       :from [[:a] [:b]], :to [:c]}}}
    (me/unhook sys [:a] [:c])
    (println (me/get-observers sys))
    ;; => {[:a] {}, [:b] {}} 
    (me/hook sys (fn [a b] (+ a b)) [[:a] [:b]] [:c])
    (me/unhook sys [:a])
    (println (me/get-observers sys))
    ;; => {[:a] {}, [:b] {}}
    ) 
  ```

  Now system can no longer trigger *observer* and cascade by *observe!*."
  {:malli/schema [:function
                  [:=> [:cat SysAtom Path] :any]
                  [:=> [:cat SysAtom Path Path] :any]]}
  ([sys in] (let [o ((get-observers sys) in)]
              (mapv (fn [[to {:keys [from]}]] (mapv #(unhook-single sys % to) from)) o)))
  ([sys in out] (let [from (get-in (get-observers sys) [in out :from])]
                  (mapv #(unhook-single sys % out) from))))

(defn observed?
  {:malli/schema [:=> [:cat SysAtom Path] :boolean]}
  [sys path] (subset? (set [path]) (get-observed sys)))

(defn- make-observed
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys path] (swap! sys vary-meta update ::observed #(union % (set [path]))))

(defn- make-triggered
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys path] (swap! sys vary-meta update ::triggered #(conj % path)))

(defn- release
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys] (swap! sys vary-meta update ::triggered rest))

;; (defn- fns?
;;   "check all of the *coll*'s elements are function"
;;   {:malli/schema [:=> [:cat [:vector :any]] :boolean]}
;;   [coll] (reduce (fn [acc in] (and acc (fn? in))) coll))

(defn- cascade! 
  "*sys*'s internal observation cascade against *in* `path`

  Recursively called until all `triggered` paths are `observed`.
  = *sys*'s `triggered` queue becomes empty"
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys in]
  (when vector? (make-observed sys in))
  (when-let [observer ((get-observers sys) in)] 
    (mapv (fn [[to {:keys [react from]}]]
            (let [argv (mapv #(get-in @sys %) from)]
              (when-not (observed? sys to)
                (make-triggered sys to)
                (swap! sys assoc-in to (apply react argv)))))
          observer))
  (when-let [path (first (get-triggered sys))]
    (release sys)
    (recur sys path)))

(defn trigger! 
  "trigger *sys*'s observation cascade starts from *path*

  Note: Return keyword means...
  - :success : all of the internal cascade successfully finished
  - :nil : given *path* does not exist in *sys*.
  - :observed : given *path* is already observed (or reacted).

  Example:
  ```
  (let [sys (->sys {:tree {:a {:b 1} :c {:d 0}}})]
    (hook sys (fn [[b d]] (+ b d)) [[:tree :a :b] [:tree :c :d]] [:tree :a :b])
    (observe! sys [:tree :c :d] inc)
    @sys)
  ;; => {:tree {:a {:b 2}} :c {:d 1}}
  ```"
  {:malli/schema [:=> [:cat SysAtom Path [:=> [:cat :any] :any]] :keyword]}
  [sys path f]
  (cond
    (nil? (get-in @sys path)) :nil
    (observed? sys path) :observed
    :else (do (swap! sys update-in path f)
              (cascade! sys path)
              :success)))

(defn reload
  "reload *sys*'s observe/react state to enable to observe *sys again* 

  Once you `observe!` some `path`, all cascaded paths are reacted and observed.
  Those info is stored in *sys* metadata to wait trigger and prevent double-call.
  So, `reload` must be called to clean up info at end of the frame or equivalent. 

  Though it's not recommended, user can modify those info to avoid state reload.
  But in that case, user should consider launch another `system`."
  {:malli/schema [:=> [:cat SysAtom] :any]}
  [sys] (swap! sys vary-meta assoc ::observed #{} ::reacted []))

(defn observe! 
  "*sys*'s observation cascade starts from *path*

  Note:
  - `observe!` = `trigger!` + `reload`

  Example:
  ```
  (let [sys (->sys {:tree {:a {:b 1} :c {:d 0}}})] 
    (hook sys [[:tree :c :d]] [:tree :a :b] [:printer :d<-b])
    (observe! sys [:tree :c :d] inc)
    @sys)
  ;; => {:tree {:a {:b 2}} :c {:d 1}}
  ```

  This is the PRIMARY way to modify system state reactively.
  Direct `swap!` bypasses observation and breaks the dependency chain."
  {:malli/schema [:=> [:cat SysAtom Path [:=> [:cat :any] :any]] :keyword]}
  [sys path f]
  (let [res (trigger! sys path f)] (reload sys) res))
