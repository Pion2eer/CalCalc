# CalCalc — памятка

- Отвечать по-русски. Пользователь: Димон, ПК pion4070 (Windows).
- Kotlin + Compose, Room, WorkManager. Пакет `ltd.pion.calcalc`, minSdk 26.
- Android SDK в облачном контейнере недоступен (dl.google.com закрыт): собираем и проверяем через GitHub Actions (`.github/workflows/build.yml`).
- Версия = номер сборки CI (`BUILD_NUMBER`), так обновление всегда видит новую версию.
- Ключ подписи только в секретах репозитория и у пользователя на ПК. Другой ключ сломает обновление поверх.
- ИИ: прокси `ai-local.ps1` из соседнего проекта «Мой ассистент» (D:\MyDesctopOffice\local-ai) перед Ollama `gemma4:12b-it-qat`, туннель Cloudflare; Apps Script вызывает `/chat`.
- Вход: Google Identity AuthorizationClient (drive.file + userinfo.email), только Android OAuth-клиент с SHA-1 ключа подписи. У каждого пользователя своя таблица «CalCalc» на его Диске (Sheets API токеном из приложения).
- ИИ общий: `apps-script/Code.gs` (web app). POST `{action:"parse", accessToken, text, weightKg}` → `{ok, retry, error, title, kcal, protein, fat, carbs, question, options[]}`. Скрипт проверяет токен через tokeninfo и список ALLOWED, ПК регистрируется `?action=localReg&key=REG_KEY {url, token, model, paused}`.
- Сайт и политика конфиденциальности: `docs/` → GitHub Pages, домен calcalc.pion.ltd.
