# 全ノート一覧の起動時間

2026-11-09、Android実機（モデル2410CRP4CG）で検証。

## 実測結果

本番コピーの全12冊で、Activity起動開始から一覧の描画フレーム確定まで1,408〜1,862 msだった。
3回とも3,000 ms以内の検証に成功した。

| 本番コピーの独立した起動 | 一覧データ準備完了 | 一覧フレーム確定 |
| --- | ---: | ---: |
| 1 | 1,720 ms | 1,862 ms |
| 2 | 1,311 ms | 1,414 ms |
| 3 | 1,312 ms | 1,408 ms |

本番コピーは設定も含めて独立領域へ展開し、本番の外部保存先には接続しない条件で計測した。
起動前の展開・ハッシュ照合は計測に含めない。

検証アプリに保存済みの全2冊で、起動時の一覧読み込みは変更前8,695 ms。
変更後はActivity起動開始から一覧の描画フレーム確定まで1,319〜1,349 msだった。

| 独立した起動 | 一覧データ準備完了 | 一覧フレーム確定 |
| --- | ---: | ---: |
| 1 | 1,255 ms | 1,349 ms |
| 2 | 1,245 ms | 1,341 ms |
| 3 | 1,226 ms | 1,319 ms |

変更前はViewModel起動から操作可能になるまでの計測で、Activityの描画時間を含まない。
変更後は同じ一覧データの準備完了に加え、実際のCompose一覧画面のフレーム確定を計測した。
各回で3,000 ms以内をassertし、一覧のID、タイトル、更新日時、筆跡数が保存内容と一致することも確認した。
計測はInstrumentationのプロセス起動後からで、OSによるプロセス生成時間は含まない。
個人データを含めない計測ログはGit管理外の `captures/note-list-20261109/` に保存。

## 変更内容

- 端末内の保存済み全ノートと設定を読み込み、外部同期を待たず一覧を表示する。
- 外部同期は一覧表示後に開始し、同期中の表示を出す。外部の更新は同期後に一覧へ反映する。
- 同期中に開いたノートと、保存待ちの設定変更を保護する。同期終了時にも他の操作の待機状態を解除しない。
- 保存形式、同期時の検証、競合処理、原本の保持方針は継続する。

この時間は保存済み一覧の表示時間であり、外部ストレージの全同期完了時間ではない。
他端末で新規作成した未取得のノートなどは、外部同期完了後に表示される。

## 検証

- 単体テスト262件成功、1件スキップ。
- `Format3OnlyStorageTest` と `Format3IntegrationTest` の実機16件成功。
- 外部読み取りを意図的に停止しても3秒以内に保存済み一覧が利用でき、解放後には追加ノートが表示されることを確認。
- 同期中の設定変更の保持、ノートを開いたままの同期、閉じた後の追加ノート反映、保存・再起動・競合解決を確認。
- debug APKとAndroidTest APKのビルド成功、Lintは0 errors / 8 warnings。

再計測（検証アプリの既存データを保持する）：

```powershell
adb shell am instrument -w -e class jp.handwriting.note.storage.NoteListStartupBenchmarkTest -e measureInstalledLibrary true -e maxListStartupMs 3000 net.neomenia.misqnotes.probe.tests/androidx.test.runner.AndroidJUnitRunner
```

## 本番データでの追加計測

本番の読み取り専用スナップショットは実機内に保持した。
150,837ファイルについて、作成前後の内容のハッシュとファイル一覧が変化していないことを確認済み。
スナップショットは922,092,439 bytes、SHA-256は `7f82750bf7fbd6fe6c83e4304fb2164c3e6448171467007594c31a53ef5e9f96`。

ユーザーの明示的な許可を受け、実機内の検証アプリのexternal filesへ `note-list-production-snapshot.zip` としてコピーした。
PCへ本番データは移送していない。
上記の3回の本番コピー計測後にも、原本バックアップと入力コピーのSHA-256が上記の値と一致することを確認した。
このテストは必要な現行ストアと設定だけを独立したディレクトリへ展開し、スナップショット内のハッシュ一覧と照合する。
原本、入力アーカイブ、展開したノートはいずれも削除しない。

```powershell
adb shell am instrument -w -e class jp.handwriting.note.storage.NoteListStartupBenchmarkTest -e measureProductionSnapshot true -e maxListStartupMs 3000 net.neomenia.misqnotes.probe.tests/androidx.test.runner.AndroidJUnitRunner
```
