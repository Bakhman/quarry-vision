# 📖 Chat Handbook

Чтобы работа над проектом `quarry-vision` была удобной, мы разделили контекст на три чата.  
Здесь описано назначение каждого из них.

---

## Чат №1 — CI/CD + Repo Management
Здесь мы работаем над:
- настройкой CI/CD (`ci.yaml`),
- правилами ветвления и protection (PR-only, pre-push hooks),
- документацией (`README.md`, `CONTRIBUTING.md`, `LICENSE`),
- чистотой репозитория и стандартами командной работы.

📌 Резюме:
- CI работает, зелёный билд обязателен.
- В `main` прямой push запрещён, только через PR.
- Документация ведётся и поддерживается.

---

## Чат №2 — Trello Sync
Здесь обсуждаем:
- подключение Trello API,
- GitHub Actions workflow `trello-sync.yaml`,
- синхронизацию задач с Trello.

📌 Резюме:
- Trello API подключён.
- Workflow работает (раз в день).
- Есть синхронизация доски → GitHub.

---

## Чат №3 — Dev Work / Code
Здесь идёт основная разработка:
- ingestion (USB → система),
- детекция (ковши, рейсы),
- OCR (Tess4J),
- PostgreSQL,
- UI (JavaFX/Spring),
- тесты и архитектура.

📌 Резюме:
- Архитектура проекта определена (модули core, ingest, detection, ocr, persistence, ui, config).
