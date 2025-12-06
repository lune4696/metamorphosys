# Metamorphosys

Models **event-driven reactive system** in a tiny .cljc file, using clojure metadata.

**Why Metamorphosys?**
- Declarative observer ( in → reaction → out )
- Explicit cascade control (`observed!`/`recoverd!`)
- Universal path-based data access
- Built-in [malli][4] validation

## Installation

**After published to Clojars**
```clojure
{:deps {metamorphosys/core {:mvn/version "0.1.0"}}}
```

**For now**
```clojure
{:deps {io.github.lune4696/metamorphosys {:git/tag "v0.1.0" :git/sha "..."}}}
```

**Or clone locally:**
```bash
git clone https://github.com/lune4696/metamorphosys
```

## Concepts: 観測による状態の自動更新 (Observation triggered state auto-update)

In quantum mechanics, system observation automatically changes its state.
Metamorphosys is inspired from this to implement reactive system management.

| Library | Metaphor | Example |
|----------|----------|---------|
| **Re-frame** | [Water cycle][1]  | Dispatch → Handler → Update |
| **Nexus** | [nexus][2] | add-watch atom → compute between atom |
| **Metamorphosys** | [Quantum Measurement][3] | observe! system → reactions |

Metamorphosys helps:

- **Prototyping** : Tiny model (300 LOC), system is just a map atom w/ metadata.
- **Game Dev** : Scalable multiple inputs, flexible triggering.
- **Data management** : It's just a reactive system, so use with what you want.

All function input/output type is specified by using [malli][4], thus you can easily check whether your input is valid by enabling malli.instrument.

``` clojure
(require '[malli.clj-kondo :as mc]
(require '[malli.instrument :as mi]) 
(require '[malli.dev :as dev])
(require '[malli.dev.pretty :as pretty])
(mi/collect!)
(mi/instrument!)
(dev/start! {:report (pretty/reporter)})
```


### Core Principles

1. **Centralized State** : state is just a map atom (= `system`)
```clojure
(def sys (system {:window {:x 800 :y 450} 
                  :player {:hp 100 :stamina 20 :positions [20 -10 0]}
                  :walls {:positions [25 0 0]}}))
```

2. **Path-Based Access** : uniform addressing via index vector (= `path`)
```clojure
(get-in [:player :hp] @sys)
```

3. **Explicit Observation** : changes only via `observe!`
```clojure
(observe! sys [:player :hp] dec)  ; ✓ Triggers hooks
(swap! sys ...)           ; ✗ Silent, breaks reactivity
```

4. **Pure Reactions** : in (+ paths) --[reaction]--> out
```clojure
(hook 
  sys 
  [:player :positions] 
  [:can-run?] 
  [:has-stamina? :collision?]
  [[:player :stamina] [:walls :positions]])
```

## Comparison

### Quick Overview

| | Reagent | Re-frame | Nexus | Metamorphosys |
|---|---|---|---|---|
| **Type** | React wrapper | Full framework | Derived atoms | Reactive system |
| **State** | ratoms | Single DB | Multiple atoms | system (map atom) |

### vs Reagent

System in metamorphosys is conceptually inspired by [Reagent][8]'s atom and cursor, but key differences are...

- **Reagent**: Automatic reactivity via React rendering
- **Metamorphosys**: Explicit reactivity via `observe!` (just clojure data and function)

Metamorphosys can be seen as Reagent **disentangled from** hiccup-like 
markup and browser rendering, enabling use in non-UI contexts like 
game logic, simulations, or workflow engines.

### vs Re-frame

Basically, metamorphosys is just a reactive system model library.
Thus it's a little bit inaccurate to compare it against [Re-frame][6],
a feature-complete web framework. But here are some comparison...

