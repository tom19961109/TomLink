# TomLink

TomLink 是獨立的開源 Android 活動資料工具。步數和交通移動保存在裝置的 SQLite 資料庫，可離線匯出 JSON。使用者也可以用 Gmail 授權，主動把活動同步至 TomPet。

- [GitHub Releases](https://github.com/tom19961109/TomLink/releases)
- [步數交換格式 1.0](docs/steps-format.md)

## 計步

1. 安裝 APK。需要 Android 8.0 以上與系統計步器或逐步偵測器。
2. 第一次開啟並允許身體活動權限後，App 會自動開始計步。背景服務能在切換 App 或鎖屏後繼續運作，可在 App 設定關閉「即時計步」。強制停止或重新開機後，重新開啟 App 即可繼續。
3. 支援逐步偵測器時，首頁會隨步數更新。其他裝置依系統計步器的回報更新。

## 交通移動

Google Play 服務的身體活動辨識會記錄搭車或騎單車的時間。可在「設定與更新」開關自動記錄交通移動。

GPS 時速預設關閉。開啟後需允許精確定位，TomLink 只在首頁所在的 App 畫面位於前景時讀取速度。收到有效訊號才顯示當前時速。App 只保存測量時間和速度的日統計，不儲存座標、路線或同步 GPS 統計。

首頁用 GPS 測得的時間加權平均時速乘上當天行駛時間。GPS 涵蓋部分行程時會標為推估，當天完全沒有 GPS 測量時則依預設時速估算。

## 活動月曆

週曆和月曆的行駛距離以各日搭車和單車的時間及預設時速估算。

- **活動月曆**：頂端「設定」可調整每日步數目標（預設 8,000 步）。已達標顯示綠色，未達標顯示紅色，未來日期顯示灰色。點日期看步數與估算距離。
- **活動週曆**：先顯示本週總步數與估算距離，再依星期一到星期日列出每天的資料。
- 預設搭車 30 km/h、騎單車 15 km/h，可在月曆或週曆設定。既有紀錄會按目前設定重新估算。

## 匯出與同步

- 選「匯出活動 JSON」可在手機本機儲存檔案，也可手動匯入 TomPet。
- 首次選「同步至 TomPet」會開啟瀏覽器。在瀏覽器使用 Gmail 登入，比對裝置代碼並確認連結。回到 TomLink 再按一次即可上傳。TomPet 只允許同一個 Gmail 帳號讀取這些活動紀錄。
- 同步的交通移動時間會計入 TomPet 的外出探索，與重疊的外出探索紀錄只計一次。可在 App 解除 Gmail 連結。裝置授權有效期為 14 天。
## 更新

- 從 alpha.3 開始，可以在 App 內更新。alpha.12 起從首頁的「設定與更新」點「檢查更新」。有新版時點「下載並安裝新版」，再於 Android 系統畫面確認安裝。
- 首次使用 App 內更新時，Android 會要求允許 TomLink 安裝應用程式。
- alpha.1 和 alpha.2 採用不同簽章，升到 alpha.3 前請先同步至 TomPet 或匯出步數 JSON，再移除舊版並安裝 alpha.3。

## 建置

使用 JDK 21 與 Android SDK Platform 36。Windows 執行 `./gradlew.bat assembleDebug`，Linux 或 macOS 執行 `bash gradlew assembleDebug`。APK 位於 `app/build/outputs/apk/debug/`。

### 發布 Android 版本

以「架構.頁面.功能.DEBUG」更新 `app/build.gradle` 的版本名稱，首次正式發行使用 `1.0.0.0`，並遞增 Android 版本代碼，執行 `assembleRelease`，使用 TomLink 的固定發布金鑰簽署 APK。請將金鑰和密碼保存在 Git 之外。

為簽署後的 `TomLink-Android-preview.apk` 建立 `update.json`，欄位為 `versionCode`（數字）、`versionName` 和 `sha256`（完整 APK 的 SHA-256）。在相同版本的原始碼提交之上建立一筆只含這兩個檔案的提交，將它推送到 `apk-v<versionName>` 標籤，然後推送指向原始碼的 `v<versionName>` 標籤。GitHub Actions 驗證 APK 簽章、版本和雜湊後發布。
