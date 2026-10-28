# CalCalc — памятка

- Отвечать по-русски. Пользователь: Димон, ПК pion4070 (Windows).
- Kotlin + Compose, Room, WorkManager. Пакет `ltd.pion.calcalc`, minSdk 26.
- Android SDK в облачном контейнере недоступен (dl.google.com закрыт): собираем и проверяем через GitHub Actions (`.github/workflows/build.yml`).
- Версия = номер сборки CI (`BUILD_NUMBER`), так обновление всегда видит новую версию.
- Ключ подписи только в секретах репозитория и у пользователя на ПК. Другой ключ сломает обновление поверх.
- ИИ: прокси `ai-local.ps1` из соседнего проекта «Мой ассистент» (D:\MyDesctopOffice\local-ai) перед Ollama `gemma4:12b-it-qat`, туннель Cloudflare; Apps Script вызывает `/chat`.
- Протокол с Apps Script: POST `{action:"pushEntries", token, entries}` → `{ok}`; GET `?action=getChanges&token&since` → `{ok, now, entries:[{id,title,kcal,protein,fat,carbs,status}]}`.
