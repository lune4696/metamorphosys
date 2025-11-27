(ns metamorphosys.core)

;; メタデータの制約によりコレクションしかオブザーバブル化できない = オブザーバーを付与できないのだが、
;; clojure における型付きのデータ = collection なので問題ない
;; また、メタデータは値でなくシンボル(ないし位置)に紐づいている
;; よって、値を swap! しても system atom が reset! されない限り observable meta は消去されない
;; あくまで observe! によってのみ observer はトリガされる
;; よって、外部から swap! による「観測不可能な干渉」を行う場合、値が変わっても observer はトリガされない
;; 値が変わった後に observe! した時の値がいきなり変わったり、 noop になったりするだけである
;; もちろん、基本的に好ましくはないが、それでシステムが破綻することはない

;; やっぱ clojure は素晴らしい、設計を簡単に切り替えられる...!

;; NOTE:
;; 1. ミドルウェア適用、ないし observers を連鎖で仕込むには?
;;   - observers を hiccup-like keyword vector に変えて、
;;     (hook tree [...] [::inc ::mul [::printer [::trigger-some [::func-some]]]]])
;;     のように、適用する関数チェインを記述する
;;     簡単のために、引数と返り値は [observer path before after] に固定する
;;     関数は defonce した reactions (map atom) に assoc していけば良いんじゃないか
;; 2. declarative にしたい、或いはデータから自動的に observers をインポートないしデータにエクスポートしたい
;;   - 現状の observer は中にグローバルの変数があっても良いとしているのでこの部分が不透明
;;     なので、基本的に作用に必要な変数を変数に渡したいが、そうすると関数の柔軟性が失われる
;;     現状 observable を特定のデータ構造内に束縛せずに自由に定義しているのをやめて、
;;     'metasystem' という単一の map atom に移すことで、 system を固定してしまいパスのみで変数を表現すればどうか
;;     或いは、そこまでしないとしても system は 'systems' (map atom, pre-defined) に登録して、そこからインデクシングするとか
;;     いややっていること同じだけど
;; NOTE:
;; 1. ミドルウェア適用、ないし observers を連鎖で仕込むには?
;;   - 簡単のために kw vector はネストせず、 observers は {:any/kw [:fn0 :fn1 ...]} 形式にした
;;     実用上困ることはないと思う
;;   - observer は半ば意図的に副作用を認めている
;;     - このライブラリの目的は状態変化によって状態変化をトリガすることによって系の変化をモデリングすること (= metamorphosis) なので
;;     - とはいえ、純粋関数、内部変更関数 (= observe!) 、外部副作用関数 (println, DB access, ...) は分けても良いかもしれない
;; 2. declarative にしたい、或いはデータから自動的に observers をインポートないしデータにエクスポートしたい
;;   - してみた、まあこっちのほうが分かりやすい
;;     ただ、重要な点として system 外のデータとのシンクロをどうするかが問題になる
;;     - とはいっても、システム外の対象がどんな形でデータを持っているか (atom かもしれないし、 interop 先の型かもしれない)
;;       はわからないので、それは observer の fn の担当にしたほうが良いか
;; 3. system の持ち方これでいいの
;;   - 正直わからないけど、変更そんなに大変じゃないから良いんじゃないか
 
(def sys (atom {}))
(def reacts (atom {}))

(defn register! [path data] (swap! sys assoc-in path data))
(defn unregister! [path] (swap! sys update-in (butlast path) dissoc (last path)))
(defn activate! [kw f] (swap! reacts assoc kw f))
(defn deactivate! [kw] (swap! reacts dissoc kw))

(defn observable
  [path] (swap! sys update-in path #(with-meta % {:memos/observers {} :memos/observed? false})))

(defn unobservable
  [path] (swap! sys update-in path #(vary-meta % dissoc :memos/observers :memos/observed?)))

(defn observable?
  [path] (let [m (meta (get-in @sys path))]
           (not (or (nil? (:memos/observers m))
                    (nil? (:memos/observed? m))))))

(defn entity
  [path] (get-in @sys path))

(defn observed? [path]  (:memos/observed? (meta (entity path))))

(defn hook
  [path to reactions]
  (swap! sys update-in path #(vary-meta % assoc-in [:memos/observers to] reactions)))

(defn unhook
  [path to]
  (swap! sys update-in path #(vary-meta % update-in [:memos/observers] dissoc to)))

(defn unhook-all
  [path] (swap! sys update-in path #(vary-meta % assoc-in [:memos/observers] {})))

(defn chain [ini fns] (reduce (fn [ans f] ((@reacts f) ans)) ini fns))

(defn- make-observed [entt]
  (vary-meta entt assoc :memos/observed? true))

(defn- -observe! 
  [path f] (let [prev (entity path) 
                 curr (if (:memos/observed? (meta prev))
                        prev 
                        (do (swap! sys update-in path make-observed)
                            (swap! sys update-in path f)
                            (entity path)))]
             (when-not (= prev curr)
               (let [observers (:memos/observers (meta prev))]
                 (->> observers
                      (mapv (fn [[to fs]] 
                              (cond
                                (not (vector? to)) (chain [to path prev curr] fs)
                                (and
                                 (observable? to)
                                 (not (observed? to))) (->> (chain [to path prev curr] fs)
                                 (#(-observe!
                                    to (fn [_] (with-meta % (meta (entity to)))))))
                                :else nil)))))) 
             (swap! sys update-in path #(vary-meta % assoc :memos/observed? false))
             curr))

(defn observe! 
  ([path f]
   (cond
     (not (observable? path)) :memos/not-observable
     (nil? (entity path)) :memos/not-found
     :else (-observe! path f)))
  ([path subpath f]
   (if (nil? (entity (concat path subpath)))
     :memos/not-found
     (observe! path #(update-in % subpath f)))))

(defn observes! 
  [path fs] (mapv (fn [[subpath f]] (observe! path subpath f)) fs))

(defn noop? [x] (or (= :memos/not-observable x) (= :memos/not-found x)))



(defn op? [x] (not (noop? x)))

(defn printer
  [[to from prev curr]]
  (println to ">>" from ":" prev "->" curr)
  [to from prev curr])
