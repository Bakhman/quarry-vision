### 📍 **Roadmap v1.2 (Work in Progress)**

#### 1. UX / UI

* [ ] Добавить индикаторы прогресса (Import, Detect).
* [ ] Добавить статус-бар с текущим процессом.
* [ ] Сделать кнопки *Start / Stop* для `CameraWorker` более очевидными.

#### 2. Detection

* [ ] Вынести параметры в отдельную вкладку “Detection Settings”.
* [ ] Добавить автооптимизацию параметров (adaptive EMA, thresholds).
* [ ] Улучшить FSM (точнее отличать загрузку и выгрузку ковша).

#### 3. OCR / Аналитика

* [ ] Добавить модуль OCR (Tess4J) для распознавания номеров самосвалов.
* [ ] Привязка OCR к событиям загрузки.

#### 4. Snapshot CLI / Reports

* [ ] Добавить тест Snapshot CLI в CI.
* [ ] CSV → XLSX экспорт (Apache POI / OpenCSV).

#### 5. DevOps

* [ ] Добавить workflow `test-snapshot.yaml`.
* [ ] Добавить сборку lightweight Linux-версии (без UI, только CLI).
* [ ] Добавить автоматический changelog генератор для релизов.

#### 6. Research (pet-микросервисы)

* [ ] Разделить detection на сервис + REST API.
* [ ] gRPC или HTTP API для внешнего вызова `detect(video)`.
* [ ] Docker Compose demo (Postgres + detect-service + UI-client).

