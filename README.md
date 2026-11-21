# TomLink

TomLink 是獨立的開源 Android 活動資料工具。步數保存在裝置的 SQLite 資料庫，可離線匯出 JSON。使用者也可以用 Gmail 授權，主動把步數同步至 TomPet。

- [GitHub Releases](https://github.com/tom19961109/TomLink/releases)
- [步數交換格式 1.0](docs/steps-format.md)

## 計步

1. 安裝測試版 APK。需要 Android 8.0 以上與系統計步器或逐步偵測器。
2. 第一次開啟並允許身體活動權限後，App 會自動開始計步。背景服務能在切換 App 或鎖屏後繼續運作，可從 App 或通知暫停。強制停止或重新開機後，重新開啟 App 即可繼續。
3. 預設使用系統計步器以減少耗電。若要較快看到每一步，且手機支援逐步偵測器，可開啟「即時步數偵測」。實際延遲、準確度和耗電會依手機而異。

## 步數月曆

在首頁點「步數月曆」，可離線查看每日步數、週一到週日的週合計，以及今日和本週的總步數。月曆使用手機時區，舊版已留在本機的步數也會自動納入統計。

## 匯出與同步

- 選「匯出步數 JSON」可在手機本機儲存檔案，也可手動匯入 TomPet。
- 首次選「同步至 TomPet」會開啟瀏覽器。在瀏覽器使用 Gmail 登入，比對裝置代碼並確認連結。回到 TomLink 再按一次即可上傳。TomPet 只允許同一個 Gmail 帳號讀取這些步數。
- 同步只會傳送步數欄位；可在 App 解除 Gmail 連結。裝置授權有效期為 14 天。
## 更新

- 從 alpha.3 開始，在 App 點「檢查更新」。有新版時點「更新」，下載完成後在 Android 系統畫面確認安裝。
- 首次使用 App 內更新時，Android 會要求允許 TomLink 安裝應用程式。
- alpha.1 和 alpha.2 採用不同簽章，升到 alpha.3 前請先同步至 TomPet 或匯出步數 JSON，再移除舊版並安裝 alpha.3。

## 建置

使用 JDK 21 與 Android SDK Platform 36。Windows 執行 `./gradlew.bat assembleDebug`，Linux 或 macOS 執行 `bash gradlew assembleDebug`。APK 位於 `app/build/outputs/apk/debug/`。

### 發布測試版

更新 `app/build.gradle` 的版本名稱並遞增版本碼，執行 `assembleRelease`，使用 TomLink 的固定發布金鑰簽署 APK。請將金鑰和密碼保存在 Git 之外。

為簽署後的 `TomLink-Android-preview.apk` 建立 `update.json`，欄位為 `versionCode`（數字）、`versionName` 和 `sha256`（完整 APK 的 SHA-256）。在相同版本的原始碼提交之上建立一筆只含這兩個檔案的提交，將它推送到 `apk-v<versionName>` 標籤，然後推送指向原始碼的 `v<versionName>` 標籤。GitHub Actions 驗證 APK 簽章、版本和雜湊後發布。
