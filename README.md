# Metamorphosys

Minimal event-driven reactive system library (cljc), using clojure metadata.

## Philosophy: 観測が状態を更新する (Observation updates state)

In quantum mechanics, observing a system changes its state. 
Metamorphosys applies this principle to software:

| Library | Metaphor | Example |
|----------|----------|---------|
| **Re-frame** | [Water cycle][1]  | Dispatch → Handler → Update |
| **Nexus** | [nexus][2] | add-watch atom → compute between atom (trigger: value change) |
| **Metamorphosys** | Quantum | observe! system → reactions (trigger: explicit observe!) |

### Core Principles

1. **Centralized State**: Single map atom (= `system`) is a source of truth
```clojure
   (def sys (system {:game {...} :ui {...}}))
```

2. **Path-Based Access**: Uniform addressing via index vector (= `path`)
```clojure
   (get-in [:player :hp] @sys)
```

3. **Explicit Observation**: Changes only via `observe!`
```clojure
   (observe! sys [:hp] dec)  ; ✓ Triggers hooks
   (swap! sys ...)           ; ✗ Silent, breaks reactivity
```

4. **Multi-Input Pure Reactions**: AND-semantics for derived state
```clojure
   (hook 
     sys 
     (to-paths [:player :hp] [:player :stamina] [:walls :positions]) 
     [:can-run?] 
     [:fine? :collision?])
   ;; Only fires when all [:player :hp] [:player :stamina] [:walls :positions] are observed
```

All function input/output type is specified by using [malli][4]

## Comparison

Metamorphosys is for:

- **Rapid prototyping** (minimal concepts, functions)
- **Game development** (native multiple inputs, double-call-free circular deps)
- **Learning** (LOC: 300 vs 3000)
- **Solo projects** (data is just a map atom)

### vs Re-frame?

**Trade-offs** Strictness vs Minimalism:
- ❌ No built-in time-travel
- ✅ Simpler mental model
- ✅ Universal path indexing
- ✅ Flexible triggering (timing, input/output args)

### vs Nexus

**Nexus** Automatic reaction between atom caused by value change:
```clojure
;; Define once
(def hp (atom 100))
(def dead? (nexus/compute hp #(< % 0)))

;; Updates propagate automatically
(reset! hp -10)
@dead?  ;; => true (auto-updated!)
```

**Metamorphosys** Tunable reaction controlled by observe!/recover!:
```clojure
;; Define reactions
(let [sys (system {:player {:hp 100 :dead? false}})]
  (add-action sys ::check-death (fn [dead? hp] (<= hp 0)))
  ;; add action ::check-death to system
  (hook sys [[:player :hp]] [:player :dead?] [::check-death]) 
  ;; hook observer :hp --[::check-death]-> :dead?
  (observe! sys [:hp] identity) ;; hooks fire
  (recover! sys)) ;; system recovered
```

**Key differences:**

| Aspect | Nexus | Metamorphosys |
|--------|-------|---------------|
| Updates | Automatic (on swap!) | Explicit (on observe!) |
| State | Multiple atoms | Single system |
| Best for | UI reactivity | Game loops, batch updates |

## Installation
```clojure
{:deps {metamorphosys/core {:mvn/version "0.1.0"}}}
```

## Quick Start

## Advanced Usage

see test/metamorphosys/*.cljc

### Game state management 
### Debugging (dependency graphs)

## API Reference

See [core.clj](src/metamorphosys/core.clj) docstrings.

## Acknowledments

Designed by [lune4696](https://github.com/lune4696)

## Change Log
This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

### [0.1.1] - 2025-12-xx

Changed

Removed

Fixed

### 0.1.0 - 2025-11-29

Added
- Basic functionality below.

## License: MIT

Copyright © 2025 Christian Johansen, Magnar Sveen, and Teodor Heggelund. Distributed under the [MIT License][5].

## Literatures

[1]:https://day8.github.io/re-frame/a-loop/
[2]:https://dictionary.cambridge.org/dictionary/english/nexus
[3]:https://en.wikipedia.org/wiki/Measurement_in_quantum_mechanics
[4]:https://github.com/metosin/malli
[4]:https://opensource.org/license/mit
