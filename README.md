# TomLink

TomLink 是獨立的開源 Android 活動資料工具。初版從手機前景計步開始，將區間步數保存在裝置的 SQLite 資料庫，使用者可自行匯出 JSON。TomPet 是第一個可以讀取該檔案的應用。

- [GitHub Releases](https://github.com/tom19961109/TomLink/releases)
- [步數交換格式 1.0](docs/steps-format.md)

## 使用方式

1. 安裝測試版 APK。需要 Android 8.0 以上與系統計步感測器。
2. 點「開始計步」，Android 10 以上會要求身體活動權限。收到第一筆感測器資料後開始記錄增量。
3. 點「停止計步」，再點「匯出步數 JSON」，選擇檔案儲存位置。可將檔案匯入 [TomPet](https://tomforge.com/tompet)。

切換應用程式或鎖屏會結束本次計步。背景計步、重啟後續記和耗電都需要實機驗證。測試版使用偵錯簽章，升級若需要重新安裝，請先匯出資料。

## 建置

使用 JDK 21 與 Android SDK Platform 36。Windows 執行 `./gradlew.bat assembleDebug`，Linux 或 macOS 執行 `bash gradlew assembleDebug`。APK 位於 `app/build/outputs/apk/debug/`。
