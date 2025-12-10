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
          sys (me/->sys {})]

      (me/assoc-in! sys [:tree] tree)

      (me/hook sys (fn [& args] (println args)) [[:tree :a :b]] [:print])
      (me/unhook sys [:tree :a] [:foo]) ;; safely nil return

      (is (= :success (me/observe! sys [:tree :a :b] inc)))
      (is (= 1 (get-in @sys [:tree :a :b])))

      (is (= :success (me/trigger! sys [:tree :c] inc)))
      (me/reload sys)
      (is (= 1 (get-in @sys [:tree :c])))

      (is (= :nil (me/trigger! sys [:tree :d] inc)))
      (me/reload sys)


      (is (= :success (me/trigger! sys [:tree :a :b] inc)))
      (is (= 2 (get-in @sys [:tree :a :b])))
      (is (= :observed (me/trigger! sys [:tree :a :b] inc)))
      (is (= :success (me/trigger! sys [:tree :c] inc)))
      (me/reload sys)
      (is (= 2 (get-in @sys [:tree :a :b])))
      (is (= 2 (get-in @sys [:tree :c])))

      (me/hook sys (fn [& args] (println args)) [[:tree :a :b]] [:print])

      (is (= :success (me/observe! sys [:tree :a :b] inc)))
      (is (= 3 (get-in @sys [:tree :a :b])))

      (me/dissoc-in! sys [:tree :a :b])

      (is (= :nil (me/observe! sys [:tree :a :b] inc)))
      (is (= nil (get-in @sys [:tree :a :b])))

      (me/assoc-in! sys [:tree :a :b] 0)

      (is (= :success (me/observe! sys [:tree :a :b] inc)))
      (is (= 1 (get-in @sys [:tree :a :b]))))))

(deftest reaction 
  (test/testing "all"

    (mi/collect!)
    (mi/instrument!)
    (dev/start! {:report (pretty/reporter)})

    (let [sys (me/->sys {:tree {:a {:b 0} :c {:d 0}}})] 
      (me/hook sys (fn [d] d) [[:tree :c :d]] [:tree :a :b])
      (me/hook sys (fn [b] b) [[:tree :a :b]] [:tree :c :d])
      (me/hook sys (fn [b d] (+ b d)) [[:tree :a :b] [:tree :c :d]] [:tree :e])

      (is (= :success (me/observe! sys [:tree :a :b] inc)))
      (is (= :success (me/observe! sys [:tree :c :d] inc)))
      (is (= {:a {:b 2} :c {:d 2} :e 4} (get-in @sys [:tree])))
    
      (me/unhook sys [:tree :c :d] [:tree :a :b])
      (is (= :success (me/observe! sys [:tree :c :d] inc)))
      (is (= {:a {:b 2} :c {:d 3} :e 5} (get-in @sys [:tree])))

      (me/hook sys (fn [d] d) [[:tree :c :d]] [:tree :a :b])
      ;; (me/hook sys [:tree :c :d] [:tree :a :b] [::printer ::b<-d])
      (is (= :success (me/observe! sys [:tree :c :d] inc)))
      (is (= {:a {:b 4} :c {:d 4} :e 8} (get-in @sys [:tree])))

      (me/dissoc-in! sys [:tree :a :b])
      (me/assoc-in! sys [:tree :a :b] 0)
      (is (= {:a {:b 0} :c {:d 4} :e 8} (get-in @sys [:tree])))

      (is (= :success (me/observe! sys [:tree :c :d] inc)))
      (is (= {:a {:b 5} :c {:d 5} :e 10} (get-in @sys [:tree])))
      
      (println "\nsys:" @sys "meta:" (meta @sys)))))

(mc/collect *ns*)
(mc/emit!)
