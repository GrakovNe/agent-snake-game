# Runbook: как продолжить, если сессия/тачка умерли

Обновлено: 2026-07-26, поздний вечер.

## Текущее состояние конвейера

- Бот: `episodes` (роллаутный поиск) и `neural` (ONNX-сеть вместо роллаутов) — паритет,
  сеть в ~3 раза быстрее. 30×30 target ~39-41%, 60×60 ~3-7% на 3599, медиана 3591.
- Датасеты (формат: `label w h cells...`): `data/planes-30-shard-a.txt` (20k, мак),
  `planes-30-shard-cloud1.txt` (161k, тачка → зеркалится на мак в `data/cloud-mirror/`).
- План размерной сетки — в RESEARCH.md (30 ✅, 60 в работе, дальше по transfer-метрике:
  45×45 → 30×60 → 72×72).

## Облачная тачка

- Instance `epdedarjpcu84qbub6gr` (snake-worker), 96 vCPU / 96 GB, прерываемая,
  зона ru-central1-b, IP меняется после stop/start.
- Бюджет-предохранитель: 10 000 ₽/мес, алерты 50/80/95% (`snake-research-guard`).
- **Автоматика уже стоит, руками ничего делать не надо**:
  - на тачке cron `vm-babysit.sh` (@reboot и */5): перезапускает harvest из `job.env`,
    пока нет `<OUT>.done`; harvest резюмируемый (чанки по 512, продолжает по числу строк);
  - на маке cron `mac-babysit.sh` (*/10): стартует вытесненную ВМ, зеркалит data/.
- Новый джоб: `ssh yc-user@<IP> 'agent-snake-game/deploy/launch-harvest.sh SIZE GAMES SEEDFROM ROLLOUTS OUT'`
  (сам пишет job.env и стартует; сторожа дальше держат).
- Погасить всё: `yc compute instance stop epdedarjpcu84qbub6gr` + убрать cron-строки
  (`crontab -e` на обеих машинах, маркеры vm-babysit / snake-babysit).

## Обучение и деплой сетки

```bash
train/.venv/bin/python train/train.py data/planes-30-*.txt data/planes-60-*.txt --epochs 30
train/.venv/bin/python train/export.py          # -> data/value-net.onnx (динамические оси)
./gradlew benchmark -Psize=30 -Pgames=200 -Pstrategy=neural
./gradlew benchmark -Psize=60 -Pgames=30 -Pstrategy=neural
```
Валидация печатается по размерам — это метрика transfer для решения по сетке размеров.

## Ближайшие шаги (если продолжаешь ты, будущая сессия)

1. Дождаться `planes-30-shard-cloud1.txt.done` → запустить 60×60:
   `launch-harvest.sh 60 600 600000 24 data/planes-60-shard-a.txt` (ночь).
2. Локально: совместное обучение → export → бенчи 30×30 (2×200) и 60×60 (30).
3. По transfer-цифрам — добор сетки размеров или глубина 60×60 (RESEARCH.md).
4. Сверх-цель прежняя: стабильные 3599+ на 60×60; сетка — эвал позиций в episodePlan,
   следующий рычаг — вторая итерация ExIt (сбор данных политикой с сеткой).
