(ns metamorphosys.core-test
  (:require [clojure.test :as test :refer [deftest is]]
            [metamorphosys.core :as me]))


(deftest core
  (test/testing "all"
    (let [tree {:a {:b 0} :c 0}]
      (me/activate! ::printer me/printer)
      (me/register! [:tree] tree)
      (println @me/sys)
      (is (not (me/observable? [:tree :a])))
      (me/observable [:tree :a])
      (is (me/observable? [:tree :a]))
      (is (not (me/observable? [:tree :a :c])))
      (me/hook [:tree :a] ::print [::printer])
    
      (me/activate! ::hoge (fn [v] (println "hoge") v))
      (me/hook [:tree :a] ::foo [::hoge])
      (me/unhook [:tree :a] ::foo)
      (me/deactivate! ::foo)
    
      (is (= {:b 1} (me/observe! [:tree :a] [:b] inc)))
      (is (= :memos/not-observable (me/observe! [:tree :c] inc)))
      (me/unhook-all [:tree :a])
      (is (me/op? (me/observe! [:tree :a] [:b] inc)))
      (me/hook [:tree :a] ::print [::printer])
      (is (= {:b 3} (me/observe! [:tree :a] [:b] inc)))
    
      (me/unobservable [:tree :a])
      (is (= :memos/not-observable (me/observe! [:tree :a] [:b] inc)))
      (is (nil? (:memos/observers (me/entity [:tree :a]))))
      (me/observable [:tree :a])
      (me/hook [:tree :a] ::print [::printer])
      (is (= {:b 4}
             (me/observe! [:tree :a] [:b] inc)
             (me/entity [:tree :a])))
      (is (some? (:memos/observers (meta (me/entity [:tree :a])))))
      (println (str "tree" [:a] ".meta") (->> (me/entity [:tree :a]) meta))
    
      (me/unregister! [:tree :a :b])
      (is (= :memos/not-found (me/observe! [:tree :a] [:b] inc)))
      (me/register! [:tree :a :b] 0)
      (is (me/observable? [:tree :a])) ;; メタデータは対象ではなく「位置」に基づいている!
    
      (me/unregister! [:tree :a :b])
    
      (reset! me/sys {:tree {:a {:b 0} :c 0}})
      (is (nil? (meta (get-in @me/sys [:tree :a])))) ;; swap! ではメタデータは消えないが、reset! では消える (hard reset)
      (me/observable [:tree :a])
      (me/hook [:tree :a] ::print [::printer])
      (me/observe! [:tree :a] [:b] inc)
      (println (me/observes! [:tree :a] {[:b] inc
                                         [:c] inc}))
      (println (me/observes! [:tree :a] [[[:b] inc]
                                         [[:b] inc]]))
    
      (is (= {:a {:b 4} :c 0} (:tree @me/sys)))
      (println "\ntree:" (:tree @me/sys))
    
      (reset! me/sys {:tree {:a {:b 0} :c {:d 0}}})
      (me/observable [:tree :a])
      (me/observable [:tree :c])
      (me/hook [:tree :a] ::print [::printer])
      (me/hook [:tree :c] ::print [::printer])
      (me/activate! ::a->c
                    (fn [[to _ prev curr]]
                      (let [c (me/entity to)
                            d (c :d)]
                        (if (nil? d)
                          c
                          {:d (+ d (- (:b curr) (:b prev)))}))))
      (me/activate! ::c->a
                    (fn [[to _ prev curr]]
                      (let [a (me/entity to)
                            b (a :b)]
                        (if (nil? b)
                          a
                          {:b (+ (:b (me/entity to))
                                 (- (:d curr) (:d prev)))}))))
      (me/hook [:tree :a] [:tree :c] [::a->c])
      (me/hook [:tree :c] [:tree :a] [::c->a])

      (me/observe! [:tree :c] [:d] inc)

      (me/observe! [:tree :a] [:b] inc)

      (is (and (= {:b 2} (me/entity [:tree :a]))
               (= {:d 2} (me/entity [:tree :c]))))
    
      (me/unobservable [:tree :c])
      (me/observe! [:tree :a] [:b] inc)
      (is (and (= {:b 3} (me/entity [:tree :a]))
               (= {:d 2} (me/entity [:tree :c]))))
      (me/observable [:tree :c])
      (me/observe! [:tree :a] [:b] inc)
      (is (and (= {:b 4} (me/entity [:tree :a]))
               (= {:d 3} (me/entity [:tree :c]))))
      (me/unregister! [:tree :c :d])
      (me/observe! [:tree :a] [:b] inc)
      (me/register! [:tree :c :d] (:b (me/entity [:tree :a])))
      (me/observe! [:tree :a] [:b] inc)
    
      (println (me/entity [:tree :a]))
      (println (me/entity [:tree :c]))
      (is (and (= {:b 6} (me/entity [:tree :a]))
               (= {:d 6} (me/entity [:tree :c]))))
      (println "\ntree:" (:tree @me/sys)))))
