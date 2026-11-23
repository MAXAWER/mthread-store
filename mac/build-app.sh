#!/bin/bash
# Собирает «MThread Store Admin.app» в корне проекта.
set -e
cd "$(dirname "$0")"
swift build -c release
APP="../MThread Store Admin.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/MThreadStoreAdmin "$APP/Contents/MacOS/"
cp Resources/AppIcon.icns "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>MThread Store Admin</string>
  <key>CFBundleDisplayName</key><string>MThread Store Admin</string>
  <key>CFBundleIdentifier</key><string>app.mthread.store.admin</string>
  <key>CFBundleExecutable</key><string>MThreadStoreAdmin</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.developer-tools</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>CFBundleDevelopmentRegion</key><string>ru</string>
</dict></plist>
PLIST
codesign --force --sign - "$APP"
echo "Готово: $APP"
