# qv-snapshot-cli

Утилита для автоматического сохранения кадров (snapshot) по событиям `events`.

## 🧩 Сборка

```bash
cd tools/qv-snapshot-cli
mvn -q -DskipTests package
```
После сборки JAR-файл создаётся по пути:

```
target/qv-snapshot-cli.jar
```

---

## ⚙️ Пример запуска

```bash
java -jar target/qv-snapshot-cli.jar \
  --mode=db \
  --pgurl=jdbc:postgresql://localhost:5432/quarryvision \
  --pguser=quarry --pgpass=quarry \
  --out=E:/SNAPS \
  --sql="select v.path, e.t_ms, e.detection_id, e.id
         from events e
         join detections d on d.id=e.detection_id
         join videos v on v.id=d.video_id
         where e.detection_id=103 order by e.id"
```

---

## 📁 Результат

Снимки сохраняются в каталоге:

```
E:/SNAPS/<video_name>/det_<detection_id>/event_<event_id>.jpg
```

---

## 💡 Замечания

* Можно менять `--out` для указания другого каталога.
* Параметр `--sql` можно использовать любой SELECT-запрос, возвращающий поля:
  `video_path`, `t_ms`, `detection_id`, `event_id`.
* Используется OpenCV (`nu.pattern.OpenCV.loadLocally()`).
