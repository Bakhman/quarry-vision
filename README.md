<p align="left">
  <!-- Java CI -->
  <a href="https://github.com/Bakhman/quarry-vision/actions/workflows/ci.yaml">
    <img src="https://github.com/Bakhman/quarry-vision/actions/workflows/ci.yaml/badge.svg?branch=main" alt="Java CI Status" />
  </a>
  <!-- Trello Sync -->
  <a href="https://github.com/Bakhman/quarry-vision/actions/workflows/trello-sync.yaml">
    <img src="https://github.com/Bakhman/quarry-vision/actions/workflows/trello-sync.yaml/badge.svg?branch=main" alt="Trello Sync" />
  </a>
  <!-- Trello Plan Apply -->
  <a href="https://github.com/Bakhman/quarry-vision/actions/workflows/trello-plan-apply.yaml">
    <img src="https://github.com/Bakhman/quarry-vision/actions/workflows/trello-plan-apply.yaml/badge.svg?branch=main" alt="Trello Plan Apply" />
  </a>
	<!-- Last commit -->
  <a href="https://github.com/Bakhman/quarry-vision/commits/main">
    <img src="https://img.shields.io/github/last-commit/Bakhman/quarry-vision/main.svg" alt="Last commit" />
  </a>
  <!-- License -->
  <a href="https://github.com/Bakhman/quarry-vision/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/Bakhman/quarry-vision.svg" alt="License" />
  </a>
</p>

# quarry-vision

Счётчик ковшей/рейсов для карьера + трекинг погрузки/разгрузки. Java 17, JavaFX, Maven, SnakeYAML, PostgreSQL.

## Project status

MVP exists and has been developed and tested on publicly available / YouTube video footage.

Current status: **blocked / waiting for customer input**.

Further production validation and detection tuning are blocked until real customer footage is available from the target environment. The cameras have not yet been installed on the excavators, and the customer has not yet provided real camera recordings.

Continuing to tune detection thresholds on non-representative public videos is not considered useful at this stage, because such videos cannot reliably validate:

- production accuracy;
- detection thresholds;
- false positives / false negatives;
- readiness of the algorithm for real operating conditions.

To continue production validation, the project needs the following customer input:

- 3–5 real video recordings from cameras installed in their intended working positions;
- approximately 10–30 minutes per recording;
- different real operating conditions where applicable: dust, low visibility, pauses, manoeuvres, empty bucket;
- resolution, FPS and a short description of each camera position;
- ground truth for each video: actual number of bucket-loading events / loading events.

Проект остановлен не из-за кода, а из-за отсутствия реального входного материала от клиента.
Следующий технически осмысленный шаг возможен только после получения видео с камер.

## Требования
- JDK 17 (Temurin)
- Maven 3.9+
- Windows (для запуска JavaFX профилем `win`; Linux/macOS возможны позже)

## Запуск
### Через Maven (рекомендовано)

mvn javafx:run

Нужно ПО, которое с двух камер(установленных в кабинах экскаваторов, которые
находятся на щебеночном карьере), считает ковши, время начала и окончания погрузки грузовиков, 
и считает количество рейсов грузовиков на щебеночный завод с одной камеры
(установленной на территории завода) на выгрузке. Также программа должна с одной 
камеры(установленной на территории завода) считать ковши на погрузке готовой продукции. - Минимальный интерфейс, бесплатные библиотеки, реализация на Java. Цель Разработать систему на Java с минимальным интерфейсом и использованием бесплатных библиотек, которая: 
1. Считает ковши с двух камер в кабинах экскаваторов, работающих на щебёночном карьере.
2. Фиксирует время начала и окончания погрузки каждого грузовика на карьере.
3. Считает количество рейсов грузовиков на завод с помощью одной камеры, установленной на территории завода в точке выгрузки.
4. Считает ковши при погрузке готовой продукции на заводе (другая камера на территории завода).
5. На погрузке в карьере а также на выгрузке и погрузке на заводе программа должна считывать номера загружаемых грузовиков и количество погрузок в каждый грузовик по отдельности. 

