# Binance Pro — Android Trading Chart App

TradingView-style Android candlestick charting and technical analysis app powered by Binance USD-M Futures & Bybit Linear public market data (REST + live WebSocket).

## Features
- **Watchlist Tab**: Live USD-M Futures pairs, search, favorites, gainers/losers filter, volume/price/24h% sorting.
- **Full-Screen Chart Tab**: Native Jetpack Compose Canvas chart with TradingView-style pan/fling, independent focal-point pinch zoom, double-tap reset, crosshair, bar countdown timer, and Last / Mark / Index price source switching.
- **Timeframes**: Presets (`1m` to `1M`), favorites bar, custom timeframe builder, and preserved zoom scale when switching timeframes.
- **Drawing Tools**: Horizontal Line, Trendline, Rectangle, Price Range calculator, with lock, style, alert linking, and delete controls.
- **Background Price Alerts**: Tap anywhere on the chart to quick-add an alert via `+`, drag alert lines, and receive background notifications with sound & vibration.
- **Bar Replay**: Step-by-step or timed historical candle playback.

---

## Upload to GitHub & Build Installable APK

### 1. Push Code to GitHub
Run these commands inside the project root folder:

```bash
git init
git add .
git commit -m "Initial commit: Binance Pro trading chart app"
git branch -M main
git remote add origin https://github.com/surajali1026/binance.git
git push -u origin main
```

*(If `origin` already exists, run `git remote set-url origin https://github.com/surajali1026/binance.git` before pushing.)*

### 2. Download & Install the APK from GitHub Actions
1. Open **https://github.com/surajali1026/binance/actions** on GitHub.
2. Open the latest **Android CI & APK Build** run (triggers automatically on every push to `main`, or click **Run workflow**).
3. Scroll to **Artifacts** and download **`binance-pro-debug-apk`**.
4. Unzip the downloaded file to get **`BinancePro-debug.apk`** and install it on your Android phone (Android 7.0+).

### 3. Optional: Signed Release APK & GitHub Releases
To also produce a production-signed **`BinancePro-release.apk`**, add these GitHub repository secrets (**Settings → Secrets and variables → Actions**):
- `RELEASE_KEYSTORE_BASE64`: Base64 string of your `.jks` keystore (`base64 -w 0 my-upload-key.jks`)
- `STORE_PASSWORD`: Keystore password
- `KEY_PASSWORD`: Key password for alias `upload`

Push a version tag (for example `git tag v1.0.0 && git push origin v1.0.0`) to automatically create a GitHub Release with the `.apk` attached.
