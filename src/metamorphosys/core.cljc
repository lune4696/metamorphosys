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

(def Act
  "schema: `system`'s (pure) action function

  Input must be single seq, whose minimal length is 2 (1 out, 1+ in(s))."
  [:=> [:cat [:sequential {:min 2} :any]] :any])

(def Actions
  "schema: `system`'s action"
  [:map-of :keyword Act])

(def Reaction
  "schema: `observer`'s reaction"
  [:vector {:min 1} :keyword])

(def PathKw
  "schema: index against `observer`"
  [:or Path :keyword])

(def Observer
  "schema: `system`'s observer"
  [:map-of PathKw [:map [:paths [:vector Path]] [:reaction Reaction]]])

(def Observers
  "schema: `system`'s observers (map)"
  [:map-of Path Observer])

(def Observed
  "schema: `system`'s observed `path` pool"
  [:set Path])

(def Triggered
  "schema: `system`'s triggered `path` queue"
  [:sequential Path])

(defn system
  "initalize system from map *m*

  Example: (initialize {:tree {:a {:b 0} :c {[:d 0] 0} :f 0}})

  In metamorphosys, all data you want to enable `observation`
  must be stored in a big map atom = `system`.
  Thus all data can be represented by key vector = `path` (e.g. [:a :b]).
  Thanks to clojure's Abstract Data Type, data field can also be accessed by path.
  This path representation and its interaction is core functionality of this library."
  {:malli/schema [:=> [:cat SysMap] SysAtom]}
  
  [m] (atom (with-meta m {::actions {}
                          ::observers {}
                          ::observed #{}
                          ::triggered []})))

(defn get-actions
  "get actions (kw-act map) of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Actions]}
  [sys] (-> @sys meta ::actions))

(defn get-act
  "get *kw* act from *sys*'s `actions`"
  {:malli/schema [:=> [:cat SysAtom :keyword] Act]}
  [sys kw] ((get-actions sys) kw))

(defn get-observers
  "get observers of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Observers]}
  [sys] (-> @sys meta ::observers))

(defn get-observer
  "get *sys*'s observer against *in* path (if any)"
  {:malli/schema [:=> [:cat SysAtom Path] [:or Observer :nil]]}
  [sys in] ((get-observers sys) in))

(defn get-observed
  "get observed path set of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Observed]}
  [sys] (-> @sys meta ::observed))

(defn get-triggered
  "get triggered path queue of *sys*"
  {:malli/schema [:=> [:cat SysAtom] Triggered]}
  [sys] (-> @sys meta ::triggered))

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
  - *act* args should be `[[out in & others]]` (1 out, 1+ in(s))
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
  [sys kw act] (swap! sys vary-meta assoc-in [::actions kw] act))

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
  [sys kw] (swap! sys vary-meta update-in [::actions] dissoc kw))

(defn hook
  "hook *sys*'s observer: *in* (+ *paths*) --[*reaction*]--> *out*

  Note:
  - *in* is a input `path` of *reaction* chain.
  - *out* is a output path of *reaction* chain.
  - *reaction* is a keyword vector represents what `action` will occur.
  - *paths* is a path vector used as reference inputs of *reaction* chain.
  - composed reaction will be pure against *sys*, because action are so.

  Example:
  ```
  (let [sys (system {:tree {:a {:b 0} :c {:d 0} :e 0}})]
    (add-action sys :printer printer)
    (add-action sys :e<-b+d (fn [[e b d]] (+ b d)))
    (hook sys [:tree :a :b] [:tree :e] [:printer :e<-b+d] [[:tree :c :d]])
    (get-observer sys))
  ;; => {[:tree :a :b] {[:tree :e] {:args [[:tree :c :d]]
                                    :reaction [:printer :e<-b+d]}}}
  ```
 
  1 vs 1 relation between in-out is essential for scalable cascade model.
  If you want true multi-input (like spreadsheet formulas), make multiple hooks.

  Now user/system can trigger `observer` and dependency graph by `observe!`."
  {:malli/schema [:function
                  [:=> [:cat SysAtom Path PathKw Reaction] :any]
                  [:=> [:cat SysAtom Path PathKw Reaction Paths] :any]]}
  ([sys in out reaction]
   (swap! sys vary-meta assoc-in [::observers in out] {:paths []
                                                       :reaction reaction}))
  ([sys in out reaction paths]
   (swap! sys vary-meta assoc-in [::observers in out] {:paths paths
                                                       :reaction reaction})))

