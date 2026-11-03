# Продолжение разработки

Android-приложение для изучения греческого; пользователь общается по-русски. Текущее состояние описано в README.md, docs/development-status.ru.md и docs/product-spec.ru.md. Последняя версия: 0.3.17 / versionCode 31.

## Сборка и проверка

- Обычная среда: JDK 21, Android SDK 35, Build Tools 35.0.0; `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
- Облако: `python tools/setup_android.py`, затем `python tools/gradle_cloud.py :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
- Словарь: `python tools/test_a2_dictionary.py`. При изменении корпуса: tools/build_a2_dictionary.py; происхождение и аудит в docs/a2-dictionary.ru.md.
- Нативный тест итогового экрана формирует app/build/reports/ux/summary.png. Проверка выполняется в Robolectric API 28, а не на настоящем телефоне.

## Совместимость данных

Сохранять applicationId ru.greeklearning.app и стабильные ID слов. Не удалять SQLite/SharedPreferences при обновлении. Изменения схемы — только миграциями. Не заменять корпус демонстрационными словами. Не округлять Points: дроби нужны для подсказок. Не начислять баллы повторно при восстановлении сессии, callback TTS или повторном нажатии. Очередь ошибок относится к конкретному режиму и группе слов.

## Выпуск

Увеличивать versionCode/versionName. APK — app/build/outputs/apk/debug/app-debug.apk; результаты проверок описывать в docs/validation-<версия>.ru.md. Отправлять исходники в GitHub после выполненной задачи, когда сборка и необходимые проверки прошли.

Ключи подписи и SDK хранятся вне репозитория. Для обновления существующей установки нужен тот же ключ, что использовался прежде. Облачный debug-ключ находится в /workspace/toolchains/android-user/debug.keystore; он НЕ входит в Git. На другой машине новый debug-ключ не обеспечит обновление поверх прежней установки. Никогда не коммитить ключи, токены, local.properties или пользовательские резервные копии. Самостоятельно не удалять установленное приложение для обхода несовпадения подписи.
