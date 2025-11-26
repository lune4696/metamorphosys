(ns metamorphosys.core)

;; メタデータの制約によりコレクションしかオブザーバブル化できない = オブザーバーを付与できないのだが、
;; clojure における型付きのデータ = collection なので問題ない
;; また、メタデータは値でなくシンボル(ないし位置)に紐づいている
;; よって、値を swap! しても system atom が reset! されない限り observable meta は消去されない
;; あくまで observe! によってのみ observer はトリガされる
;; よって、外部から swap! による「観測不可能な干渉」を行う場合、値が変わっても observer はトリガされない
;; 値が変わった後に observe! した時の値がいきなり変わったり、 noop になったりするだけである
;; もちろん、基本的に好ましくはないが、それでシステムが破綻することはない
 
(defn observable
  ([sys] (swap! sys #(with-meta % {::observers {} ::observed? false})))
  ([sys path] (if (empty? path)
                (observable sys)
                (swap! sys update-in path #(with-meta % {::observers {} ::observed? false})))))

(defn unobservable
  ([sys] (swap! sys #(vary-meta % dissoc ::observers ::observed?)))
  ([sys path] (if (empty? path)
                (unobservable sys)
                (swap! sys update-in path #(vary-meta % dissoc ::observers ::observed?)))))

(defn observable?
  ([sys] (let [m (meta @sys)] (not (or (nil? (::observers m))
                                       (nil? (::observed? m))))))
  ([sys path] (if (empty? path)
                (observable? sys)
                (let [m (meta (get-in @sys path))] (not (or (nil? (::observers m))
                                                            (nil? (::observed? m))))))))

(defn entity
  ([sys] @sys)
  ([sys path] (get-in @sys path)))

(defn hook
  ([sys k f] (swap! sys #(vary-meta % assoc-in [::observers k] f)))
  ([sys path k f] (if (empty? path)
                    (hook sys k f)
                    (swap! sys update-in path #(vary-meta % assoc-in [::observers k] f)))))

(defn unhook
  ([sys k] (swap! sys #(vary-meta % update-in [::observers] dissoc k)))
  ([sys path k] (if (empty? path)
                  (unhook sys k)
                  (swap! sys update-in path
                         #(vary-meta % update-in [::observers] dissoc k)))))

(defn unhook-all
  ([sys] (swap! sys #(vary-meta % assoc-in [::observers] {})))
  ([sys path] (if (empty? path)
                (unhook-all sys)
                (swap! sys update-in path
                       #(vary-meta % assoc-in [::observers] {})))))

(defn- -observe!
  ([sys f] (let [old-val (entity sys)
                 new-val (if (::observed? (meta old-val))
                           old-val
                           (do (swap! sys #(vary-meta % assoc ::observed? true))
                               (swap! sys f)))]
             (when-not (= old-val new-val)
               (let [observers (::observers (meta old-val))]
                 (doseq [[k f] observers] (f k [] old-val new-val))))
             (swap! sys #(vary-meta % assoc ::observed? false))
             new-val))
  ([sys path f] (if (empty? path)
                  (-observe! sys f)
                  (let [old-val (entity sys path) 
                        new-val (if (::observed? (meta old-val))
                                  old-val
                                  (do (swap! sys update-in path #(vary-meta % assoc ::observed? true))
                                      (get-in (swap! sys update-in path f) path)))]
                    (when-not (= old-val new-val)
                      (let [observers (::observers (meta old-val))]
                        (doseq [[k f] observers] (f k path old-val new-val))))
                    (swap! sys update-in path #(vary-meta % assoc ::observed? false))
                    new-val))))

(defn observe! 
  ([sys subpath f] (observe! sys [] subpath f))
  ([sys path subpath f]
   (if-not (observable? sys path)
     ::not-observable
     (if (nil? (get-in @sys (concat path subpath)))
       ::not-found
       (-observe! sys path #(update-in % subpath f))))))

(defn observes! 
  ([sys pairs] (observes! sys [] pairs))
  ([sys path pairs]
   (mapv (fn [[subpath f]] (observe! sys path subpath f)) pairs)))

(defn noop? [x] (or (= ::not-observable x) (= ::not-found x)))
(defn op? [x] (not (noop? x)))

(defn printer
  [observer path before after]
  (println observer ">>" path ":" before "->" after))

(def tree (atom {:a {:b 0} :c 0}))
(assert (not (observable? tree [:a])))
(observable tree [:a])
(assert (observable? tree [:a]))
(assert (not (observable? tree [:a :c])))
(meta (get-in @tree [:a]))
(hook tree [:a] ::printer printer)
(hook tree [:a] ::hoge (fn [& _] (println "hoge")))
(unhook tree [:a] ::hoge)
(assert (= {:b 1} (observe! tree [:a] [:b] inc)))
(assert (= ::not-observable (observe! tree [:c] [] inc)))
(unhook-all tree [:a])
(assert (op? (observe! tree [:a] [:b] inc)))
(hook tree [:a] ::printer printer)
(assert (= {:b 3} (observe! tree [:a] [:b] inc)))

(unobservable tree [:a])
(assert (= ::not-observable (observe! tree [:a] [:b] inc)))
(assert (nil? (::observers (entity tree [:a]))))
(observable tree [:a])
(hook tree [:a] ::printer printer)
(assert (= {:b 4}
           (observe! tree [:a] [:b] inc)
           (entity tree [:a])))
(assert (not (nil? (->> (entity tree [:a])
                        meta
                        ::observers))))
(println (str "tree" [:a] ".meta") (->> (entity tree [:a]) meta))

(swap! tree update-in [:a] dissoc :b)
(assert (= ::not-found (observe! tree [:a] [:b] inc)))
(swap! tree assoc-in [:a :b] 0)
(assert (observable? tree [:a]))         ;; メタデータは対象ではなく「位置」に基づいている!
(swap! tree update-in [:a] dissoc :b)
(reset! tree {:a {:b 0} :c 0})
(assert (nil? (meta (get-in @tree ["a"])))) ;; swap! ではメタデータは消えないが、reset! では消える
(observable tree [:a])
(hook tree [:a] ::printer printer)
(observe! tree [:a] [:b] inc)
(println (observes! tree [:a] {[:b] inc
                               [:c] inc}))
(println (observes! tree [:a] [[[:b] inc]
                               [[:b] inc]]))

(assert (= {:a {:b 4} :c 0} @tree))
(println "\ntree:" tree)

(reset! tree {:a {:b 0} :c {:d 0}})
(observable tree [:a])
(observable tree [:c])
(hook tree [:a] ::printer printer)
(hook tree [:c] ::printer printer)
(hook tree [:a] ::sync-add (fn [& _] (observe! tree [:c] [:d] inc)))
(hook tree [:c] ::sync-add (fn [& _] (observe! tree [:a] [:b] inc)))
(observe! tree [:a] [:b] inc)
(observe! tree [:c] [:d] inc)

(assert (and (= {:b 2} (entity tree [:a]))
             (= {:d 2} (entity tree [:c]))))

(unobservable tree [:c])
(observe! tree [:a] [:b] inc)
(assert (and (= {:b 3} (entity tree [:a]))
             (= {:d 2} (entity tree [:c]))))
(observable tree [:c])
(observe! tree [:a] [:b] inc)
(assert (and (= {:b 4} (entity tree [:a]))
             (= {:d 3} (entity tree [:c]))))
(swap! tree update-in [:c] dissoc :d)
(observe! tree [:a] [:b] inc)
(swap! tree update-in [:c] assoc :d (:b (entity tree [:a])))
(observe! tree [:a] [:b] inc)

(assert (and (= {:b 6} (entity tree [:a]))
             (= {:d 6} (entity tree [:c]))))
(println "\ntree:" tree)


(reset! tree {:a {:b 0} :c 0})
(assert (not (observable? tree [])))
(observable tree [])
(assert (observable? tree []))
(meta @tree)
(hook tree [] ::printer printer)
(hook tree [] ::hoge (fn [& _] (println "hoge")))
(unhook tree [] ::hoge)
(assert (= {:a {:b 1} :c 0} (observe! tree [:a :b] inc)))
(assert (= ::not-observable (observe! tree [:c] [] inc)))
(unhook-all tree [])
(assert (op? (observe! tree [:a :b] inc)))
(hook tree [] ::printer printer)
(assert (= {:a {:b 3} :c 0} (observe! tree [:a :b] inc)))

(unobservable tree [])
(assert (= ::not-observable (observe! tree [:a :b] inc)))
(assert (nil? (::observers (entity tree))))
(observable tree [])
(hook tree [] ::printer printer)
(assert (= {:a {:b 4} :c 0}
           (observe! tree [:a :b] inc)
           (entity tree)))
(assert (not (nil? (->> (entity tree)
                        meta
                        ::observers))))
(println "tree.meta" (->> (entity tree) meta))

(println (observes! tree {[:a :b] inc
                          [:c] inc}))
(println (observes! tree [[[:a :b] inc]
                          [[:a :b] inc]]))

(assert (= {:a {:b 7} :c 1} @tree))
(println "\ntree2:" tree)
