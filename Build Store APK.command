#!/bin/bash
# Собирает MThread Store с ключом и репозиторием из store.properties → dist/MThread-Store.apk
set -e
cd "$(dirname "$0")"
if [ ! -f "$HOME/Library/Application Support/MThread Store/store.properties" ] && [ ! -f store.properties ]; then
  echo "Сначала откройте «MThread Store Admin» и создайте репозиторий."
  read -r -p "Enter — закрыть"; exit 1
fi
if [ -z "$JAVA_HOME" ]; then
  for jdk in "$HOME/Documents/1APPS/Cladroid/.tools/amazon-corretto-21.jdk/Contents/Home" \
             "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
             "$(/usr/libexec/java_home 2>/dev/null)"; do
    [ -x "$jdk/bin/java" ] && export JAVA_HOME="$jdk" && break
  done
fi
(cd android && ./gradlew -q assembleRelease)
mkdir -p dist
cp android/app/build/outputs/apk/release/app-release.apk dist/MThread-Store.apk
echo "Готово: dist/MThread-Store.apk — перетащите его в MThread Store Admin, чтобы Store обновлял сам себя."
open dist
