# TomLink

TomLink 是獨立的開源 Android 活動資料工具。步數保存在裝置的 SQLite 資料庫，可離線匯出 JSON。使用者也可以用 Gmail 授權，主動把步數同步至 TomPet。

- [GitHub Releases](https://github.com/tom19961109/TomLink/releases)
- [步數交換格式 1.0](docs/steps-format.md)

## 計步

1. 安裝測試版 APK。需要 Android 8.0 以上與系統計步器或逐步偵測器。
2. 第一次開啟並允許身體活動權限後，App 會自動開始計步。背景服務能在切換 App 或鎖屏後繼續運作，可從 App 或通知暫停。強制停止或重新開機後，重新開啟 App 即可繼續。
3. 預設使用系統計步器以減少耗電。若要較快看到每一步，且手機支援逐步偵測器，可開啟「即時步數偵測」。實際延遲、準確度和耗電會依手機而異。

## 匯出與同步

- 選「匯出步數 JSON」可在手機本機儲存檔案，也可手動匯入 TomPet。
- 首次選「同步至 TomPet」會開啟瀏覽器。在瀏覽器使用 Gmail 登入，比對裝置代碼並確認連結。回到 TomLink 再按一次即可上傳。TomPet 只允許同一個 Gmail 帳號讀取這些步數。
- 同步只會傳送步數欄位；可在 App 解除 Gmail 連結。裝置授權有效期為 14 天。
- 測試版使用偵錯簽章。如果系統要求重新安裝，請先從舊版匯出 JSON，再把備份匯入 TomPet。

## 建置

使用 JDK 21 與 Android SDK Platform 36。Windows 執行 `./gradlew.bat assembleDebug`，Linux 或 macOS 執行 `bash gradlew assembleDebug`。APK 位於 `app/build/outputs/apk/debug/`。
