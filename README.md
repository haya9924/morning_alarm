# モーニングアラーム

Android向けのアラームと、停止後に表示する「朝のまとめ」の初期実装です。開発計画は [PLAN.md](PLAN.md) を参照してください。標準時計アプリの完全な代替ではなく、実機での動作確認が必要な開発版です。

## セットアップ

Android Studio、JDK 17以上、Android SDK（必要なバージョンは `app/build.gradle.kts` を参照）が必要です。SDK Managerで必要なSDKをインストールし、Android Studioでこのディレクトリを開いてください。コマンドラインの場合は `ANDROID_HOME` をSDKディレクトリに設定します。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions の **Android APK** でも、テストを通過したデバッグAPKを `morning-alarm-debug-apk` artifact からダウンロードできます。これは検証用で、別の署名でインストール済みのアプリに上書きできません。アンインストールすると端末内のアラーム設定などが失われるため、既存データを残したい場合は同じ署名のAPKを使ってください。

## 使い方

- `+` から時刻・ラベル・繰り返す曜日を指定してアラームを追加できます。スイッチで有効・無効を切り替え、ゴミ箱ボタンで削除できます。各アラームの操作から、次回のみのスキップや時刻変更も設定できます。
- アラーム画面では「5分スヌーズ」で一時停止できます。完全停止には、緑の開始点から図形全体を一筆でなぞる必要があります。スヌーズ待機中も画面または通知から図形を開いて解除できます。完全停止に成功すると朝のまとめが開きます。ホーム画面の「朝のまとめ」からHTMLを編集、「プレビュー」で確認できます。`{{date}}` は表示時の日付に置き換わります。
- 指示に従って通知、正確なアラーム、全画面通知を許可してください。OSや端末の設定により、全画面表示や正確な時刻の発火が保証されるわけではありません。特に通知や正確なアラームの権限がない場合、期待どおり鳴らない・遅れる可能性があります。重要な予定には実機での十分な検証を行ってください。

## 図形判定の設定・朝アラームのプレビュー

ホーム画面の「図形の判定設定」で、全アラーム共通の判定を変更できます。

- **やさしい（初期値）**: 線からのズレを従来より多く許容します。
- **従来（厳しめ）**: 以前と同じ許容幅です。

どちらも一筆・順方向・全周のトレースが必要です。設定は端末内に保存され、実際の停止判定にも適用されます。

「朝アラームをプレビュー」で、実画面と共通の波アニメーションと図形入力を試せます。音は鳴らず、スヌーズは無効です。トレース成功はプレビュー内だけの表示で、実際のアラーム・スヌーズ予約・通知は操作しません。閉じるボタンまたは戻る操作で終了できます。実機での操作感は未検証です。

## 次のアラーム予定ウィジェット

有効なモーニングアラームのうち、**次に鳴る予定を1件**表示します。今日に予定がなくても明日以降の日時を表示します。曜日指定、次回スキップ・時刻変更、スヌーズを反映します。端末内の他のアプリのアラームは表示しません。ロック画面に出るのは日時だけで、予定のラベルや操作ボタンは表示しません。

- **LockStarなし:** Android標準のロック画面ウィジェットに対応する端末では、ロック画面のウィジェットを有効にし、標準ピッカーで「次のアラーム予定」を追加してください。Pixel Tabletや、この機能を有効にしたAndroid 16 QPR1以降の端末が対象です。Androidのバージョンだけでなく、メーカーが標準機能を提供している必要があります。
- **Galaxy:** One UIの標準ピッカーに表示されない機種では、アプリ側から掲載を強制できません。対応するGalaxyでは従来どおりGood Lock → LockStarのロック画面編集から追加できます。AODへの表示は保証されません。
- **ホーム画面:** 長押し → ウィジェット → モーニングアラーム →「次のアラーム予定」。既に配置した「今日のアラーム予定」は、同じウィジェットのまま新しい表示に更新されます。

アラームの変更・発火、スヌーズ変更、日付・時刻・タイムゾーン変更に応じて表示を更新します。予定時刻や日付境界でも補助更新しますが、省電力制御やホストの挙動によって反映が遅れる場合があります。Galaxy/LockStarの実機動作は未検証です。