________________________________________
Техническое задание
Система автоматического учёта погрузки и выгрузки грузовиков на щебёночном карьере и заводе
Цель
Разработать систему на Java с минимальным интерфейсом и использованием бесплатных библиотек, которая выполняет:
1.	Подсчёт ковшей с двух камер в кабинах экскаваторов, работающих на щебёночном карьере.
2.	Фиксацию времени начала и окончания погрузки каждого грузовика.
3.	Подсчёт рейсов грузовиков на завод по одной камере, установленной на территории завода в точке выгрузки.
4.	Подсчёт ковшей при погрузке готовой продукции на заводе (другая камера на территории завода).
5.	Распознавание номеров грузовиков на погрузке (карьер) и при выгрузке/погрузке на заводе.
6.	Учёт количества ковшей для каждого грузовика отдельно по номеру.
________________________________________
Расположение камер  
•	Камера 1 — кабина экскаватора №1 (погрузка щебня в кузов).  
•	Камера 2 — кабина экскаватора №2 (погрузка щебня в кузов).  
•	Камера 3 — завод, точка выгрузки (учёт рейсов).  
•	Камера 4 — завод, погрузка готовой продукции (учёт ковшей и рейсов).  
________________________________________
Функциональные модули
1.	Видео-захват и потоковая обработка  
	Используем OpenCV для работы с RTSP/HTTP потоками камер.  
	Обработка кадров в реальном времени.  
2.	Подсчёт ковшей  
	Алгоритм детектирования ковша в кадре (например, на основе движения, изменения формы и области интереса).  
	Отдельный счётчик для каждого грузовика.  
3.	Определение начала и конца погрузки  
	Состояния: ожидание → погрузка → завершение.  
	Логика фиксации времени в базу данных.  
4.	Распознавание номеров  
	Используем Tess4J (Java-обёртка для Tesseract OCR) для извлечения номера с кадра.  
	Возможна дополнительная фильтрация с OpenALPR (если будет нужна точность выше).  
5.	Учёт рейсов  
	Камера 3 фиксирует проезд грузовика и сверяет номер с базой.  
	Запись события рейса с меткой времени.  
6.	Хранилище данных  PostgreSQL, для хранения:  
	▪︎ Список грузовиков (номер, дата первого появления).  
	▪︎ Лог загрузок/выгрузок (номер, количество ковшей, время начала/окончания, камера).  
7.	Минимальный интерфейс  
	JavaFX/простая веб-панель.  
	Просмотр статистики по каждому грузовику.  
	Экспорт отчётов (CSV/Excel).  
________________________________________
Технологический стек
•	Java 17+
•	OpenCV — детекция движения и объектов.
•	Tess4J — OCR для номеров.
•	PostgreSQL — база данных.
•	JavaFX или минимальный веб-интерфейс (Spring Boot + Thymeleaf).
•	Maven — сборка проекта.
________________________________________
```
src/main/java/com/quarryvision
  ├─ app/        # Boot, Config
  ├─ core/       # db, importer, video, ...
  └─ ui/         # JavaFX контроллеры
src/main/resources/
  ├─ application.yaml
  └─ logback.xml
```

## Chat Handbook

Для удобной работы с проектом см. [Chat Handbook](docs/chat-handbook.md).

Быстрый самотест (2 мин)

### Переопределение параметров через JVM свойства

Некоторые параметры можно менять без редактирования `application.yaml`, передавая их в виде
системных свойств (`-Dимя=значение`).

Пример: изменить время слияния событий ковша (`mergeMs`):

```bash  
    
mvn -q exec:java \
  -Dexec.mainClass="com.quarryvision.app.Boot" \
  -Dexec.jvmArgs=-Dqv.mergeMs=4000
```  

  
   
```bash

mvn -q -DskipTests -Dqv.mergeMs=17000 exec:java -Dexec.mainClass=com.quarryvision.app.Boot

```

