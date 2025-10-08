# План Trello (источник истины для apply)

## 📥 Inbox
- [ ] Идеи/заметки сюда

## 📋 Backlog
- [ ] OCR (Tess4J) — smoke-тест
- [ ] Persistence (Postgres) — миграции + запись
- [ ] UI (JavaFX) — старт/стоп + лог
- [ ] E2E связка + краткое демо (GIF/скрин)
- [ ] Очередь обработки (QueueService) — очередь видео, ProgressBar, Cancel
- [ ] Фильтр и пагинация в Reports (по дате, merge_ms, пути)
- [ ] Экспорт событий одной детекции (CSV)
- [ ] UI-улучшения и авто-refresh
- [ ] OCR-модуль (Tesseract) — распознавание номеров машин
- [ ] Charts (Recharts/FXChart) — графики активности камер
- [ ] Preferences UI — редактирование `application.yaml` без перезапуска
- [ ] Отчёты по рейсам (truck trips) — загрузки/выгрузки
- [ ] UI-рефакторинг и темы (адаптивная ширина)

## 🚧 In Progress
- [ ] Detection (подсчёт ковшей) — заглушка + интерфейс

## 🔍 Review
- [ ] Ruleset OK

## ✅ Done

- [ ] Auto-merge OK
- [ ] cleanup-guide.md + зачистка старых веток
- [ ] Docs: `CONTRIBUTING.md`, `branch-naming.md`, `pr-checklist.md`
- [ ] Ruleset: запрет push, required check `build`
- [ ] CI Badge/README финализировать
- [ ] Auto-merge (squash) проверить на тех-PR
- [ ] PR-templates / Issue-templates просмотреть
- [ ] Ingest (USB) — базовый поток

## 🧰 Tech/CI
- [ ] `.gitignore`: исключить IDE/`target/`
- [ ] build (pull_request) зелёный
- [ ] Trello Plan Apply/Sync расписание/права
- [ ] Release workflow (GitHub Actions: build → artifact → release)
- [ ] Dockerfile + headless-режим (CLI-версия)
- [ ] Unit-тесты importer/queue
- [ ] Unit-тесты для `Pg`, `BucketDetector`
- [ ] Docs: базовый `user-guide.md`
- [ ] Docs: `developer-guide.md`, FAQ
- [ ] CodeQL / SpotBugs анализ
- [ ] Performance-tuning и профилирование

## 🗺 Epics
- [ ] Ingest → Detection → OCR → Persist → UI (end-to-end)
