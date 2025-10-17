# 🗺️ QuarryVision Roadmap

## 🔖 Overview
Цель проекта — создать автономную систему анализа видео с карьеров:
импорт → детекция → сохранение → отчёты и аналитика.  
Данный roadmap отражает план до стабильной версии **v1.1**.

---

## 🚀 v0.9 — Feature-Complete MVP
**Цель:** закончить пользовательские функции (импорт → детекция → отчёты).  
**Срок:** ~10 дней

| Приоритет | Задача                                                                                  | Статус |
|-----------|-----------------------------------------------------------------------------------------|--------|
| 🟠 P2     | [ ] Экспорт событий одной детекции (CSV)                                                | ☐      |
| 🟠 P2     | [ ] Unit-тесты для `Pg`, `BucketDetector`                                               | ☐      |
| 🟠 P2     | [ ] Docs: базовый `user-guide.md`                                                       | ☐      |
| 🟢 P3     | [ ] UI-улучшения и авто-refresh                                                         | ☐      |
| 🟢 P3     | [ ] Reports — фильтр, пагинация и экспорт CSV реализованы                               | ☐      |
| 🟢 P1     | [ ] Очередь обработки (QueueService) — очередь видео для детектора, ProgressBar, Cancel | ☐      |

---

## 🧠 v1.0 — Stable Release
**Цель:** стабильность, OCR, CI/CD, релизные артефакты.  
**Срок:** ~14 дней

| Приоритет | Задача                                                            | Статус |
|-----------|-------------------------------------------------------------------|--------|
| 🔴 P1     | [ ] OCR-модуль (Tesseract) — распознавание номеров машин          | ☐      |
| 🔴 P1     | [ ] Release workflow (GitHub Actions: build → artifact → release) | ☐      |
| 🟠 P2     | [ ] Dockerfile + headless-режим (CLI-версия)                      | ☐      |
| 🟠 P2     | [ ] Unit-тесты importer/queue                                     | ☐      |
| 🟠 P2     | [ ] Docs: `developer-guide.md`, FAQ                               | ☐      |
| 🟢 P3     | [ ] CodeQL / SpotBugs анализ                                      | ☐      |

---

## 📈 v1.1 — Analytics & Polish
**Цель:** аналитика, UX-улучшения, отчёты по рейсам.  
**Срок:** ~10 дней

| Приоритет | Задача                                                                 | Статус |
|-----------|------------------------------------------------------------------------|--------|
| 🔴 P1     | [ ] Charts (Recharts/FXChart) — графики активности камер               | ☐      |
| 🟠 P2     | [ ] Preferences UI — редактирование `application.yaml` без перезапуска | ☐      |
| 🟠 P2     | [ ] Отчёты по рейсам (truck trips) — загрузки/выгрузки                 | ☐      |
| 🟢 P3     | [ ] UI-рефакторинг и темы (адаптивная ширина)                          | ☐      |
| 🟢 P3     | [ ] Performance-tuning и профилирование                                | ☐      |


---

## 🧾 Summary

| Версия   | Цель                 | Срок   | Результат                |
|----------|----------------------|--------|--------------------------|
| **v0.9** | Feature-complete MVP | ~10 дн | весь цикл работы с видео |
| **v1.0** | Stable Release       | ~14 дн | OCR, CI/CD, релизы       |
| **v1.1** | Analytics & Polish   | ~10 дн | графики, отчёты, UX      |

---

## 📅 Progress tracking

| Компонент          | Ветка / PR                   | Статус | Комментарий                        |
|--------------------|------------------------------|--------|------------------------------------|
| QueueService       | `feat/queue`                 | ☐      | реализация очереди и UI-индикатора |
| Reports filtering  | `feat/reports-filter`        | ☐      | фильтры и пагинация                |
| Events export CSV  | `feat/reports-export-events` | ☐      | экспорт событий одной детекции     |
| OCR integration    | `feat/ocr-tesseract`         | ☐      | модуль OCR + сохранение в БД       |
| Docker + CLI       | `feat/docker-cli`            | ☐      | headless-режим                     |
| Release workflow   | `ci/release`                 | ☐      | сборка JAR + релиз GitHub          |
| Docs (user guide)  | `docs/user-guide`            | ☐      | пользовательская инструкция        |
| Docs (dev guide)   | `docs/dev-guide`             | ☐      | описание архитектуры               |
| Charts / analytics | `feat/analytics-charts`      | ☐      | визуализация данных                |
| Preferences UI     | `feat/ui-preferences`        | ☐      | редактирование параметров в UI     |

---

**Легенда:**
- 🔴 P1 — критично (минимальный функционал)
- 🟠 P2 — желательно (основное удобство)
- 🟢 P3 — улучшения и полировка
- ☐ — не выполнено ✅ — выполнено 🚧 — в работе

---

## ⚡ 7-Day Sprint — MVP Push (v0.9 Focus)

**Цель:** довести текущую ветку v0.9 до состояния боевого MVP, пригодного для демо.  
**Срок:** 5 календарных дней активной работы.

