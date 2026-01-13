# PERF baseline — QuarryVision (до оптимизаций)

Дата: YYYY-MM-DD  
Ветка/коммит: `<branch>` / `<commit_sha>`  
Машина: CPU (Central Processing Unit, центральный процессор) / RAM / OS  
Java: `<version>`  
OpenCV: `<version>`  
Tesseract/Tess4J: `<version>`  

---

## 1) Набор тестов (эталонные видео)

### Видео A (типовое)
- Имя/путь: `<...>`
- Длительность: `<...>`
- FPS (frames per second, кадров в секунду): `<...>`
- Разрешение: `<...>`
- Условия: `<день/ночь/пыль/блики/угол>`

### Видео B (сложное)
- Имя/путь: `<...>`
- Длительность: `<...>`
- FPS: `<...>`
- Разрешение: `<...>`
- Условия: `<...>`

---

## 2) Команда запуска / сценарий

Команда (CLI (Command Line Interface, интерфейс командной строки) или UI (User Interface, пользовательский интерфейс) шаги):
```bash
<insert run command here>
```

Конфиги/параметры:
- режим: FAST/AUDIT/DEFAULT
- qv.ocr.*: `<...>`
- qv.detect.*: `<...>`

---

## 3) Итоговые метрики (PERF)

Вставить строку `PERF {...}` (как есть из лога):
```text
PERF {...}
```

Сводка:
- total_time_ms = `<...>`
- frames_decoded = `<...>`
- frames_processed = `<...>`
- events_found = `<...>`
- roi_scans = `<...>`
- ocr_calls = `<...>`
- ocr_ms_total = `<...>`
- ocr_ms_avg = `<...>`
- ocr_ms_p95 = `<...>` (если есть)
- db_ms_total = `<...>`
- detect_ms_total = `<...>`
- decode_ms_total = `<...>`

---

## 4) Наблюдения
- Где “горит” (топ-1 узкое место): `<...>`
- Аномалии (если есть): `<...>`

---

## 5) Цели на следующий PR
- Ожидаемый эффект: `<например: ocr_calls ↓ 10×, total_time ↓ 5×>`
- Риски: `<...>`

