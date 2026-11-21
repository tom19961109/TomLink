# TomLink 步數交換格式 1.0

匯出檔是 UTF-8 JSON 陣列。每筆是半開時間區間 [startTime, endTime) 內的正整數步數。時間是 ISO 8601，須附 UTC 或時區偏移。裝置每次安裝產生一個隨機來源識別碼。

```json
[
  {
    "schemaVersion": "1.0",
    "recordId": "sample-001",
    "sourceId": "android-step-counter:sample",
    "type": "steps",
    "measurementKind": "interval-total",
    "value": 150,
    "unit": "count",
    "startTime": "2026-11-21T10:00:00+08:00",
    "endTime": "2026-11-21T10:02:00+08:00",
    "recordedAt": "2026-11-21T10:02:01+08:00",
    "quality": "observed"
  }
]
```

以 sourceId 和 recordId 的組合識別重送或更正。同時段的多來源資料應由接收端選擇一個來源，避免重複計量。格式不提供背景資料完整性的保證。
