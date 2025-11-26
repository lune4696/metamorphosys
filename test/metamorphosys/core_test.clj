(ns metamorphosys.core-test
  (:require [clojure.test :as test :refer [deftest]]
            [metamorphosys.core :as me]))


(deftest core
  (test/testing "all"
    (let [tree {:a {:b 0} :c 0}]
      (me/activate! :my/print me/printer)
      (me/register! [:tree] tree)
      (println @me/sys)
      (assert (not (me/observable? [:tree :a])))
      (me/observable [:tree :a])
      (assert (me/observable? [:tree :a]))
      (assert (not (me/observable? [:tree :a :c])))
      (me/hook [:tree :a] :my/printer [:my/print])
    
      (me/activate! ::huga (fn [v] (println "hoge") v))
      (me/hook [:tree :a] ::hoge [::huga])
      (me/unhook [:tree :a] ::hoge)
      (me/deactivate! ::hoge)
    
      (assert (= {:b 1} (me/observe! [:tree :a] [:b] inc)))
      (assert (= :memos/not-observable (me/observe! [:tree :c] [] inc)))
      (me/unhook-all [:tree :a])
      (assert (me/op? (me/observe! [:tree :a] [:b] inc)))
      (me/hook [:tree :a] :my/printer [:my/print])
      (assert (= {:b 3} (me/observe! [:tree :a] [:b] inc)))
    
      (me/unobservable [:tree :a])
      (assert (= :memos/not-observable (me/observe! [:tree :a] [:b] inc)))
      (assert (nil? (:memos/observers (me/entity [:tree :a]))))
      (me/observable [:tree :a])
      (me/hook [:tree :a] :my/printer [:my/print])
      (assert (= {:b 4}
                 (me/observe! [:tree :a] [:b] inc)
                 (me/entity [:tree :a])))
      (assert (not (nil? (->> (me/entity [:tree :a])
                              meta
                              :memos/observers))))
      (println (str "tree" [:a] ".meta") (->> (me/entity [:tree :a]) meta))
    
      (me/unregister! [:tree :a :b])
      (assert (= :memos/not-found (me/observe! [:tree :a] [:b] inc)))
      (me/register! [:tree :a :b] 0)
      (assert (me/observable? [:tree :a])) ;; メタデータは対象ではなく「位置」に基づいている!
    
      (me/unregister! [:tree :a :b])
    
      (reset! me/sys {:tree {:a {:b 0} :c 0}})
      (assert (nil? (meta (get-in @me/sys [:tree :a])))) ;; swap! ではメタデータは消えないが、reset! では消える (hard reset)
      (me/observable [:tree :a])
      (me/hook [:tree :a] :my/printer [:my/print])
      (me/observe! [:tree :a] [:b] inc)
      (println (me/observes! [:tree :a] {[:b] inc
                                      [:c] inc}))
      (println (me/observes! [:tree :a] [[[:b] inc]
                                      [[:b] inc]]))
    
      (assert (= {:a {:b 4} :c 0} (:tree @me/sys)))
      (println "\ntree:" (:tree @me/sys))
    
      (reset! me/sys {:tree {:a {:b 0} :c {:d 0}}})
      (me/observable [:tree :a])
      (me/observable [:tree :c])
      (me/hook [:tree :a] :my/printer [:my/print])
      (me/hook [:tree :c] :my/printer [:my/print])
      (me/activate! ::syncadd->c (fn [v] (me/observe! [:tree :c] [:d] inc) v))
      (me/activate! ::syncadd->a (fn [v] (me/observe! [:tree :a] [:b] inc) v))
      (me/hook [:tree :a] ::sync-add [::syncadd->c])
      (me/hook [:tree :c] ::sync-add [::syncadd->a])
      (me/observe! [:tree :a] [:b] inc)
      (me/observe! [:tree :c] [:d] inc)
    
      (assert (and (= {:b 2} (me/entity [:tree :a]))
                   (= {:d 2} (me/entity [:tree :c]))))
    
      (me/unobservable [:tree :c])
      (me/observe! [:tree :a] [:b] inc)
      (assert (and (= {:b 3} (me/entity [:tree :a]))
                   (= {:d 2} (me/entity [:tree :c]))))
      (me/observable [:tree :c])
      (me/observe! [:tree :a] [:b] inc)
      (assert (and (= {:b 4} (me/entity [:tree :a]))
                   (= {:d 3} (me/entity [:tree :c]))))
      (me/unregister! [:tree :c :d])
      (me/observe! [:tree :a] [:b] inc)
      (me/register! [:tree :c :d] (:b (me/entity [:tree :a])))
      (me/observe! [:tree :a] [:b] inc)
    
      (assert (and (= {:b 6} (me/entity [:tree :a]))
                   (= {:d 6} (me/entity [:tree :c]))))
      (println "\ntree:" (:tree @me/sys)))))
