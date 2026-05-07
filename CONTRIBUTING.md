# Contributing to QuarryVision

Добро пожаловать! 🎉  
Этот документ описывает, как правильно вносить изменения в проект, чтобы всё было прозрачно и стабильно.

---

## Workflow (TL;DR)

1. Обнови `main`:
   ```sh
   git switch main && git pull
   ```

2. Создай новую ветку:

   ```sh
   git switch -c feature/<short-name>
   # или fix/<short-name>, chore/<short-name>
   ```

3. Пиши код → добавь изменения:

   ```sh
   git add -A && git commit -m "feat: кратко что сделали"
   ```

4. Локальная проверка:

   ```sh
   mvn clean verify
   ```

5. Запушь ветку:

   ```sh
   git push -u origin <branch>
   ```

   → создай **Pull Request**.

6. Жди CI:

   * 🟢 зелёный CI → merge (Squash).
   * 🔴 красный CI → фикс и пушь в ту же ветку.

7. После merge:

   ```sh
   git switch main && git pull
   git branch -d <branch>
   ```
   
8. и удали ветку на GitHub:
    ```sh
   git push origin --delete <branch_name>
   ```
    Alternative (shorter) syntax:\
    You can also use a shorter syntax for deleting remote branches:
    ```sh
    git push origin :<branch_name>
    ```

---

## Ветвление

Используй префиксы:

* `feature/<topic>`
* `fix/<bug>`
* `chore/<task>`

---

## Коммиты (Conventional Commits)

Формат:

```
<type>: краткое описание
```

Примеры:

* `feat: добавить загрузку видео из USB`
* `fix: исправить падение при пустом application.yaml`
* `chore: обновить зависимости`
* `docs: поправить README`
* `ci: изменения в GitHub Actions, CI/CD`
* `test: добавить unit-тесты для Config`

📂 См. также: [Branch Naming Guide](./docs/branch-naming.md)

---

## Pull Request

* Должен собираться:

  ```sh
  mvn clean verify
  ```
* Опиши **что** и **зачем** меняется.
* Обнови README/доки при необходимости.
* Если PR устарел → нажми **Update branch** или сделай:

  ```sh
  git merge origin/main
  ```

---

## CI / Правила ветки `main`

* Прямой push в `main` **запрещён**
  (Branch protection + local pre-push hook).
* Обязательный check: **Java CI / build (pull\_request)**.
  Без зелёного CI merge невозможен.
* Рекомендуемая стратегия: **Squash and merge**.

---

## Локальный запуск

Сборка:

```sh
mvn clean verify
```

Запуск JavaFX:

* из Maven:

  ```sh
  mvn org.openjfx:javafx-maven-plugin:0.0.8:run "-Djavafx.platform=win"
  ```
* из IDE (IntelliJ IDEA):
  в **Run/Debug Configurations** добавь VM options:

  ```
  --add-modules=javafx.controls,javafx.fxml
  ```

---

## Полезные команды Git

```sh
git fetch --all --prune
git log --oneline --graph --decorate --all
git restore -SW .     # откат всех незакоммиченных изменений
```

---

## Документация (docs/)
- Любые правки в документации делаем отдельной веткой вида `docs/<topic>` и через Pull Request.
- Для мелких правок: `docs/<topic>` → PR → зелёный CI → squash & merge.
- Автогенерируемые файлы (например, `TRELLO.md`) приходят отдельными PR с метками `chore`, `trello`.

💡 Спасибо за вклад! Делая всё по правилам, мы держим проект стабильным и понятным 🙌

