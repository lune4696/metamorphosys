(ns metamorphosys.core)

;; メタデータの制約によりコレクションしかオブザーバブル化できない = オブザーバーを付与できないのだが、
;; clojure における型付きのデータ = collection なので問題ない
;; また、メタデータは値でなくシンボル(ないし位置)に紐づいている
;; よって、値を swap! しても system atom が reset! されない限り observable meta は消去されない
;; あくまで observe! によってのみ observer はトリガされる
;; よって、外部から swap! による「観測不可能な干渉」を行う場合、値が変わっても observer はトリガされない
;; 値が変わった後に observe! した時の値がいきなり変わったり、 noop になったりするだけである
;; もちろん、基本的に好ましくはないが、それでシステムが破綻することはない
 
(def sys (atom {}))
(def reactions (atom {}))

(defn register! [path data] (swap! sys assoc-in path data))
(defn unregister! [path] (swap! sys update-in (butlast path) dissoc (last path)))
(defn activate! [kw f] (swap! reactions assoc kw f))
(defn deactivate! [kw] (swap! reactions dissoc kw))

(defn observable
  [path] (if (empty? path)
           (swap! sys #(with-meta % {::observers {} ::observed? false}))
           (swap! sys update-in path #(with-meta % {::observers {} ::observed? false}))))

(defn unobservable
  [path] (if (empty? path)
           (swap! sys #(vary-meta % dissoc ::observers ::observed?))
           (swap! sys update-in path #(vary-meta % dissoc ::observers ::observed?))))

(defn observable?
  [path] (let [m (if (empty? path) (meta @sys) (meta (get-in @sys path)))]
           (not (or (nil? (::observers m))
                    (nil? (::observed? m))))))

(defn entity
  [path] (get-in @sys path))

(defn hook
  [path k fs] (if (empty? path)
                 (swap! sys #(vary-meta % assoc-in [::observers k] fs))
                 (swap! sys update-in path #(vary-meta % assoc-in [::observers k] fs))))

(defn unhook
  [path k] (if (empty? path)
              (swap! sys #(vary-meta % update-in [::observers] dissoc k))
              (swap! sys update-in path #(vary-meta % update-in [::observers] dissoc k))))

(defn unhook-all
  [path] (if (empty? path)
           (swap! sys #(vary-meta % assoc-in [::observers] {}))
           (swap! sys update-in path
                  #(vary-meta % assoc-in [::observers] {}))))

(defn- -observe! 
  [path f] (let [old-val (entity path) 
                 new-val (if (::observed? (meta old-val))
                           old-val
                           (if (empty? path)
                             (do (swap! sys #(vary-meta % assoc ::observed? true))
                                 (swap! sys f))
                             (do (swap! sys update-in path #(vary-meta % assoc ::observed? true))
                                 (swap! sys update-in path f)
                                 (entity path))))]
             (when-not (= old-val new-val)
               (let [observers (::observers (meta old-val))]
                 (doseq [[k fs] observers]
                   (doseq [f fs]
                     ((@reactions f) k path old-val new-val)))))
             (if (empty? path)
               (swap! sys #(vary-meta % assoc ::observed? false))
               (swap! sys update-in path #(vary-meta % assoc ::observed? false)))
             new-val))

(defn observe! 
  [path subpath f] (if-not (observable? path)
                     ::not-observable
                     (if (nil? (get-in @sys (concat path subpath)))
                       ::not-found
                       (-observe! path #(update-in % subpath f)))))

(defn observes! 
  [path pairs] (mapv (fn [[subpath f]] (observe! path subpath f)) pairs))

(defn noop? [x] (or (= ::not-observable x) (= ::not-found x)))



(defn op? [x] (not (noop? x)))

(defn printer
  [observer path before after]
  (println observer ">>" path ":" before "->" after))

(activate! ::print printer)

(def tree {:a {:b 0} :c 0})
(register! [:tree] tree)
(println @sys)
(assert (not (observable? [:tree :a])))
(observable [:tree :a])
(assert (observable? [:tree :a]))
(assert (not (observable? [:tree :a :c])))
(meta (get-in @sys [:tree :a]))
(hook [:tree :a] ::printer [::print])

(activate! ::huga (fn [& _] (println "hoge")))
(hook [:tree :a] ::hoge [::huga])
(unhook [:tree :a] ::hoge)
(deactivate! ::hoge)

(assert (= {:b 1} (observe! [:tree :a] [:b] inc)))
(assert (= ::not-observable (observe! [:tree :c] [] inc)))
(unhook-all [:tree :a])
(assert (op? (observe! [:tree :a] [:b] inc)))
(hook [:tree :a] ::printer [::print])
(assert (= {:b 3} (observe! [:tree :a] [:b] inc)))

(unobservable [:tree :a])
(assert (= ::not-observable (observe! [:tree :a] [:b] inc)))
(assert (nil? (::observers (entity [:tree :a]))))
(observable [:tree :a])
(hook [:tree :a] ::printer [::print])
(assert (= {:b 4}
           (observe! [:tree :a] [:b] inc)
           (entity [:tree :a])))
(assert (not (nil? (->> (entity [:tree :a])
                        meta
                        ::observers))))
(println (str "tree" [:a] ".meta") (->> (entity [:tree :a]) meta))

(unregister! [:tree :a :b])
(assert (= ::not-found (observe! [:tree :a] [:b] inc)))
(register! [:tree :a :b] 0)
(assert (observable? [:tree :a]))         ;; メタデータは対象ではなく「位置」に基づいている!

(unregister! [:tree :a :b])

(reset! sys {:tree {:a {:b 0} :c 0}})
(assert (nil? (meta (get-in @sys [:tree :a])))) ;; swap! ではメタデータは消えないが、reset! では消える (hard reset)
(observable [:tree :a])
(hook [:tree :a] ::printer [::print])
(observe! [:tree :a] [:b] inc)
(println (observes! [:tree :a] {[:b] inc
                                [:c] inc}))
(println (observes! [:tree :a] [[[:b] inc]
                                [[:b] inc]]))

(assert (= {:a {:b 4} :c 0} (:tree @sys)))
(println "\ntree:" (:tree @sys))

(reset! sys {:tree {:a {:b 0} :c {:d 0}}})
(observable [:tree :a])
(observable [:tree :c])
(hook [:tree :a] ::printer [::print])
(hook [:tree :c] ::printer [::print])
(activate! ::syncadd->c (fn [& _] (observe! [:tree :c] [:d] inc)))
(activate! ::syncadd->a (fn [& _] (observe! [:tree :a] [:b] inc)))
(hook [:tree :a] ::sync-add [::syncadd->c])
(hook [:tree :c] ::sync-add [::syncadd->a])
(observe! [:tree :a] [:b] inc)
(observe! [:tree :c] [:d] inc)

(assert (and (= {:b 2} (entity [:tree :a]))
             (= {:d 2} (entity [:tree :c]))))

(unobservable [:tree :c])
(observe! [:tree :a] [:b] inc)
(assert (and (= {:b 3} (entity [:tree :a]))
             (= {:d 2} (entity [:tree :c]))))
(observable [:tree :c])
(observe! [:tree :a] [:b] inc)
(assert (and (= {:b 4} (entity [:tree :a]))
             (= {:d 3} (entity [:tree :c]))))
(unregister! [:tree :c :d])
(observe! [:tree :a] [:b] inc)
(register! [:tree :c :d] (:b (entity [:tree :a])))
(observe! [:tree :a] [:b] inc)

(assert (and (= {:b 6} (entity [:tree :a]))
             (= {:d 6} (entity [:tree :c]))))
(println "\ntree:" (:tree @sys))

;; やっぱ clojure 凄い、sys を排除する設計に簡単に切り替えられる...!
;; 
(defn hiccup [f ini coll] (reduce (fn [acc nxt] (if (vector? nxt) (hiccup f acc nxt) (f acc nxt))) ini coll))

;; (reset! sys {:tree {:a {:b 0} :c 0}})
;; (assert (not (observable? [:tree])))
;; (observable [:tree])
;; (assert (observable? [:tree]))
;; (meta @sys)
;; (hook [:tree] ::printer printer)
;; (hook [:tree] ::hoge (fn [& _] (println "hoge")))
;; (unhook [:tree] ::hoge)
;; (assert (= {:a {:b 1} :c 0} (observe! [:tree :a :b] inc)))
;; (assert (= ::not-observable (observe! [:tree :c] [] inc)))
;; (unhook-all tree [])
;; (assert (op? (observe! tree [:a :b] inc)))
;; (hook tree [] ::printer printer)
;; (assert (= {:a {:b 3} :c 0} (observe! tree [:a :b] inc)))

;; (unobservable tree [])
;; (assert (= ::not-observable (observe! tree [:a :b] inc)))
;; (assert (nil? (::observers (entity tree))))
;; (observable tree [])
;; (hook tree [] ::printer printer)
;; (assert (= {:a {:b 4} :c 0}
;;            (observe! tree [:a :b] inc)
;;            (entity tree)))
;; (assert (not (nil? (->> (entity tree)
;;                         meta
;;                         ::observers))))
;; (println "tree.meta" (->> (entity tree) meta))

;; (println (observes! tree {[:a :b] inc
;;                           [:c] inc}))
;; (println (observes! tree [[[:a :b] inc]
;;                           [[:a :b] inc]]))

;; (assert (= {:a {:b 7} :c 1} @tree))
;; (println "\ntree2:" tree)

;; NOTE:
;; - ミドルウェア適用、ないし observers を連鎖で仕込むには?
;;   -  observers を hiccup-like keyword vector に変えて、
;;      (hook tree [...] [::inc ::mul [::printer [::trigger-some [::func-some]]]]])
;;      のように、適用する関数チェインを記述する
;;      簡単のために、引数と返り値は [observer path before after] に固定する
;;      関数は defonce した reactions (map atom) に assoc していけば良いんじゃないか
;; - declarative にしたい、或いはデータから自動的に observers をインポートないしデータにエクスポートしたい
;;   - 現状の observer は中にグローバルの変数があっても良いとしているのでこの部分が不透明
;;     なので、基本的に作用に必要な変数を変数に渡したいが、そうすると関数の柔軟性が失われる
;;     現状 observable を特定のデータ構造内に束縛せずに自由に定義しているのをやめて、
;;     'metasystem' という単一の map atom に移すことで、 system を固定してしまいパスのみで変数を表現すればどうか
;;     或いは、そこまでしないとしても system は 'systems' (map atom, pre-defined) に登録して、そこからインデクシングするとか
;;     いややっていること同じだけど
;; NOTE:
;; - declarative にしたい、或いはデータから自動的に observers をインポートないしデータにエクスポートしたい
;;   - してみた、まあこっちのほうが分かりやすい
;;     ただ、重要な点として system 外のデータとのシンクロをどうするかが問題になる
;;     - とはいっても、システム外の対象がどんな形でデータを持っているか (atom かもしれないし、 interop 先の型かもしれない)
;;       はわからないので、それは observer の担当にしたほうが良いかもしれない
