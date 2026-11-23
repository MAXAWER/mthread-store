# MThread Store

Личный магазин своих Android-приложений: Mac-приложение публикует APK, Android-приложение ставит и обновляет их в одно нажатие. Без своего сервера — каталог, скриншоты и APK лежат в этом репозитории и его Releases.

| Папка | Что это |
|---|---|
| `android/` | MThread Store для телефона (Kotlin, Jetpack Compose, Material You) |
| `mac/` | MThread Store Admin для macOS (Swift, SwiftUI) |
| `catalog.json`, `media/` | Каталог и картинки — их пишет админка, руками не править |
| `docs/design.md` | Как всё устроено |

## Сборка

```bash
mac/build-app.sh
```

Соберёт `MThread Store Admin.app` в корне проекта. Нужны Swift (Command Line Tools), `gh` с выполненным `gh auth login` и Android SDK build-tools (для `aapt2`).

```bash
./"Build Store APK.command"
```

Соберёт `dist/MThread-Store.apk`. Репозиторий и ключ шифрования берутся из `~/Library/Application Support/MThread Store/store.properties`, который создаёт админка. **Этот файл не публикуется — храните его копию**: без ключа закрытые APK не расшифровать.

## Как пользоваться

1. Откройте MThread Store Admin, перетащите APK в окно, заполните карточку, нажмите «Опубликовать» (⌘S).
2. Телефоны увидят новую версию при открытии Store или фоновой проверкой; на Android 12+ обновления ставятся без подтверждений.
3. «Закрытое приложение» — APK шифруется, поставить его можно только через Store.

Новая версия должна иметь больший `versionCode` и ту же подпись. Лимит GitHub — 2 ГБ на APK.

Админка работает с GitHub бережно: запросы идут по одному с паузами, при ограничениях GitHub она останавливается, а не повторяет запросы.

Публикация из терминала тем же кодом и с теми же паузами:

```bash
"MThread Store Admin.app/Contents/MacOS/MThreadStoreAdmin" --publish app.apk --name "Название" --category "Категория" --encrypted --icon icon.png
```
