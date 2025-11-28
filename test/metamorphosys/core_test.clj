(ns metamorphosys.core-test
  (:require [clojure.test :as test :refer [deftest is]]
            [metamorphosys.core :as me]))

(deftest basic
  (test/testing "all"
    (let [tree {:a {:b 0} :c 0}
          sys (me/system! {})]
      (me/add-action sys ::printer me/printer)
      (me/assoc-in! sys [:tree] tree)
      (me/hook sys [[:tree :a :b]] :print [::printer])

      (me/add-action sys ::hoge (fn [v] (println "hoge") v))
      (me/hook sys [[:tree :a]] ::foo [::hoge])
      (me/unhook sys [[:tree :a :b]] ::foo)
      (me/unhook sys [[:tree :a]] ::foo) ;; safely nil return
      (me/del-action sys ::hoge)

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/clear! sys)
      (is (= 1 (get-in @sys [:tree :a :b])))

      (is (some? (me/observe! sys [:tree :c] inc)))
      (me/clear! sys)
      (is (= 1 (get-in @sys [:tree :c])))

      (is (nil? (me/observe! sys [:tree :d] inc)))
      (me/clear! sys)

      (me/unhook-all sys [:tree :a :b])

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (is (= 2 (get-in @sys [:tree :a :b])))
      (is (nil? (me/observe! sys [:tree :a :b] inc)))
      (is (some? (me/observe! sys [:tree :c] inc)))
      (me/clear! sys)
      (is (= 2 (get-in @sys [:tree :a :b])))
      (is (= 2 (get-in @sys [:tree :c])))

      (me/hook sys [:tree :a :b] :print [::printer])

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/clear! sys)
      (is (= 3 (get-in @sys [:tree :a :b])))

      (me/dissoc-in! sys [:tree :a :b])

      (is (nil? (me/observe! sys [:tree :a :b] inc)))
      (me/clear! sys)
      (is (= nil (get-in @sys [:tree :a :b])))

      (me/assoc-in! sys [:tree :a :b] 0)

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/clear! sys)
      (is (= 1 (get-in @sys [:tree :a :b]))))))

(deftest reaction 
  (test/testing "all"
    (let [sys (me/system! {:tree {:a {:b 0} :c {:d 0} :e 0}})]
      (me/add-action sys ::printer me/printer)
      (me/add-action sys ::d<-b (fn [[d b]] (+ d b)))
      (me/add-action sys ::b<-d (fn [[b d]] (+ b d)))
      (me/add-action sys ::e<-b.d (fn [[_ b d]] (+ b d)))
      (me/hook sys [[:tree :a :b]] [:tree :c :d] [::printer ::d<-b])
      (me/hook sys [[:tree :c :d]] [:tree :a :b] [::printer ::b<-d])
      (me/hook sys
               (me/syspath [:tree :a :b] [:tree :c :d])
               [:tree :e] [::printer ::e<-b.d])
      (me/hook sys
               (me/syspath [:tree :c :d] [:tree :a :b])
               [:tree :e] [::printer ::e<-b.d ]) ;; syspath is unique (sorted) path

      (is (some? (me/observe! sys [:tree :a :b] inc)))
      (me/clear! sys)
      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/clear! sys)
      (is (= {:a {:b 3} :c {:d 2} :e 5}
             (get-in @sys [:tree])))
    
      (me/unhook sys [[:tree :c :d]] [:tree :a :b])
      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/clear! sys)
      (is (= {:a {:b 3} :c {:d 3} :e 5}
             (get-in @sys [:tree])))

      (me/hook sys [[:tree :c :d]] [:tree :a :b] [::printer ::b<-d])
      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/clear! sys)
      (is (= {:a {:b 7} :c {:d 4} :e 11}
             (get-in @sys [:tree])))

      (me/dissoc-in! sys [:tree :a :b])
      (me/assoc-in! sys [:tree :a :b] 0)
      (is (= {:a {:b 0} :c {:d 4} :e 11}
             (get-in @sys [:tree])))

      (is (some? (me/observe! sys [:tree :c :d] inc)))
      (me/clear! sys)
      (is (= {:a {:b 5} :c {:d 5} :e 10}
             (get-in @sys [:tree])))
      
      (println "\nsys:" @sys "meta:" (meta @sys)))))