調査資料:
- [Samsung: One UI 7以降のロック画面・AODウィジェット](https://www.samsung.com/ae/support/mobile-devices/how-to-add-widgets-on-the-lock-screen-and-aod-in-one-ui-7-or-later/)
- [Android Developers: ロック画面ウィジェットFAQ](https://android-developers.googleblog.com/2025/03/widgets-on-lock-screen-faq.html)
- [LockStarのサードパーティウィジェット対応に関する記事](https://sammyguru.com/lockstar-hits-one-ui-7-heres-whats-new/)

## 朝のまとめの拡張

アプリ内エディターでHTMLを編集できます。安全のため、表示用WebViewのJavaScript・ネットワーク通信・ファイルアクセスは無効です。手動編集したHTMLは端末内に保存され、エディター内の「プレビュー」では常に手動版を確認できます。

### Hermesから更新する（ADB不要）

1. Hermesと同じホスト・ユーザーで `python3 tools/hermes_summary.py rotate-token` を初回に1度実行します。生成された `~/.local/state/morning-alarm-summary/token` の値を、チャットやログに貼らず端末へ安全に渡します。
2. ホストで `python3 tools/hermes_summary.py serve` を継続実行します（Python 3.9以降、既定の待ち受けは `127.0.0.1:3647` のみ）。接続方法は次のどちらかです。
   - **非公開HTTPSリバースプロキシ（従来）**: 端末から届く非公開HTTPS（例: Tailscale Serve）で `/v1/morning-summary` を `127.0.0.1:3647` へ転送し、`Authorization` ヘッダーを保持します。Funnel等で公開したり、既存のルートを上書きしたりしないでください。
   - **Tailscale経由の直接HTTP**: `python3 tools/hermes_summary.py serve --host <Tailscale IP>` で、TailscaleのIP（`100.64.0.0/10` または `fd7a:115c:a1e0::/48`）に直接待ち受けます。`--host` はループバックかTailscaleのIPリテラルのみ受け付け、`0.0.0.0`・LAN/公開IP・ホスト名は拒否します。TLS終端はなく、通信はTailscaleの暗号化トンネル内だけです。Funnelや `0.0.0.0` で外部へ公開しないでください。
3. Androidアプリの「朝のまとめ」編集 →「Hermes連携」でURLと読み取りトークンを入力し、「設定して同期」を選びます。
   - HTTPSは任意のホスト: `https://<ホスト>/v1/morning-summary`
   - HTTPは**Tailscale限定**: `http://100.x.x.x:3647/v1/morning-summary` または `http://<ホスト>.ts.net:3647/v1/morning-summary`（IPv6は `http://[fd7a:115c:a1e0::1]:3647/v1/morning-summary`）
   - `http://` はTailscaleのIPv4（`100.64.0.0/10`）・IPv6（`fd7a:115c:a1e0::/48`）・`*.ts.net` 以外はアプリが拒否します。既定ポートはHTTPSが443、HTTPが80で省略されます。
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

IDは一覧・作成結果を使ってください。このADBインターフェースはアラーム操作専用です。朝のまとめの更新には上記のHTTPS／Tailscale HTTP連携を使います。

## 既知の制約

- スヌーズは5分固定で、Android 12以降では正確なアラームの許可が必要です。許可がない場合は鳴動を継続し、スヌーズを拒否します。スヌーズ中に元のアラームを削除・無効化すると再鳴動できません。
- 図形入力は指でなぞる操作が必須です。運動・視覚面でのアクセシビリティ対応は未整備です。OSの強制停止、電源断などを防ぐ仕組みではありません。
- 音源選択、アプリ内での音量・振動設定は未実装です。端末の既定アラーム音とアラーム音量を使用し、振動します。
- 鳴動はフォアグラウンドサービスで行いますが、OSや端末によってサービス起動が制限される場合の代替鳴動は未実装です。実機での確認もできていないため、**重要な目覚ましとしては使用しないでください。**
- 通知の停止操作も図形画面を開きます。タップだけでは完全停止しません。スヌーズの再鳴動、再起動後の復元、ロック画面でのトレースと波形描画は実機未検証です。
- 正確なアラーム権限を取り消すと、OSが登録済みアラームを削除することがあります。権限を取り消した直後に近似アラームへ自動移行することは保証されません。アプリを再度開き、権限と登録状態を確認してください。
- Androidの省電力設定、通知権限、正確なアラーム権限、全画面通知権限、およびメーカー独自の制限が動作に影響します。再起動後の再登録を含め実機で確認してください。
- 朝のまとめはHTMLを自由に編集できますが、スクリプトや外部リソースは利用できません。交通・カレンダー連携もまだありません。