**What metamorphosys focuses**
- ✅ Just (meta) data (you can use any data type as long as it's indexable)
- ✅ Use it with anything you want (logging, rendering, ...)
- ✅ Universal path indexing ([:character :foo :health :poison?])
- ✅ Flexible triggering (timing, input/output args)

**What metamorphosys NOT focuses**
- ❌ Built-in time-travel
- ❌ Strong connection with React 
- ❌ Strict purity

### vs Nexus

[Nexus][7] is a small, zero-dependency library for dispatching actions, 
it's idea and target is similar to metamorphosys.
But there're some differences between two...

**Nexus** : Value-change driven automatic computed atom:
```clojure
;; Define each state as an discrete atom

(def hp (atom 100))
(def dead? (nexus/compute [hp] (fn [[h s]] (<= h 0)))) 
;; dead? atom is automatic computation result => *data IS computation result*

(reset! hp -10)
@dead?  ;; => true (auto-updated)
```

**Metamorphosys** : Reactive system (= map atom):
```clojure
;; Define all state as a system (= map atom)
(let [sys (system {:hp 1 :stamina 10 :dead? false})] ;; initialize system 
  (add-action sys :die? (fn [[dead? hp]] (<= hp 0))) ;; add an action
  (hook sys [:hp] [:dead?] [:die?]) ;; hook observer :hp --[:die?]-> :dead?
  ;; [:dead?] path is just a data => *reaction UPDATES data*

  (observe! sys [:hp] dec) ;; observation fires observer and player died
  (println (get-in @sys [:dead?]))    ;; => true 
  (println (get-in @sys [:hp])) ;; => 0

  (observe! sys [:hp] inc) ;; NOT fired, because path is already observed
  (println (get-in @sys [:dead?]))    ;; => true
  (println (get-in @sys [:hp])) ;; => 0

  (recover sys) ;; system recovered

  (observe! sys [:hp] inc) ;; Fires and resurrect [:player] !
  (println (get-in @sys [:dead?])) ;; => false
  (println (get-in @sys [:hp]))) ;; => 1
```

Basically, nexus aims to provide automatic computation between atoms, while metamorphosys aims to provide reactive system (= map atom).

## Quick Start

```clojure
(require '[metamorphosys.core :as me])

;; 1. Create system
(def sys (me/system {:counter 0}))
(me/assoc-in! sys [:result] 0) ;; => {:counter 0, :result 0}

;; 2. Define action
(me/add-action sys :double (fn [[_ in]] (* 2 in))) ;; args: [out, in0, in1, ...]

;; 3. Hook observer
(me/hook sys [:counter] [:result] [:double]) ;; in, out, reaction [kw0, kw1, ...]

;; 4. Observe!
(me/observe! sys [:counter] inc) ;; => :success
(me/recover sys) ;; => {:counter 1, :result 2} (= @sys)
```

**Key points:**
- `system` defines system (= map atom)
- `assoc-in!` add any data to system at given path
- `add-action` add keyword-function pair to system
- `hook` add observer (in --[reaction]-> out) to make system reactive
- `observe!` triggers given input path's observer
- `recover` resets observation state (and return current system)
- Actions are functions which should be pure against system

## Advanced Usage

see [example](example) and [test](test) directory

### Game state management 

[Tetris](example/tetris.clj)

### Debugging (dependency graphs)

Coming soon...

## API Reference

See [core.cljc](src/metamorphosys/core.cljc) docstrings.

## Acknowledgements

Designed by [lune]()([@lune4696](https://github.com/lune4696)).

## Change Log

This change log basically follows [keepachangelog.com][5].

### 0.1.1 - 2025-12-07

Changed
- enable the observation output against not existing key
- changed observe! output from #{:success nil} to #{:success :nil :observed}

### 0.1.0 - 2025-12-01

Added
- Basic functionality to model reactive state

## License: MIT

Copyright © 2025 lune (lune4696). Distributed under the [MIT License](https://opensource.org/license/mit).

[1]:https://day8.github.io/re-frame/a-loop/
[2]:https://dictionary.cambridge.org/dictionary/english/nexus
[3]:https://en.wikipedia.org/wiki/Measurement_in_quantum_mechanics
[4]:https://github.com/metosin/malli
[5]:https://keepachangelog.com/
[6]:https://github.com/day8/re-frame
[7]:https://github.com/cjohansen/nexus
[8]:https://github.com/reagent-project/reagent
