# 🗺 QuarryVision — 2-недельный спринт (Trello Plan)

## 📥 Списки
1. 📥 **Inbox** — входящие идеи/заметки
2. 📋 **Backlog** — очередь задач
3. 🚧 **In Progress** — в работе
4. 🔍 **Review** — код-ревью / проверка
5. ✅ **Done — Sprint 2025-W38–W39** — готово (срок спринта)
6. 🧰 **Tech/CI** — техдолг, инфраструктура
7. 🗺 **Epics** — крупные блоки (карточки-маяки с чеклистами)

---

## 🗺 Epics

### Epic: Week 1 — CI/CD & Hygiene
- [ ] Ruleset OK
- [ ] Auto-merge OK
- [ ] PR templates
- [ ] Docs обновлены

### Epic: Week 2 — MVP Pipeline
- [ ] Ingest → Detection → OCR → Persist → UI (end-to-end)

---

## 📋 Week 1 — задачи

- [ ] CI Badge/README финализировать
- [ ] Ruleset: запрет push, required check `build`
- [ ] Auto-merge (squash) проверить на тех-PR
- [ ] Docs: `CONTRIBUTING.md`, `branch-naming.md`, `pr-checklist.md`
- [ ] `cleanup-guide.md` + зачистка старых веток

---

## 📋 Week 2 — задачи

- [ ] Ingest (USB) — базовый поток
- [ ] Detection (подсчёт ковшей) — заглушка + интерфейсы
- [ ] OCR (Tess4J) — smoke-тест
- [ ] Persistence (Postgres) — миграции + запись
- [ ] UI (JavaFX) — старт/стоп + лог
- [ ] E2E связка + краткое демо (GIF/скрины)  
