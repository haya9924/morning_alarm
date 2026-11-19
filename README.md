# モーニングアラーム

Android向けのアラームと、停止後に表示する「朝のまとめ」の初期実装です。開発計画は [PLAN.md](PLAN.md) を参照してください。標準時計アプリの完全な代替ではなく、実機での動作確認が必要な開発版です。

## セットアップ

Android Studio、JDK 17以上、Android SDK（必要なバージョンは `app/build.gradle.kts` を参照）が必要です。SDK Managerで必要なSDKをインストールし、Android Studioでこのディレクトリを開いてください。コマンドラインの場合は `ANDROID_HOME` をSDKディレクトリに設定します。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使い方

- `+` から時刻とラベルを指定すると毎日のアラームを追加できます。スイッチで有効・無効を切り替え、ゴミ箱ボタンで削除できます。
- アラーム画面で停止すると朝のまとめが開きます。ホーム画面の「朝のまとめ」からHTMLを編集、「プレビュー」で確認できます。`{{date}}` は表示時の日付に置き換わります。
- 指示に従って通知、正確なアラーム、全画面通知を許可してください。OSや端末の設定により、全画面表示や正確な時刻の発火が保証されるわけではありません。特に通知や正確なアラームの権限がない場合、期待どおり鳴らない・遅れる可能性があります。重要な予定には実機での十分な検証を行ってください。

## 朝のまとめの拡張

アプリ内エディターでHTMLを編集できます。安全のため、表示用WebViewのJavaScript・ネットワーク通信・ファイルアクセスは無効です。手動編集したHTMLは端末内に保存され、エディター内の「プレビュー」では常に手動版を確認できます。

### Hermesから更新する（ADB不要）

1. Hermesと同じホスト・ユーザーで `python3 tools/hermes_summary.py rotate-token` を初回に1度実行します。生成された `~/.local/state/morning-alarm-summary/token` の値を、チャットやログに貼らず端末へ安全に渡します。
2. ホストで `python3 tools/hermes_summary.py serve` を継続実行します（Python 3.9以降、待ち受けは `127.0.0.1:3647` のみ）。端末から届く**非公開HTTPS**リバースプロキシ（例: Tailscale Serve）で `/v1/morning-summary` をこのポートへ転送し、`Authorization` ヘッダーを保持してください。Funnel等で公開したり、既存のルートを上書きしたりしないでください。
3. Androidアプリの「朝のまとめ」編集 →「Hermes連携」で `https://<ホスト>/v1/morning-summary` と読み取りトークンを入力し、「設定して同期」を選びます。
4. 以後、Hermesはホストで `python3 tools/hermes_summary.py publish /path/to/summary.html` を実行するだけです。UTF-8のHTML（256 KiB以下）が置き換わります。`{{date}}` は端末の表示時刻に展開されます。

端末はまとめ画面を開いたときに自動取得します。取得に失敗した場合は前回取得した内容、まだ一度も取得できていなければ手動版を表示します。「今すぐ同期」でも確認できます。バックグラウンドで定期同期したり、端末へ直接プッシュしたりはしません。「連携を解除」で手動版に戻ります。漏えい時はホストの `revoke-token` で読み取りを停止し、`rotate-token` で再発行して端末に再設定してください。失効してもオフライン表示用の端末キャッシュは自動削除されません。

## Hermes / ADB連携（実験的）

ADBで端末にアクセスできる環境向けに、明示的BroadcastReceiverを用意しています。ネットワークAPIではなく、Hermesから直接Androidに接続する機能でもありません。受信には `android.permission.DUMP` が必要で、通常のサードパーティアプリからは操作できない設計です。ADB shellの権限はOS・端末依存のため、実機での確認が必要です。出力はordered broadcastの結果JSONとlogcatの `HermesCommand` タグで確認できます。

```sh
COMPONENT=com.example.morningalarm/.hermes.HermesCommandReceiver
PREFIX=com.example.morningalarm.hermes.action
adb shell am broadcast -n "$COMPONENT" -a "$PREFIX.PING"
adb shell am broadcast -n "$COMPONENT" -a "$PREFIX.LIST_ALARMS"
adb shell am broadcast -n "$COMPONENT" -a "$PREFIX.CREATE_ALARM" --ei hour 7 --ei minute 30 --es label '朝'
adb shell am broadcast -n "$COMPONENT" -a "$PREFIX.SET_ENABLED" --el id 1 --ez enabled false
adb shell am broadcast -n "$COMPONENT" -a "$PREFIX.DELETE_ALARM" --el id 1
```

IDは一覧・作成結果を使ってください。このADBインターフェースはアラーム操作専用です。朝のまとめの更新には上記のHTTPS連携を使います。

## 既知の制約

- 繰り返しは毎日のみです。曜日指定、スヌーズ、音源選択、既存アラームの時刻編集などは未実装です。
- 鳴動はフォアグラウンドサービスで行いますが、OSや端末によってサービス起動が制限される場合の代替鳴動は未実装です。実機での確認もできていないため、**重要な目覚ましとしては使用しないでください。**
- 通知から停止した場合、アラーム画面が表示されていなければ朝のまとめは自動表示されません。
- 正確なアラーム権限を取り消すと、OSが登録済みアラームを削除することがあります。権限を取り消した直後に近似アラームへ自動移行することは保証されません。アプリを再度開き、権限と登録状態を確認してください。
- Androidの省電力設定、通知権限、正確なアラーム権限、全画面通知権限、およびメーカー独自の制限が動作に影響します。再起動後の再登録を含め実機で確認してください。
- 朝のまとめはHTMLを自由に編集できますが、スクリプトや外部リソースは利用できません。交通・カレンダー連携もまだありません。
