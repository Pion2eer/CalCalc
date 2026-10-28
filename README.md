# CalCalc

Android-приложение для подсчёта калорий. Ввод работает без сети, калории еды досчитывает ИИ на домашнем ПК
(Ollama, Gemma 4) через Google Apps Script. Активность считается по MET, калории можно ввести вручную.

План: https://claude.ai/artifact/568FTayHpkALYiZ11h7q6d

## Сборка

Каждый push в `main` собирает APK в GitHub Actions. Если заданы секреты `KEYSTORE_BASE64` и `KEYSTORE_PASSWORD`,
APK подписывается постоянным ключом и выкладывается релизом `v0.1.<номер сборки>` вместе с `version.json`.
Приложение читает `releases/latest/download/version.json` и предлагает обновиться (кнопка в «Профиле»).

Без секретов собирается debug-APK как артефакт сборки, релиз не создаётся.
