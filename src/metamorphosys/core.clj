(ns metamorphosys.core
  (:require [clojure.walk :refer [postwalk]]))

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
;;     - このライブラリの目的は状態変化によって状態変化をトリガする (= metamorphosis) なので
;; 2. declarative にしたい、或いはデータから自動的に observers をインポートないしデータにエクスポートしたい
;;   - してみた、まあこっちのほうが分かりやすい
;;     ただ、重要な点として system 外のデータとのシンクロをどうするかが問題になる
;;     - とはいっても、システム外の対象がどんな形でデータを持っているか (atom かもしれないし、 interop 先の型かもしれない)
;;       はわからないので、それは observer の fn の担当にしたほうが良いか
 
(def sys (atom {}))
(def reacts (atom {}))

(defn register! [path data] (swap! sys assoc-in path data))
(defn unregister! [path] (swap! sys update-in (butlast path) dissoc (last path)))
(defn activate! [kw f] (swap! reacts assoc kw f))
(defn deactivate! [kw] (swap! reacts dissoc kw))

(defn observable
  [path] (if (empty? path)
           (swap! sys #(with-meta % {:memos/observers {} :memos/observed? false}))
           (swap! sys update-in path #(with-meta % {:memos/observers {} :memos/observed? false}))))

(defn unobservable
  [path] (if (empty? path)
           (swap! sys #(vary-meta % dissoc :memos/observers :memos/observed?))
           (swap! sys update-in path #(vary-meta % dissoc :memos/observers :memos/observed?))))

(defn observable?
  [path] (let [m (if (empty? path) (meta @sys) (meta (get-in @sys path)))]
           (not (or (nil? (:memos/observers m))
                    (nil? (:memos/observed? m))))))

(defn entity
  [path] (get-in @sys path))

(defn hook
  [path k fs] (if (empty? path)
                 (swap! sys #(vary-meta % assoc-in [:memos/observers k] fs))
                 (swap! sys update-in path #(vary-meta % assoc-in [:memos/observers k] fs))))

(defn unhook
  [path k] (if (empty? path)
              (swap! sys #(vary-meta % update-in [:memos/observers] dissoc k))
              (swap! sys update-in path #(vary-meta % update-in [:memos/observers] dissoc k))))

(defn unhook-all
  [path] (if (empty? path)
           (swap! sys #(vary-meta % assoc-in [:memos/observers] {}))
           (swap! sys update-in path
                  #(vary-meta % assoc-in [:memos/observers] {}))))

(defn compose [ini fns] (reduce (fn [ans f] ((@reacts f) ans)) ini fns))

(defn- -observe! 
  [path f] (let [old-val (entity path) 
                 new-val (if (:memos/observed? (meta old-val))
                           old-val
                           (if (empty? path)
                             (do (swap! sys #(vary-meta % assoc :memos/observed? true))
                                 (swap! sys f))
                             (do (swap! sys update-in path #(vary-meta % assoc :memos/observed? true))
                                 (swap! sys update-in path f)
                                 (entity path))))]
             (when-not (= old-val new-val)
               (let [observers (:memos/observers (meta old-val))]
                 (mapv (fn [[k fs]] (compose [k path old-val new-val] fs)) observers)))
             (if (empty? path)
               (swap! sys #(vary-meta % assoc :memos/observed? false))
               (swap! sys update-in path #(vary-meta % assoc :memos/observed? false)))
             new-val))

(defn observe! 
  [path subpath f] (if-not (observable? path)
                     :memos/not-observable
                     (if (nil? (get-in @sys (concat path subpath)))
                       :memos/not-found
                       (-observe! path #(update-in % subpath f)))))

(defn observes! 
  [path pairs] (mapv (fn [[subpath f]] (observe! path subpath f)) pairs))

(defn noop? [x] (or (= :memos/not-observable x) (= :memos/not-found x)))



(defn op? [x] (not (noop? x)))

(defn printer
  [[observer path before after]]
  (println observer ">>" path ":" before "->" after)
  [observer path before after])