| День      | Цель                     | Основные задачи                                                                                    |
|-----------|--------------------------|----------------------------------------------------------------------------------------------------|
| **Day 1** | Queue Service            | Реализовать очередь видео для детектора: ядро, прогресс, Cancel; интеграция с UI.                  |
| **Day 2** | Cameras — стабильность   | Завершить авто-refresh и health-апдейты; корректная работа кнопок Start/Stop; улучшить UX.         |
| **Day 3** | Database & Persistence   | Расширить Pg-запросы (JOIN, фильтры, статистика); провести базовые тесты записи/чтения.            |
| **Day 4** | Полировка и устойчивость | Логирование, try/catch в воркерах, единый ExecutorService, throttling FPS.                         |
| **Day 5** | Финальная сборка и демо  | Проверка всех вкладок (Import → Detect → Save → Reports), подготовка demo build, user-guide draft. |

---

### 🧩 Результат

- Полностью рабочий MVP с вкладками:
    - **Import** — загрузка и сканирование USB;
    - **Detect** — вызов `BucketDetector`;
    - **Cameras** — управление и health-мониторинг;
    - **Reports** — просмотр детекций, фильтры, экспорт;
    - **Queue** — очередь на обработку.
- Логи и refresh работают без задержек.
- PostgreSQL и миграции `V1–V5` полностью в работе.
- Demo можно показать заказчику или использовать как пилот в полевых условиях.

---

### 🧠 После спринта

Следующие шаги продолжают roadmap:

| Версия   | Фокус            | Основные пункты                                                                 |
|----------|------------------|---------------------------------------------------------------------------------|
| **v1.0** | Стабильный релиз | OCR (Tesseract), Release workflow, Docker/CLI, CI/CD, Unit-тесты importer/queue |
| **v1.1** | Аналитика и UX   | Charts, Trips, Preferences UI, Performance tuning                               |

---

📌 **Примечание:**  
Этот 7-дневный спринт не заменяет стратегический roadmap (v0.9–v1.1),  
а служит его «боевым ускорителем» для выхода на демо-версию продукта.

## Актуальный roadmap по текущему приложению.

### Состояние

* ✅ Cameras: Edit-фикс, авто-refresh, Start/Stop, health.
* ✅ Детекция: EMA+FSM, EOF-сброс, NMS, tuned params (8 эвентов на `locs4.mp4`), `-Dqv.mergeMs`, trace (по YAML), освобождение `Mat`.
* ✅ Snapshot CLI: `tools/qv-snapshot-cli` (+ `--biasMs`), shaded JAR.
* ✅ Память/завершение: graceful shutdown (`Boot/MainController/Pg.close()`), daemon-пул, `javaw` запуск.
* ✅ Packaging: shade + `ServicesResourceTransformer`, portable ZIP (JRE+JAR+`run.bat`), внешний `config/application.yaml`.
* ✅ Reports: фильтры и экспорт CSV.
* ✅ .gitignore: `trace/`.

### Что добить до v0.9 (MVP демо)

* [ ] Docs: `docs/user-guide.md` (установка ZIP, `run.bat`, внешний YAML, пути INBOX/DB).
  Ветка: `docs/user-guide`
* [ ] Demo-релиз: собрать ZIP и выложить в GitHub Releases.
  Ветка: `ci/release-zip`

### v1.0 — Stable

* [ ] Release workflow: GitHub Actions (build → shaded JAR → ZIP + checksum → Release).
  Ветка: `ci/release`
* [ ] Unit-тесты: `BucketDetector` (фикстуры видео), `Pg` (in-memory/контур с локальной PG).
  Ветка: `tests/core`
* [ ] Dockerfile + headless режим (CLI запуск детектора без UI).
  Ветка: `feat/docker-cli`
* [ ] OCR (Tesseract) по желанию в 1.0 или сдвинуть в 1.1.
  Ветка: `feat/ocr-tesseract`
* [ ] Статанализ: SpotBugs/CodeQL базовый профиль.
  Ветка: `ci/static-analysis`

### v1.1 — Analytics & Polish

* [ ] Charts в UI: активность камер, частота эвентов.
  Ветка: `feat/analytics-charts`
* [ ] Preferences UI: правка ключевых detect/import/db параметров с сохранением во внешний YAML.
  Ветка: `feat/ui-preferences`
* [ ] Trip-отчёты (рейсы): агрегировать эвенты в «ковши на рейс».
  Ветка: `feat/reports-trips`
* [ ] Performance: профилирование, снижение аллокаций `Mat`, батч-запись в БД.
  Ветка: `perf/tuning`

### Детекция — план точечных улучшений (по мере надобности)

* [ ] ROI per-camera (маска зоны погрузки).
  Ветка: `feat/detect-roi`
* [ ] Сохранение параметров детекции в `detections` (audit).
  Ветка: `feat/detect-param-snapshot`
* [ ] Автокалибровка `thrLowFactor/emaAlpha` по первой минуте видео.
  Ветка: `feat/detect-autotune`

### Контрольные точки

* Tag `v0.9` после user-guide и релизного ZIP.
* Tag `v1.0` после CI-релизов и тестов.
* Tag `v1.1` после аналитики и preferences.

Нужен шаблон GitHub Release и action-yaml для сборки ZIP — скажи, сгенерирую.
