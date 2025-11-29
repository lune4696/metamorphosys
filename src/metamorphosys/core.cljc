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
  [:fn (fn [x] (and (= (type (atom x)) (type x))
                    (m/validate SysMap @x)))])

(def Path
  "schema: access path against `system`"
  [:vector {:min 1} :any])

(def Pathable
  "schema: access path against `system`"
  [:or Path :keyword])

(def Paths
  "schema: access paths against `system`"
  [:vector {:min 1} Path])

(def Act
  "schema: `system`'s action function

  Input must be single vector, whose minimal length is 1."
  [:=> [:cat [:sequential {:min 1} :any]] :any])

(def Action
  "schema: `system`'s action"
  [:map-of :keyword Act])

(def Reaction
  "schema: `observer`'s reaction"
  [:vector {:min 1} :keyword])

(def Observer
  "schema: `system`'s observer"
  [:map-of Paths [:map-of Pathable Reaction]])

(def Observed
  "schema: `system`'s observed `path` pool"
  [:set Path])

(def Reacted
  "schema: `system`'s reacted `observer` list (map)"
  [:map-of Paths :any])

(defn system
  "initalize system from map *m*

  Example: (initialize {:tree {:a {:b 0} :c {[:d 0] 0} :f 0}})

  In metamorphosys, all data you want to enable `observation`
  must be stored in a big map atom = `system`.
  Thus all data can be represented by key vector = `path` (e.g. [:a :b]).
  Thanks to clojure's Abstract Data Type, data field can also be accessed by path.
  This path representation and its interaction is core functionality of this library."
  {:malli/schema [:=> [:cat SysMap] SysAtom]}
  
  [m] (atom (with-meta m {::action {}
                          ::observer {}
                          ::observed #{}
                          ::reacted {}})))

(defn get-action
  "get actions map of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Action]}
  [sys] (-> @sys meta ::action))

(defn get-observer
  "get observers map of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Observer]}
  [sys] (-> @sys meta ::observer))

(defn get-observed
  "get observed path set of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Observed]}
  [sys] (-> @sys meta ::observed))

(defn get-reacted
  "get reacted path map of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Reacted]}
  [sys] (-> @sys meta ::reacted))

(defn assoc-in!
  "assoc *data* in *sys* at *path*

  Example:
  ```
  (let [sys (system {:tree {:a {:b 0}}})]
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
  (let [sys (system {:tree {:a {:b 0}}})]
    (dissoc-in! (system {:tree {:a {:b 0}}}) [:tree :a :b]))
  ;; => {:tree {:a {}}}
  ```

  Similar to clojure.core/dissoc!, but it's against map atom."
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys path] (swap! sys update-in (butlast path) dissoc (last path)))

(defn add-action
  "add *act* as *kw* to *sys*'s `action`

  Note:
  - *act* args should be `[[out & ins]]` (1 output, 0~ inputs)
  - *act* should be pure function against *sys*
    - side effect against outside *sys* is totally fine (c.f. `printer`)

  Example:
  ```
  (let [sys (system {:tree {:a {:b 0} :c {:d 0} :e 0}})]
        (add-action sys :print printer)
        (add-action sys :e<-b.d (fn [[e b d]] (+ e b d)))
        (get-action sys))
  ;; => {:print #function[metamorphosys.core/printer]
         :e<-b.d #function[user/eval23936/fn--23938]}
  ```

  Now system can call/apply *act* against *kw* in `reaction`."
  {:malli/schema [:=> [:cat SysAtom :keyword Act] :any]}
  [sys kw act] (swap! sys vary-meta assoc-in [::action kw] act))

(defn del-action
  "delete *kw*'s act from *sys*'s `action`

  Example:
  ```
  (let [sys (system {:tree {:a {:b 0}}})]
        (add-action sys :print printer)
        (del-action sys :print)
        (get-action sys))
  ;; => {}
  ```

  Now system cannot call/apply *act* against *kw* in `reaction`."
  {:malli/schema [:=> [:cat SysAtom :keyword] :any]}
  [sys kw] (swap! sys vary-meta update-in [::action] dissoc kw))

(defn hook
  "hook *sys*'s observer: *in-paths* --[*reaction*]--> *out-path*

  Note:
  - *in-paths* is a *reaction* chain's input path vector (= `paths`).
  - *out-path* is a *reaction* chain's output `path`.
  - *reaction* is a `action` keyword vector.
  - composed reaction will be pure against *sys*, because action are so.

  Example:
  ```
  (let [sys (system {:tree {:a {:b 0} :c {:d 0}}})]
    (add-action sys :printer printer)
    (add-action sys :d<-b (fn [[d b]] (+ d b)))
    (hook sys [[:tree :c :d]] [:tree :a :b] [:printer :b<-d])
    (get-observer sys))
  ;; => {[[:tree :c :d]] {[:tree :a :b] [:printer :b<-d]}}
  ```
 
  When ALL paths in *in-paths* are observed, *reaction* executes.
  This enables multi-input derived values (like spreadsheet formulas).

  Now user/system can trigger `observer` and dependency graph by `observe!`."
  {:malli/schema [:=> [:cat SysAtom Paths Pathable Reaction] :any]}
  [sys in-paths out-path reaction]
  (swap! sys vary-meta assoc-in [::observer in-paths out-path] reaction))

