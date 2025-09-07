# QuarryVision — 2-недельный спринт (финализация)

## Эпик: Неделя 1 — Технический фундамент
**Цель:** стабилизировать инфраструктуру и CI/CD

### CI/CD и GitHub Actions
- [ ] Проверить стабильность Java CI (`.github/workflows/ci.yaml`) на всех PR
- [ ] Проверить ежедневный запуск `trello-sync.yaml`
- [ ] Добавить/проверить бейджи CI/Trello в `README.md`
- [ ] Повторно проверить локальный pre-push hook

### Документация
- [ ] Финализировать `CONTRIBUTING.md` (workflow + правила)
- [ ] `README.md`: разделы *build/run*, *CI/CD*, *Trello Sync*

### Repo hygiene
- [ ] `.gitignore`: исключить IDE/`target/`
- [ ] Issue templates: *feature* / *bug*
- [ ] Branch protection: PR-only, required check `build (pull_request)`, block force push

---

## Эпик: Неделя 2 — Функционал + финал
**Цель:** собрать MVP и упаковать проект

### Core-функционал
- [ ] Ingest: видео (USB → система)
- [ ] Detection: подсчёт ковшей
- [ ] OCR: Tess4J (базовые тесты)
- [ ] Persistence: PostgreSQL

### Интеграция
- [ ] E2E pipeline: ingest → detection → OCR → DB
- [ ] UI (JavaFX): старт/стоп, лог статуса

### Финализация
- [ ] LICENSE (MIT/Apache-2.0)
- [ ] `ROADMAP.md`: сделано / дальше
- [ ] Демо (GIF/скрины)
- [ ] «С нуля»: проверить сборку по инструкции
