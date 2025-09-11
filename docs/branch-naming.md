# 🌿 Branch Naming Guide

Единые правила именования веток в `quarry-vision`.

## 📌 Формат

### ` - <type>/<short-description>`


- `<type>` — совпадает с Conventional Commits.
- `<short-description>` — кратко, латиницей, через `-`.

---

## 🔖 Допустимые типы

| Тип      | Когда использовать                              | Примеры веток                  |
|----------|-------------------------------------------------|--------------------------------|
| `feat/`  | Новая функциональность                          | `feat/usb-ingest`, `feat/ocr`  |
| `fix/`   | Исправление бага                                | `fix/nullpointer-ingest`       |
| `docs/`  | Только документация                             | `docs/pr-checklist-update`     |
| `chore/` | Рутинные задачи (зависимости, конфиги и пр.)    | `chore/update-maven-wrapper`   |
| `ci/`    | Изменения в CI/CD, GitHub Actions               | `ci/add-trello-sync`           |
| `test/`  | Добавление/обновление тестов                   | `test/config-service`          |

---

## ✅ Примеры в действии

1. **Фича:**  
   Ветка → `feat/detection-ocr`  
   Коммит → `feat: добавить OCR через Tess4J`

2. **Багфикс:**  
   Ветка → `fix/application-yaml-crash`  
   Коммит → `fix: исправить падение при пустом application.yaml`

3. **Документация:**  
   Ветка → `docs/readme-update`  
   Коммит → `docs: добавить пример запуска`

---

⚡ Итог:  
- По названию ветки всегда понятно, что внутри.  
- Стиль веток = стиль коммитов.  
- Легко искать и фильтровать ветки в GitHub.  


---
