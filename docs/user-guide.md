# 🧩 QuarryVision — User Guide (v1.1.2)

## 1. Назначение
QuarryVision — приложение для автоматического подсчёта ковшей, мониторинга загрузки самосвалов и формирования отчётов по видео из карьера.

---

## 2. Состав дистрибутива

После распаковки архива `quarry-vision-v1.1.2.zip` структура папок выглядит так:

```
QuarryVision/
├── app/
│   └── quarry-vision.jar
├── config/
│   └── application.yaml
├── jre/
│   └── ... (встроенная Liberica JRE 21 Full)
└── run.bat
```

---

## 3. Системные требования

- Windows 10 / 11 (x64)
- Не требуется установленная Java (JRE встроена)
- Свободное место: 1.5 GB
- Подключённая PostgreSQL 16+

---

## 4. Быстрый запуск

1. Распакуйте архив `quarry-vision-v1.1.2.zip` в любую папку (например, `D:\QuarryVision`).
2. Запустите `run.bat` двойным щелчком.
3. После запуска появится интерфейс QuarryVision (JavaFX).

> Приложение готово к работе сразу после настройки `application.yaml`.

---

## 5. Настройка `application.yaml`

Файл расположен в `config/application.yaml`.  
Пример:

```yaml
db:
  url: jdbc:postgresql://localhost:5432/quarryvision
  user: quarry
  pass: quarry

import:
  patterns: ["*.mp4", "*.avi"]
  inbox: E:/INBOX
  source: E:/CAMERAS

detect:
  stepFrames: 15
  diffThreshold: 45
  eventRatio: 0.18
  cooldownFrames: 150
  minChangedPixels: 35000
  mergeMs: 5000
  emaAlpha: 0.2
  thrLowFactor: 0.6
  minActiveMs: 1200
  nmsWindowMs: 2000
  ```

---

## 6. Основные каталоги

- **INBOX** — папка, из которой берутся видеофайлы для анализа.  
- **DB** — PostgreSQL база данных `quarryvision`, создаётся автоматически при первом запуске.
- **TRACE/** — создаётся автоматически при параметре `-Dqv.trace=true`.
- **SNAPS/** — результат CLI-утилиты snapshot.

---

## 7. Завершение работы

Для корректного завершения просто закройте окно приложения.  
Все фоновые потоки и соединения с БД будут остановлены автоматически.

---

**Версия:** v1.1.2  
**Автор:** Bakhmai  
**Дата:** 2025-10-19