```bash

mvn -q -DskipTests -Dqv.ocr.init=true exec:java -Dexec.mainClass=com.quarryvision.app.Boot

```

# OCR TEST
```bash
mvn -q -DskipTests \
-Dqv.ocr.init=true \
-Dqv.ocr.datapath=src/main/resources/tessdata \
-Dqv.ocr.languages=eng \
-Dqv.ocr.psm=7  \
-Dqv.mergeMs=7000 \
exec:java -Dexec.mainClass=com.quarryvision.app.Boot   
```
```bash
mvn -q -DskipTests clean compile \
-Dqv.ocr.init=true   \
-Dqv.ocr.minContrast=0.08   -Dqv.ocr.fillMin=0.005   -Dqv.ocr.fillMax=0.94 \
-Dqv.ocr.adaptBlock=31 -Dqv.ocr.adaptC=5 \
-Dqv.ocr.adaptBlock2=41 -Dqv.ocr.adaptC2=2 \
-Dqv.ocr.datapath=src/main/resources/tessdata \
exec:java -Dexec.mainClass=com.quarryvision.app.Boot

```

```bash
mvn -q -DskipTests \
-Dqv.ocr.init=true \
-Dqv.ocr.datapath=src/main/resources/tessdata \
-Dqv.ocr.languages=eng+rus \
-Dqv.ocr.minContrast=0.05 -Dqv.ocr.fillMax=0.97 -Dqv.ocr.maxRoiPerScan=120 \
exec:java -Dexec.mainClass=com.quarryvision.app.Boot
```

```bash
mvn -q -DskipTests \
  -Dqv.ocr.init=true \
  -Dqv.ocr.mode=fast \
  -Dqv.ocr.datapath=src/main/resources/tessdata \
  -Dqv.ocr.languages=eng+rus \
  -Dqv.ocr.psm=7 \
  -Dqv.ocr.minContrast=0.05 \
  -Dqv.ocr.fillMin=0.005 \
  -Dqv.ocr.fillMax=0.940 \
  -Dqv.ocr.dump=true \
  -Dqv.ocr.dumpDir=trace/ocr \
  -Dqv.ocr.dumpMax=12 \
  -Dqv.ocr.maxRoiPerScan=25 \
  exec:java -Dexec.mainClass=com.quarryvision.app.Boot

```

```bash
mvn -q -DskipTests \
-Dqv.detect.warmupMs=15000 \
-Dqv.detect.maxMs=20000 \
-Dqv.ocr.init=true \
-Dqv.ocr.datapath=src/main/resources/tessdata \
-Dqv.ocr.languages=eng+rus \
-Dqv.ocr.minContrast=0.05 \
-Dqv.ocr.fillMax=0.97 \
-Dqv.ocr.eventOffsetsSec=0,-4,4 \
-Dqv.ocr.maxRoiPerScan=100 \
-Dqv.ocr.stopVotes=2 \
exec:java -Dexec.mainClass=com.quarryvision.app.Boot

```







Приоритет:
1. Свойства `-D...` (самый высокий).
2. Значения из `application.yaml`.
3. Значения по умолчанию в коде.

### Детект-параметры (через `-D`)

Можно переопределять основные параметры алгоритма:

- `qv.stepFrames` — шаг по кадрам (по умолчанию 15).
- `qv.diffThreshold` — порог бинаризации разницы (по умолчанию 45).
- `qv.eventRatio` — минимальная доля изменившихся пикселей для события (по умолчанию 0.18).
- `qv.cooldownFrames` — задержка до следующего события (по умолчанию 150).
- `qv.minChangedPixels` — минимальное число изменившихся пикселей (по умолчанию 35000).
- `qv.morphW`, `qv.morphH` — размер ядра морфологии (по умолчанию 3×3).
- `qv.mergeMs` — объединение близких событий (по умолчанию 5000 мс).
- `qv.maxFrames` — ограничение на число кадров при тестах (по умолчанию 60).

> 💡 Эти значения удобно использовать при подборе параметров под новое видео, не меняя код и YAML.

