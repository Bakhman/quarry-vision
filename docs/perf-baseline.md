# PERF baseline — QuarryVision (до оптимизаций)

Дата: 2026-01-14  
Ветка/коммит: `<TODO: branch>` / `<TODO: commit_sha>`  
Машина: `<TODO: CPU (Central Processing Unit, центральный процессор)>` / `<TODO: RAM>` / `<TODO: OS>`  
Java: `<TODO: version>`  
OpenCV: `<TODO: version>`  
Tesseract/Tess4J: `<TODO: version>`  

---

## 1) Набор тестов (эталонные видео)

### Видео A (типовое)
- Имя/путь: `locs2.mp4` (путь: `<TODO: full path>`)
- Длительность: ~00:02:39.5 (из frames=4786 @ fps=30.0)
- FPS (frames per second, кадров в секунду): 30.0
- Разрешение: `<TODO: width x height>`
- Условия: `<TODO: день/ночь/пыль/блики/угол>`

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
UI: Import tab → кнопка "Detect" → выбрать/вставить путь к locs2.mp4 → запуск
```
```bash
mvn -q -DskipTests \
-Dqv.ocr.init=true \
-Dqv.ocr.datapath=src/main/resources/tessdata \
-Dqv.ocr.languages=eng+rus \
-Dqv.ocr.minContrast=0.05 -Dqv.ocr.fillMax=0.97 -Dqv.ocr.maxRoiPerScan=120 \
exec:java -Dexec.mainClass=com.quarryvision.app.Boot
```

Конфиги/параметры:
- режим: FAST (по логике OCR_FAST_MODE=true)
- qv.ocr.eventOffsetsSec: 0,-4,4 (из PERF)
- qv.ocr.maxRoiPerScan: 120 (из PERF)
- qv.ocr.stopVotes: <TODO: not set in baseline>
- stepFrames: 15 (из PERF)
- прочие qv.detect.*: <TODO: capture from "Detect params: ...">
---

## 3) Итоговые метрики (PERF)

Вставить строку `PERF {...}` (как есть из лога):
```text
+PERF {video='locs2.mp4', totalMs=2535957, openMs408, loopMs2535506, fps=30.0, frames=4786, events=5, ocrEnabled=true, snapReads=11, roiAttempts=1132, roiDroppedFast=41, ocrCalls=1091, ocrRoiMs=2161831, ocrMs=2156869, ocrAvgMs=1976, stepFrames=15, maxRoiPerScan=120, eventOffsetsSec='0,-4,4'}

```

Сводка:
- total_time_ms = 2535957 (~42m 15.9s)
- frames_decoded = 4786
- frames_processed = <TODO: depends on stepFrames/EOF skips>
- events_found = 5
- roi_scans = 1132 (roiAttempts)
- roi_dropped_fast = 41
- ocr_calls = 1091
- ocr_ms_total = 2156869 (~35m 56.9s)
- ocr_ms_avg = 1976 (~1.98s / call)
- ocr_ms_p95 = <TODO: not captured in PR-0>
- db_ms_total = <TODO: not captured in PR-0>
- detect_ms_total = <TODO: not captured in PR-0>
- decode_ms_total = <TODO: not captured in PR-0>

---

## 4) Наблюдения
- Где “горит” (топ-1 узкое место):  
    OCR. ocrMs=2_156_869ms из totalMs=2_535_957ms (доминирует время на OCR).
- Аномалии (если есть):    
    Очень дорогой один OCR-вызов: ocrAvgMs ~ 1976ms.  
    Высокое число OCR-вызовов: ocrCalls=1091 на events=5 (в среднем ~218 OCR-вызовов на событие).

---

## 5) Цели на следующий PR
- Ожидаемый эффект:
    ocr_calls ↓ 5–10× (за счёт раннего останова/голосования внутри одного scan)
    total_time ↓ 3–8× (если OCR остаётся доминирующим)
- Риски:
    Ранний стоп может закрепить “мусорный” номер, если normalizePlate(...) нормализует ложные кандидаты. 
    Требуется включать/настраивать через qv.ocr.stopVotes и сравнивать по PERF.