(defn unhook
  "unhook observer(s) from *sys* 

  Note:
  - 1-arity: Unhook observer against *in* completely
  - 2-arity: Unhook specific *out* from observer against *in* 

  Example:
  ```
  (let [sys (system {:tree {:a {:b 0} :c {:d 0}}})]
    (hook sys [:tree :a :b] [:tree :c :d] [:printer :d<-b])
    (hook sys [:tree :a :b] [:tree :e :f] [:printer :f<-b])
    (unhook sys [:tree :e :f] [:tree :a :b])
    (get-observer sys))
  ;; => {[:tree :a :b] {[:tree :c :d] [:printer :d<-b]}}
  ```

  Now user/system can no longer trigger *observer* and cascade by *observe!*."
  {:malli/schema [:function
                  [:=> [:cat SysAtom Path] :any]
                  [:=> [:cat SysAtom Path PathKw] :any]]}
  ([sys in] (swap! sys vary-meta update-in [::observers] dissoc in))
  ([sys in out] (swap! sys vary-meta update-in [::observers in] dissoc out)))

(defn observed?
  "check *sys*'s *path*' observer is observed"
  {:malli/schema [:=> [:cat SysAtom Path] :boolean]}
  [sys path] (subset? (set [path]) (get-observed sys)))

(defn- observed
  "make it observed to *sys*'s *path* observer"
  {:malli/schema [:=> [:cat SysAtom PathKw] :any]}
  [sys path] (swap! sys vary-meta update ::observed #(union % (set [path]))))

(defn- triggered
  "add *path* to *sys*'s `triggered` observer queue"
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys path] (swap! sys vary-meta update ::triggered #(conj % path)))

(defn- release
  "release a first `path` from *sys*'s `triggered` path queue"
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys] (swap! sys vary-meta update ::triggered rest))

(defn- fns?
  "check all of the *coll*'s elements are function"
  {:malli/schema [:=> [:cat [:vector :any]] :boolean]}
  [coll] (reduce (fn [acc in] (and acc (fn? in))) coll))

(defn- observes! 
  "*sys*'s internal observation against *in* `path`

  Recursively called until all `triggered` paths are `observed`.
  = *sys*'s `triggered` queue becomes empty"
  {:malli/schema [:=> [:cat SysAtom Path] :any]}
  [sys in]
  (when vector? (observed sys in))
  (when-let [observer (get-observer sys in)] 
    (mapv (fn [[out {:keys [paths reaction]}]]
            (let [arg (if (vector? out) (get-in @sys out) out)
                  argv (mapv #(get-in @sys %) (cons in paths))
                  reacts (cons #(cons % argv)
                               (map #(get-act sys %) reaction))]
              (when (and (some? arg)
                         (fns? reacts)
                         (or (keyword? out)
                             (not (observed? sys out)))) 
                (if (vector? out)
                  (do (triggered sys out)
                      (swap! sys update-in out (apply comp (reverse reacts))))
                  ((apply comp (reverse reacts)) arg)))))
          observer))
  (when-let [path (first (get-triggered sys))]
    (release sys)
    (recur sys path)))

(defn observe! 
  "`sys`'s external observation aganinst [`path`],
  trigger internal observation cascade 

  Note: Returns :success on success, nil on failure...
  - given `path` does not exist in `sys`.
  - given `path` is already observed (or reacted).

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
                  [:cat SysAtom Path [:=> [:cat :any] :any]]
                  [:or :keyword :nil]]}
  [sys path f]
  (when (and (some? (get-in @sys path))
             (not (observed? sys path)))
    (swap! sys update-in path f)
    (observes! sys path)
    :success))

(defn recover
  "clear *sys*'s observe/react state and recover *sys to be re-observed* 

  Once you `observe!` some `path`, path and its precedant observers
  are automatically observed and react.
  Those information are stored in *sys*'s metadata to wait next user input 
  and prevent observer's double-call. 
  So, when all inputs from outside *sys* are processed,
  user must call `recover` to clean up the information.
  Typically, user will call it at the end of frame or tick or any equivalent.
  Though it's not recommended, user can modify those info by your hand
  to avoid state recovering.
  But in that case, user should consider launch another `system`."
  {:malli/schema [:=> [:cat SysAtom] :any]}
  [sys] (swap! sys vary-meta assoc ::observed #{} ::reacted []))

(defn printer
  "example *action*: print given args and pass all args to next"
  {:malli/schema Act}
  [[out & args :as all]]
  (println "out:" out "in:" args)
  all)