(defn unhook
  "unhook observer(s) from *sys* 

  Note:
  - 1-arity: Unhook ALL observers from *in-paths*
  - 2-arity: Unhook specific *in-paths* → *out-path* observer

  Example:
  ```
  (let [sys (system {:tree {:a {:b 0}}})]
    (hook sys [[:tree :c :d]] [:tree :a :b] [:printer :b<-d])
    (hook sys [[:tree :c :d]] [:tree :e :f] [:printer :b<-d])
    (unhook sys [[:tree :c :d]] [:tree :a :b])
    (get-observer sys))
  ;; => {[[:tree :c :d]] {[:tree :a :b] [:printer :b<-d]}}
  ```

  Now user/system cannot trigger *observer* and dependency graph by *observe!*."
  {:malli/schema [:function
                  [:=> [:cat SysAtom Paths] :any]
                  [:=> [:cat SysAtom Paths Pathable] :any]]}
  ([sys in-paths]
   (swap! sys vary-meta assoc-in [::observer in-paths] {}))
  ([sys in-paths out-path]
   (swap! sys vary-meta update-in [::observer in-paths] dissoc out-path)))

(defn to-paths
  "turn path *args* into unique (sorted) `paths`

  Example:
  ```
  (to-paths [:a] [:b])
  ;; => [[:a] [:b]]
  (to-paths [:b] [:a])
  ;; => [[:a] [:b]]
  ```
  This ensures `hook` uniqueness regardless of *args* order,
  thus prevent duplicated registration of multiple input's hook."
  {:malli/schema [:=> [:cat [:* Path]] Paths]}
  [& args] (vec (sort args)))

(defn observed?
  "check *sys*'s *paths*'s observer is observed"
  {:malli/schema [:=> [:cat SysAtom Paths] :boolean]}
  [sys paths] (subset? (set paths) (get-observed sys)))

(defn reacted?
  "check *sys*'s *paths*'s observer is reacted"
  {:malli/schema [:=> [:cat SysAtom Paths] :boolean]}
  [sys paths] (contains? (get-reacted sys) paths))

(defn- observed
  "make it observed to *sys*'s *paths*'s observer"
  {:malli/schema [:=> [:cat SysAtom Paths] :any]}
  [sys paths] (swap! sys vary-meta update ::observed #(union % (set paths))))

(defn- reacted
  "make it reacted to *sys*'s *paths*'s observer"
  {:malli/schema [:=> [:cat SysAtom Paths] :any]}
  [sys paths] (swap! sys vary-meta assoc-in [::reacted paths] nil))

(defn- fns?
  "check all of the *coll*'s elements are function"
  {:malli/schema [:=> [:cat [:vector :any]] :boolean]}
  [coll] (reduce (fn [acc in] (and acc (fn? in))) coll))

(defn- observes! 
  "*sys*'s internal observation against *in-paths*

  Recursively called until there is no `observed` but non-`reacted` observer.
  - Observer is regarded as observed when all *in-paths* are (in) observed."
  {:malli/schema [:=> [:cat SysAtom Paths] :any]}
  [sys in-paths]
  (when-let [reactions ((get-observer sys) in-paths)]
    (reacted sys in-paths)
    (let [argv (mapv #(get-in @sys %) in-paths)]
      (mapv (fn [[out reacts]]
              (let [arg (if (vector? out)
                          (get-in @sys out)
                          out)
                    acts (mapv #((get-action sys) %) reacts)]
                (when (and (some? arg)
                           (fns? acts)
                           (if (vector? out)
                             (not (observed? sys [out]))
                             true))
                  (if (vector? out)
                    (do
                      (observed sys [out])
                      (swap! sys update-in out #(concat [%] argv))
                      (swap! sys update-in out (apply comp (reverse acts))))
                    ((apply comp (reverse acts)) (concat [arg] argv))))))
            reactions))
    (when-let [paths (first (filter #(and (observed? sys %)
                                          (not (reacted? sys %)))
                                    (keys (get-observer sys))))]
      (recur sys paths))))

(defn observe! 
  "`sys`'s external observation aganinst [`path`],
  trigger internal observation dependency graph

  Note: Returns :success on success, nil on failure...
  - given `path` does not exist in `sys`.
  - given [`path`] is already observed (or reacted).

  Example:
  ```
  (let [sys (system {:tree {:a {:b 1} :c {:d 0}}})]
    (add-action sys :printer printer)
    (add-action sys :d<-b (fn [[d b]] (+ d b)))
    (hook sys [[:tree :c :d]] [:tree :a :b] [:printer :d<-b])
    (observe! sys [:tree :c :d] inc)
    @sys)
  ;; out: 1, in: (1)
  ;; => {:tree {:a {:b 2}} :c {:d 1}}
  ```
  This is the PRIMARY way to modify system state reactively.
  Direct `swap!` bypasses observation and breaks the dependency chain."
  {:malli/schema [:=>
                  [:cat SysAtom Pathable [:=> [:cat :any] :any]]
                  [:or :keyword :nil]]}
  [sys path f]
  (when (and (some? (get-in @sys path))
             (not (observed? sys [path]))
             (not (reacted? sys [path])))
    (observed sys [path])
    (swap! sys update-in path f)
    (observes! sys [path])
    :success))

(defn recover!
  "clear *sys*'s observe/react state and recover *sys to be re-observed* 

  Once you `observe!` some `path`, path and its precedant observers
  are automatically observed and react.
  Those information are stored in *sys*'s metadata to wait next user input 
  and prevent observer's double-call. 
  So, when all inputs from outside *sys* are processed,
  user must call `recover!` to clean up the information.
  Typically, user will call it at the end of frame or tick or any equivalent.
  Though it's not recommended, user can modify those info by your hand
  to avoid state recovering.
  But in that case, user should consider launch another `system`."
  {:malli/schema [:=> [:cat SysAtom] :any]}
  [sys] (swap! sys vary-meta assoc ::observed #{} ::reacted {}))

(defn printer
  "example *action*: print given args and pass all args to next"
  {:malli/schema Act}
  [[out & args :as all]]
  (println "out:" out "in:" args)
  all)
