# CalCalc — памятка

- Отвечать по-русски. Пользователь: Димон, ПК pion4070 (Windows).
- Kotlin + Compose, Room, WorkManager. Пакет `ltd.pion.calcalc`, minSdk 26.
- Android SDK в облачном контейнере недоступен (dl.google.com закрыт): собираем и проверяем через GitHub Actions (`.github/workflows/build.yml`).
- Версия = номер сборки CI (`BUILD_NUMBER`), так обновление всегда видит новую версию.
- Ключ подписи только в секретах репозитория и у пользователя на ПК. Другой ключ сломает обновление поверх.
- ИИ: прокси `ai-local.ps1` из соседнего проекта «Мой ассистент» (D:\MyDesctopOffice\local-ai) перед Ollama `gemma4:12b-it-qat`, туннель Cloudflare; Apps Script вызывает `/chat`.
- Вход: Google Identity AuthorizationClient (drive.file + userinfo.email), только Android OAuth-клиент с SHA-1 ключа подписи. У каждого пользователя своя таблица «CalCalc» на его Диске (Sheets API токеном из приложения).
- ИИ общий: `apps-script/Code.gs` (web app). POST `{action:"parse", accessToken, text, weightKg}` → `{ok, retry, error, title, kcal, protein, fat, carbs, question, options[]}`. Скрипт проверяет токен через tokeninfo и список ALLOWED, ПК регистрируется `?action=localReg {url, token, model, paused}` (первая регистрация закрепляет токен прокси); ai-local.ps1 шлёт её также на адреса из local-ai\extra-scripts.txt.
- Журнал: скрипт пишет всё (регистрации ПК, запросы parse с временем, ошибки приложения через action clientLog, самопроверку ПК раз в 10 мин) в таблицу «CalCalc AI журнал» на Диске владельца. Claude читает её через Google Drive (search_files title «журнал» → read_file_content).
- Сайт и политика конфиденциальности: `docs/` → GitHub Pages, домен calcalc.pion.ltd.
- Проверка UI перед выпуском (обязательно): `ScreenshotTest` (Roborazzi) рисует экраны, CI кладёт их в ветку `screenshots/<ветка>`; job `device` гоняет `.github/ui-smoke.sh` на эмуляторе с настоящей клавиатурой → `screenshots/<ветка>-device`. Смотреть: `git fetch origin '+refs/heads/screenshots/*:refs/remotes/origin/screenshots/*'` и `git archive`. Артефакты и логи Actions из контейнера не скачиваются (blob закрыт), логи читать через GitHub MCP get_job_logs. Правки UI сначала в отдельной ветке, в main (= релиз) только после просмотра скриншотов.
- Текст «что нового» для баннера обновления: `.github/release-notes.txt` (по-русски), обновлять при каждом заметном релизе и держать там только новое этого выпуска (1–3 строки), старое удалять.
