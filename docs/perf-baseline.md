# PERF baseline — QuarryVision (до оптимизаций)

Дата: 2026-01-15
Ветка/коммит: `main` / `17c8539e34da7f98ff1ba2b043ade4cdc8f4ad89`
Машина: CPU (Central Processing Unit, центральный процессор) / RAM / OS (уточни)
Java: `<version>`
OpenCV: `<version>`
Tesseract/Tess4J: `<version>`

---

## 1) Набор тестов (эталонные видео)

### Видео A (типовое)
- Имя/путь: `locs2.mp4` (путь уточни)
- Длительность: ~159.53s (4786 frames / 30 FPS (frames per second, кадров в секунду))
- FPS (frames per second, кадров в секунду): 30.0
- Разрешение: `<...>`
- Условия: `<день/ночь/пыль/блики/угол>``

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
-Dqv.ocr.minContrast=0.05 \
-Dqv.ocr.fillMax=0.97 \
-Dqv.ocr.eventOffsetsSec=0,-4,4 \
-Dqv.ocr.maxRoiPerScan=120 \
-Dqv.ocr.stopVotes=2 \
exec:java -Dexec.mainClass=com.quarryvision.app.Boot
```

Конфиги/параметры:
- режим: FAST (по логике OCR_FAST_MODE=true)
- qv.ocr.: init=true, datapath=src/main/resources/tessdata, languages=eng+rus, 
              minContrast=0.05, fillMax=0.97, maxRoiPerScan=120, stopVotes=2
- qv.detect.: stepFrames=15 (остальное см. PERF)
---

## 3) Итоговые метрики (PERF)

Вставить строку `PERF {...}` (как есть из лога):
```text
PERF {{video='locs2.mp4', totalMs=2578865, openMs=422, loopMs=2578409, fps=30.0, frames=4786, events=5, ocrEnabled=true,
snapReads=11, roiAttempts=1132, roiDroppedFast=41, ocrCalls=1091, ocrRoiMs=2205016, ocrMs=2199415, ocrAvgMs=2015, ocrStopByVotes=0,
stepFrames=15, maxRoiPerScan=120, eventOffsetsSec='0,-4,4'}}
```

Сводка:
- total_time_ms = 2_578_865 (~42m 58.865s)
- frames_decoded = 4_786
- frames_processed = <...> (если появится метрика — заполним)
- events_found = 5
- snap_reads = 11
- roi_attempts = 1_132
- roi_dropped_fast = 41
- ocr_calls = 1_091
- ocr_ms_total = 2_199_415
- ocr_roi_ms_total = 2_205_016
- ocr_ms_avg = 2_015
- ocr_stop_by_votes = 0
- db_ms_total = <...>
- detect_ms_total = <...>
- decode_ms_total = <...>

---

## 4) Наблюдения
- Где “горит” (топ-1 узкое место): OCR (~85% totalMs по ocrMs/totalMs)
- Аномалии (если есть): ocrStopByVotes=0 (ранняя остановка по голосам ни разу не сработала) 
plate@ (по логу): IA6864OA, O205EX (истинный: BE8624AO)
---

## 5) Цели на следующий PR
- Ожидаемый эффект: ocr_calls ↓ (минимум 3×), total_time ↓ (минимум 2×) без деградации качества номера
- Риски: ранняя остановка/агрессивный отбор ROI может “закрепить” ложный номер

