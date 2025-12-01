(ns metamorphosys.core-test
  (:require [clojure.test :as test :refer [deftest is]]
            [metamorphosys.core :as me]
            [malli.clj-kondo :as mc]
            [malli.instrument :as mi]
            [malli.dev :as dev]
            [malli.dev.pretty :as pretty]))

(deftest basic
  (test/testing "all"

    (mi/collect!)
    (mi/instrument!)
    (dev/start! {:report (pretty/reporter)})

    (let [tree {:a {:b 0} :c 0}
          sys (me/system {})]
      (me/add-action sys ::printer me/printer)
      (me/assoc-in! sys [:tree] tree)
      (me/hook sys [:tree :a :b] :print [::printer])

      (me/add-action sys ::hoge (fn [v] (println "hoge") v))
      (me/hook sys [:tree :a] ::foo [::hoge])
      (me/unhook sys [:tree :a :b] ::foo)
      (me/unhook sys [:tree :a] ::foo) ;; safely nil return
      (me/del-action sys ::hoge)

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/recover! sys)
      (is (= 1 (get-in @sys [:tree :a :b])))

      (is (some? (me/observe! sys [:tree :c] inc)))
      (me/recover! sys)
      (is (= 1 (get-in @sys [:tree :c])))

      (is (nil? (me/observe! sys [:tree :d] inc)))
      (me/recover! sys)

      (me/unhook sys [:tree :a :b])

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (is (= 2 (get-in @sys [:tree :a :b])))
      (is (nil? (me/observe! sys [:tree :a :b] inc)))
      (is (some? (me/observe! sys [:tree :c] inc)))
      (me/recover! sys)
      (is (= 2 (get-in @sys [:tree :a :b])))
      (is (= 2 (get-in @sys [:tree :c])))

      (me/hook sys [:tree :a :b] :print [::printer])

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/recover! sys)
      (is (= 3 (get-in @sys [:tree :a :b])))

      (me/dissoc-in! sys [:tree :a :b])

      (is (nil? (me/observe! sys [:tree :a :b] inc)))
      (me/recover! sys)
      (is (= nil (get-in @sys [:tree :a :b])))

      (me/assoc-in! sys [:tree :a :b] 0)

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/recover! sys)
      (is (= 1 (get-in @sys [:tree :a :b]))))))

(deftest reaction 
  (test/testing "all"

    (mi/collect!)
    (mi/instrument!)
    (dev/start! {:report (pretty/reporter)})

    (let [sys (me/system {:tree {:a {:b 0} :c {:d 0} :e 0}})]
      (me/add-action sys ::printer me/printer)
      (me/add-action sys ::d<-b (fn [[d b]] (+ d b)))
      (me/add-action sys ::b<-d (fn [[b d]] (+ b d)))
      (me/add-action sys ::e<-b.d (fn [[_ b d]] (+ b d)))
      (me/hook sys [:tree :a :b] [:tree :c :d] [::printer ::d<-b])
      (me/hook sys [:tree :c :d] [:tree :a :b] [::printer ::b<-d])
      (me/hook sys [:tree :a :b] [:tree :e] [::printer ::e<-b.d] [[:tree :c :d]])

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/recover! sys)
      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/recover! sys)
      (is (= {:a {:b 3} :c {:d 2} :e 5}
             (get-in @sys [:tree])))
    
      (me/unhook sys [:tree :c :d] [:tree :a :b])
      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/recover! sys)
      (is (= {:a {:b 3} :c {:d 3} :e 5}
             (get-in @sys [:tree])))

      (me/hook sys [:tree :c :d] [:tree :a :b] [::printer ::b<-d])
      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/recover! sys)
      (is (= {:a {:b 7} :c {:d 4} :e 11}
             (get-in @sys [:tree])))

      (me/dissoc-in! sys [:tree :a :b])
      (me/assoc-in! sys [:tree :a :b] 0)
      (is (= {:a {:b 0} :c {:d 4} :e 11}
             (get-in @sys [:tree])))

      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/recover! sys)
      (is (= {:a {:b 5} :c {:d 5} :e 10}
             (get-in @sys [:tree])))
      
      (println "\nsys:" @sys "meta:" (meta @sys)))))

(defmulti  rock-scissor-paper
  (fn [a b] [a b]))

(defmethod  rock-scissor-paper [:rock :rock]
  [_ _] :draw)

(defmethod  rock-scissor-paper [:scissor :scissor]
  [_ _] :draw)

(defmethod  rock-scissor-paper [:paper :paper]
  [_ _] :draw)

(defmethod  rock-scissor-paper [:rock :scissor]
  [_ _] :win)

(defmethod  rock-scissor-paper [:rock :paper]
  [_ _] :lose)

(defmethod  rock-scissor-paper [:scissor :rock]
  [_ _] :lose)

(defmethod  rock-scissor-paper [:scissor :paper]
  [_ _] :win)

(defmethod  rock-scissor-paper [:paper :rock]
  [_ _] :win)

(defmethod  rock-scissor-paper [:paper :scissor]
  [_ _] :lose)

(deftest jan-ken
  (test/testing "all"
    (mi/collect!)
    (mi/instrument!)
    (dev/start! {:report (pretty/reporter)})

    (let [sys (me/system {:result :win :player {:taro {:hand :rock} :cpu {:hand :paper}}})]
      (me/add-action sys :1-2-3!
                     (fn [[_ a b]] (rock-scissor-paper a b)))
      (me/add-action sys :printer me/printer)
      (me/hook sys
               [:player :taro :hand]
               [:result]
               [:printer :1-2-3!]
               [[:player :cpu :hand]])
      (me/observe! sys [:player :taro :hand] (fn [_] :rock))
      (is (= :lose (get-in @sys [:result])))
      (me/recover! sys)

      (me/observe! sys [:player :taro :hand] (fn [_] :scissor))
      (is (= :win (get-in @sys [:result])))
      (me/recover! sys)

      (me/observe! sys [:player :taro :hand] (fn [_] :paper))
      (is (= :draw (get-in @sys [:result])))
      (me/recover! sys)

      (me/observe! sys [:player :cpu :hand] (fn [_] :scissor))

      (me/observe! sys [:player :taro :hand] (fn [_] :rock))
      (is (= :win (get-in @sys [:result])))
      (me/recover! sys)

      (me/observe! sys [:player :taro :hand] (fn [_] :scissor))
      (is (= :draw (get-in @sys [:result])))
      (me/recover! sys)

      (me/observe! sys [:player :taro :hand] (fn [_] :paper))
      (is (= :lose (get-in @sys [:result])))
      (me/recover! sys)
      )))

(mc/collect *ns*)
(mc/emit!)
