# agent-snake-game

Чистый, детерминированный движок змейки для экспериментов с ботами. Переписан с нуля
по мотивам [grakovne/snake](https://github.com/GrakovNe/snake), UI сохраняет стиль оригинала.

## Запуск

```bash
./gradlew show       -Psize=40 -Pdelay=20 -Pstrategy=greedy -Pseed=42   # шоу с UI, бесконечные раунды
./gradlew benchmark  -Psize=30 -Pgames=200 -Pseed=42 -Pstrategy=greedy  # headless, статистика + throughput
./gradlew arena      -Psize=30 -Pgames=100 -Pseed=42                    # параллельный турнир стратегий
```

Все параметры опциональны. Стратегии: `greedy` (BFS к еде + fallback на выживание), `random`.

## Архитектура

- `core` — движок. `SnakeGame` — единственный источник правды (тело змейки + occupancy-грид),
  `GameView` — read-only интерфейс, который видит стратегия. Один seed = одна и та же игра.
  Терминальные состояния: `DEAD` (`HIT_WALL` / `HIT_SELF` / `STARVED`) и `WON` (поле заполнено).
  Ход в клетку хвоста легален — хвост освобождает её в тот же тик.
- `strategy` — `fun interface Strategy { fun nextMove(game: GameView): Direction }`.
  Стратегии могут быть stateful, поэтому на каждую игру создаётся свой инстанс.
- `sim` — `GameRunner.play` (одна игра, опциональный `onStep`-хук для UI) и `Arena` —
  параллельный прогон многих игр на корутинах. `Arena.tournament` оценивает всех кандидатов
  на одном и том же наборе seed'ов (common random numbers) — это каркас для генетики/ML:
  кандидатом может быть вектор весов, а не имя стратегии.
- `ui` — Swing-рендер в стиле оригинала: 10px клетки, серая рамка, чёрная змейка с красной
  головой, magenta-еда, счёт и график длины на JFreeChart.
- `app` — точки входа `Show`, `Benchmark`, `ArenaMain`.

## Ориентиры производительности

Greedy-бейзлайн на 30×30: ~150 средняя длина, ~190k шагов/сек суммарно на 10 потоках —
запаса хватает на генетические популяции в сотни особей.
