# デッドコード監査（2026-11-07）

対象ブランチ: `develop-deadcode`（`develop`から作成）。

## 確認方法

- `app/src/main`・`debug`のKotlin宣言を抽出し、単体・実機・共有テストを含む参照先を検索。
- private宣言はファイル内の参照も確認し、Manifest登録、Androidのoverride、プロパティ委譲の暗黙呼び出しを区別。
- リソースの参照、API別の選択条件、動的リソース検索・リフレクションの有無を確認。
- lintRelease、検証APKビルド、単体テスト、関連する実機テストで検証。

静的検索と関連テストによる監査であり、すべての実行条件でデッドコードが存在しないことを証明するものではない。

## 明らかな未使用箇所の整理

| 箇所 | 根拠・変更 |
| --- | --- |
| `PenStyleDialogs.IntegerInput` | 呼び出しのないprivate UIと未使用importを削除。 |
| `MergeReviewSession.sideValues`・`b` | メソッド・getterとも参照がなく削除。 |
| `InkCanvasView.LOGICAL_WIDTH`・`ERASER_RADIUS` | テストを含め参照がなく削除。 |
| `operation_syncing` | リソース参照と動的検索がなく、日本語・英語の文言を削除。 |
| 旧ランチャーアイコン | minSdk 29ではAPI 26以降の適応型が常に選択されるため、適応型を共通ディレクトリへ移し、旧fallbackと専用drawableを削除。 |
| 未使用import | 起動画面、一覧、保存設定、ツール設定、保存処理、編集画面で参照のないimportを削除。委譲に必要な`getValue`は維持。 |

## ユーザー承認後の整理・効率化

| 箇所 | 変更 |
| --- | --- |
| マージ計画の二重構築 | `StrokeMergePlan`を廃止し、保存前の元データ検査とID割り当ても`MultiMergePlan.indexes.first()`へ統一。旧APIのテストは現行APIへ移行し、確認済みステップを明示して保存。 |
| 編集画面のプレビューハンドル | `Modifier.offset`をラムダ形式にし、縦位置の状態をレイアウト時に参照。 |
| マージ最終画面のプレビューハンドル | 同様に縦位置の状態をレイアウト時に参照。 |
| `PenStyle.fromRgb` | 現在の色入力は`ColorText`を使用するため削除。`withRgb`による透明度保持のテストは維持。 |
| `PressureCurve.asControlBezier` | 現在の編集UIは`asHandleCurve`を使用するため旧変換ヘルパーを削除。保存済み`BEZIER_CONTROLS`のcodec、計算処理とテストは維持。 |

マージ計画の構築回数は2回から1回になる。位置計算を含め、実行時間やFPSの改善率は計測していない。

## 維持した処理

- Manifestに登録された検証ActivityとDocumentsProviderは、通常画面から参照がなくてもエントリーポイントとして維持。
- バックアップ復元、外部オブジェクト検証、オブジェクト列挙、履歴時刻調整は回復・整合性・テスト用途の参照があるため維持。
- 旧筆圧保存形式の読み込み、既存設定の移行、現行モードへの変換は維持。
- 残るlint指摘は単色アイコン、ツール用コンストラクタ、端末機能指定、KTX置換、RTL配置であり、この監査では変更していない。

## 検証結果

- 検証APKビルド・実機テストコードのコンパイル成功。
- 単体テスト263件: 262件成功、1件スキップ、失敗・エラー0件。
- lintRelease成功。未使用リソースと状態依存offsetの指摘は解消。
- 実機テスト18件成功: `MergeReviewDeviceTest` 13件、`InkSurfaceSizingTest` 1件、`NotePreviewTest` 4件。
- 実機テストはGradleのUTP依存がオフラインキャッシュにないため、ビルド済みAPKをADBで更新してAndroidJUnitRunnerを直接実行。旧ヘルパー2件の追加削除前に実行し、追加削除後は単体テスト・ビルド・lintで確認。
- 本番アプリの更新、push、developへのマージは行っていない。
