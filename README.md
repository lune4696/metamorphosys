# Metamorphosys

Models **event-driven reactive system** in a tiny .cljc file, using clojure metadata.

## Installation
```clojure
{:deps {metamorphosys/core {:mvn/version "0.1.0"}}}
```

## Philosophy: 観測が状態を更新する (Observation updates state)

In quantum mechanics, system observation changes its state. 
Metamorphosys applies this principle to system management:

| Library | Metaphor | Example |
|----------|----------|---------|
| **Re-frame** | [Water cycle][1]  | Dispatch → Handler → Update |
| **Nexus** | [nexus][2] | add-watch atom → compute between atom |
| **Metamorphosys** | [Quantum Measurement][3] | observe! system → reactions |

Metamorphosys is:

- **Just (Meta) Data** : System is map atom, its info is metadata of the system.
- **For Rapid Prototyping** : Minimal concepts, functions (LOC: 300 (logic: ~100!)).
- **For FOSS Game Dev** : Native multiple inputs, flexible triggering.
- **For Something New** : Just models reactive system, no premise and dependency.

All function input/output type is specified by using [malli][4]

### Core Principles

1. **Centralized State**: State is just a map atom (= `system`)
```clojure
   (def sys (system {:window {:x 800 :y 450} 
                     :player {:hp 100 :stamina 20 :positions [20 -10 0]}
                     :walls {:positions [25 0 0]}}))
```

2. **Path-Based Access**: Uniform addressing via index vector (= `path`)
```clojure
   (get-in [:player :hp] @sys)
```

3. **Explicit Observation**: Changes only via `observe!`
```clojure
   (observe! sys [:player :hp] dec)  ; ✓ Triggers hooks
   (swap! sys ...)           ; ✗ Silent, breaks reactivity
```

4. **Multi-Input Pure Reactions**: AND-semantics for derived state
```clojure
   (hook 
     sys 
     (to-paths [:player :positions] [:player :stamina] [:walls :positions]) 
     [:can-run?] 
     [:has-stamina? :collision?])
   ;; Fires when all :player/:positions,:stamina :walls/:positions are observed
```

## Comparison

### vs Re-frame?

Basically, metamorphosys is just a reactive system model.
Thus it's a little bit inaccurate to compare it against [Re-frame][6],
but here are some comparison...

**Strictness vs Minimalism**
- ❌ No built-in time-travel
- ✅ Simpler mental model
- ✅ Universal path indexing
- ✅ Flexible triggering (timing, input/output args)

### vs Nexus

[Nexus][7] is a small, zero-dependency library for dispatching actions, 
it's purpose is similar to metamorphosys.
But there're some differences between two...

**Nexus** : Automatic reaction between atom caused by value change:
```clojure
;; Define a part of the state as an atom
(def hp (atom 100))
(def dead? (nexus/compute hp #(< % 0)))

;; Reaction is automatic computation between atoms
(reset! hp -10)
@dead?  ;; => true (auto-updated!)
```

**Metamorphosys** : Tunable reaction controlled by observe!/recover!:
```clojure
;; Define system
(let [sys (system {:window {:x 800 :y 450}})] ;; initialize system 
  (assoc-in! sys [:player] {:hp 1 :dead? false}) ;; add state as you like it
  @sys ;; => {:window {:x 800 :y 450}, :player {:hp 1 :dead? false}}

  (add-action sys ::check-death (fn [dead? hp] (<= hp 0))) ;; add an action
  (hook sys 
        [[:player :hp]] 
        [:player :dead?] 
        [::check-death]) ;; hook state observer :hp --[::check-death]-> :dead?

  (observe! sys [:hp] dec) ;; observation fires above observer and compute
  (get-in @sys [:player :dead?]) ;; => true 
  (get-in @sys [:player :hp]) ;; => 0

  (observe! sys [:hp] inc) ;; NOT fired, because path is already observed
  (get-in @sys [:player :dead?]) ;; => true
  (get-in @sys [:player :hp]) ;; => 0

  (recover! sys)) ;; system recovered

  (observe! sys [:hp] inc) ;; Fires and resurrect [:player] !
  (get-in @sys [:player :dead?]) ;; => false
  (get-in @sys [:player :hp]) ;; => 1
```

**Key differences:**

| Aspect | Nexus | Metamorphosys |
|--------|-------|---------------|
| Updates | Automatic (on swap!) | Explicit (on observe!) |
| State | Multiple atoms | Single system |
| Best for | reactive UI | Game state |

## Quick Start

## Advanced Usage

see [example](example) and [test](test) directory

### Game state management 

[Tetris](example/tetris.clj)

### Debugging (dependency graphs)

Comming soon...

## API Reference

See [core.cljc](src/metamorphosys/core.cljc) docstrings.

## Acknowledments

Designed by [lune]([@lune4696](https://github.com/lune4696)).

## Change Log

This change log basically follows [keepachangelog.com][5].

### 0.1.0 - 2025-11-29

Added
- Basic functionality to model reactive state

## License: MIT

Copyright © 2025 Christian Johansen, Magnar Sveen, and Teodor Heggelund. Distributed under the [MIT License](https://opensource.org/license/mit).

[1]:https://day8.github.io/re-frame/a-loop/
[2]:https://dictionary.cambridge.org/dictionary/english/nexus
[3]:https://en.wikipedia.org/wiki/Measurement_in_quantum_mechanics
[4]:https://github.com/metosin/malli
[5]:https://keepachangelog.com/
[6]:https://github.com/day8/re-frame
[7]:https://github.com/cjohansen/nexus
