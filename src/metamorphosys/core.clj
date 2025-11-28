(ns metamorphosys.core
  (:require [clojure.set :refer [union subset?]]))

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
 
(defn system! [m] (atom (with-meta m {::action {}
                                      ::observer {}
                                      ::observed #{}
                                      ::reacted {}})))

(defn action [sys] (-> @sys meta ::action))
(defn act [sys tag] ((-> sys action) tag))

(defn assoc-in! [sys path data] (swap! sys assoc-in path data))
(defn dissoc-in! [sys path] (swap! sys update-in (butlast path) dissoc (last path)))
(defn add-action [sys name action] (swap! sys vary-meta assoc-in [::action name] action))
(defn del-action [sys name] (swap! sys vary-meta update-in [::action] dissoc name))

(defn hook [sys in out reacts] (swap! sys vary-meta assoc-in [::observer in out] reacts))

(defn unhook [sys in out] (swap! sys vary-meta update-in [::observer in] dissoc out))
(defn unhook-all [sys in] (swap! sys vary-meta assoc-in [::observer in] {}))

(defn- observed [sys paths] (swap! sys vary-meta update ::observed #(union % (set paths))))
(defn observed? [sys paths] (subset? (set paths) (-> @sys meta ::observed)))

(defn- reacted [sys paths] (swap! sys vary-meta assoc-in [::reacted paths] nil))
(defn reacted? [sys paths] (contains? (-> @sys meta ::reacted) paths))

(defn syspath [& args] (vec (sort args)))

(defn- fns? [coll] (reduce (fn [acc in] (and acc (fn? in))) coll))

(defn- -observe! 
  [sys in]
  (when-let [effects ((-> @sys meta ::observer) in)]
    (when (and (observed? sys in) 
               (not (reacted? sys in)))
      (reacted sys in)
      (let [argv (mapv #(get-in @sys %) in)]
        (mapv (fn [[out reacts]]
                (let [arg (if (vector? out)
                            (get-in @sys out)
                            out)
                      acts (mapv #(act sys %) reacts)]
                  (when (and (some? arg)
                             (fns? acts)
                             (not (observed? sys [out])))
                    (if (vector? out)
                      (do
                        (observed sys [out])
                        (swap! sys update-in out #(concat [%] argv))
                        (swap! sys update-in out (apply comp (reverse acts))))
                      ((apply comp (reverse acts)) (concat [arg] argv))))))
              effects))
      (mapv #(-observe! sys %) (-> @sys meta ::observer keys)))))

(defn observe! 
  [sys path f]
   (when (and (some? (get-in @sys path))
              (not (observed? sys [path]))
              (not (reacted? sys [path])))
     (observed sys [path])
     (swap! sys update-in path f)
     (-observe! sys [path])
     true))

(defn clear! [sys]
  (swap! sys vary-meta assoc ::observed #{})
  (swap! sys vary-meta assoc ::reacted {}))

(defn printer
  [[out & args :as all]]
  (println out (if (vector? out) "<<" "::") args)
  all)
