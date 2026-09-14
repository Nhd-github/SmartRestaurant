<div align="center">

<img src="https://img.shields.io/badge/Jason-3.3.0-4A90D9?style=for-the-badge&logo=java&logoColor=white"/>
<img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white"/>
<img src="https://img.shields.io/badge/Agents-10-6C3483?style=for-the-badge"/>
<img src="https://img.shields.io/badge/BDI-Re--planning-2ECC71?style=for-the-badge"/>

# 🍽️ Smart Restaurant MAS

**A multi-agent system for restaurant coordination built with Jason / AgentSpeak + Java**

*10 autonomous agents · Contract Net Protocol · atomic resource booking · priority-based preemption · BDI re-planning*

---

### 📽️ Demo

[![Watch the demo](https://img.shields.io/badge/▶%20Watch%20Demo-FF0000?style=for-the-badge&logo=youtube&logoColor=white)](YOUR_VIDEO_LINK_HERE)

> Replace `YOUR_VIDEO_LINK_HERE` with your YouTube / GitHub Release link.

---

</div>

## What happens when you run it

Three customers arrive at different times with different priorities. Two waiters bid for orders, two chefs negotiate for cooking tasks, and a single tomato triggers the main coordination scenario:

1. **customer1** orders pasta (priority 4) → chef2 books the tomato
2. **customer2** orders pizza (priority 9) → chef1 also needs the tomato
3. Priority gap satisfies preemption rule (9 ≥ 4 + 3) → pasta booking is displaced
4. chef2 re-plans, asks inventoryManager for an alternative, proposes **risotto** to the customer, and only cooks after explicit approval
5. **customer3** arrives while both tables are dirty → queues at the entrance until a waiter finishes cleaning

---

## Agents

| Agent | Instances | Role |
|---|:---:|---|
| `customer` | 3 | Arrive, order food, request supplies, evaluate alternatives, pay |
| `restaurantManager` | 1 | Table lifecycle, adaptive Contract Net for waiters, reputation tracking |
| `waiter` | 2 | Bid for orders, run chef Contract Net, deliver food and supplies, clean tables |
| `chef` | 2 | Bid for cooking tasks, atomically book recipe resources, re-plan after preemption |
| `inventoryManager` | 1 | Check ingredient stock, propose alternatives, manage reusable supplies |
| `cashier` | 1 | Calculate bills, handle payment, release tables |

---

## Key mechanisms

<details>
<summary><b>📋 Adaptive Contract Net (two levels)</b></summary>

The manager sends a CFP to both waiters; each waiter bids using distance, failure history, completed tasks, and reputation. The selected waiter then runs a second CFP with the two chefs, where each chef bids using expertise, success/failure counts, and reputation.

**Waiter bid formula:**
```
Score = D + 6F - C + (100 - R) / 10
```
`D` = distance to table · `F` = failed tasks · `C` = completed tasks · `R` = reputation · lower wins · busy waiter bids 999

**Chef bid formula:**
```
Score = (5 - E) × 10 + 8F - 2S + (100 - R) / 5
```
`E` = dish expertise · `F` = dish failures · `S` = dish successes · `R` = reputation · lower wins

</details>

<details>
<summary><b>🔒 Atomic recipe booking</b></summary>

`reserveRecipe()` in `RestaurantModel` is `synchronized` — it checks the complete resource set before committing anything. A reservation is either **BOOKED** or **COOKING**. Once cooking starts, ingredients are consumed and equipment stays locked until the dish is done.

</details>

<details>
<summary><b>⚡ Controlled preemption</b></summary>

A BOOKED reservation can be displaced by a higher-priority order only when the priority gap ≥ `PREEMPTION_MARGIN` (set to 3). COOKING reservations are protected. The displaced chef receives a `reservationPreempted` percept and begins BDI recovery immediately.

</details>

<details>
<summary><b>🔄 BDI re-planning</b></summary>

When a chef loses its booking, it asks inventoryManager for a feasible alternative. The alternative is proposed to the customer through the waiter; cooking only continues after **explicit customer approval**. If the customer rejects it, the task is cancelled cleanly.

</details>

<details>
<summary><b>📦 Stale supply cancellation</b></summary>

If a customer leaves while a waiter is still fetching a supply item, the item is returned to storage rather than delivered to an empty table. Reusable cutlery is recovered during table cleaning and goes back into stock.

</details>

---

## Project structure

```
smartRestaurant.mas2j        ← MAS entry point (10 agents declared here)
RestaurantEnv.java           ← Jason ↔ Java bridge (executeAction, percepts)
RestaurantModel.java         ← Shared state, A* path finding, resource locking
RestaurantView.java          ← Grid GUI with live coordination dashboard
│
src/asl/
├── customer1.asl            ← Normal-priority customer (pasta, water)
├── customer2.asl            ← Urgent customer (pizza, napkins) — triggers preemption
├── customer3.asl            ← Late customer (pizza → soup, cutlery)
├── restaurantManager.asl    ← Table lifecycle, CFP coordination, reputation
├── waiter1.asl              ← Adaptive waiter — closer to table 1
├── waiter2.asl              ← Adaptive waiter — closer to table 2
├── chef1.asl                ← Pasta expertise 2, pizza expertise 5
├── chef2.asl                ← Pasta expertise 4, pizza expertise 2
├── inventoryManager.asl     ← Stock checks, alternative mapping, supply lifecycle
└── cashier.asl              ← Billing and payment
│
tools/
└── validate_project.py      ← Structural checks — run before submitting
```

---

## How to run

**Prerequisites:** Java 21+, Gradle (wrapper included)

```bash
# Clone
git clone <repo-url>
cd smart11

# Run (Gradle pulls Jason 3.3.0 automatically)
./gradlew run          # Linux / macOS
gradlew.bat run        # Windows
```

Or with the Jason CLI directly:

```bash
jason smartRestaurant.mas2j
```

The GUI opens automatically. The MAS console shows every CFP, bid, booking event, preemption, and re-plan in real time.

**Validate before running:**

```bash
python3 tools/validate_project.py
# Expected: VALIDATION PASSED
```

---

## Recorded run timeline

| Timestamp | Event |
|:---:|---|
| `0:00` | System starts · 10 agents initialise |
| `0:08` | customer1 seated at table1 · orders pasta (priority 4) |
| `0:12` | waiter1 wins CFP · chef2 selected for pasta |
| `0:15` | chef2 books tomato + stove + prep_counter → **BOOKED** |
| `0:18` | customer2 seated at table2 · orders pizza (priority 9) |
| `0:19` | chef1 needs tomato · 9 ≥ 4+3 → **preemption triggered** |
| `0:19` | chef2 re-plans pasta → risotto · customer1 accepts |
| `0:32` | pizza served to customer2 · payment complete · table2 → DIRTY |
| `0:38` | waiter cleans table2 · customer3 seated from queue |
| `0:51` | customer3 pizza unavailable (tomato+cheese depleted) · soup accepted |
| `1:10` | soup + cutlery served to customer3 |
| `1:20` | Both tables FREE · `preemptions=1` · `replans=1` · `failures=0` ✅ |

---

## Course context

Built for the **Multi-Agent Systems** course  
MSc in Computer Science — Data Science & AI  
University of Genova · September 2026
