# 手書き描画ライブラリ

`:drawing` は Android 10（API 29）以降で使える Android ライブラリです。Jetpack Ink を使ったペン入力の予測と復旧、筆圧ブラシと蛍光ペン、確定した線の描画、直線補正、消しゴムの当たり判定を提供します。

## 組み込み方

Gradle プロジェクトにこのディレクトリを含めて、利用するモジュールに `implementation(project(":drawing"))` を追加します。別のプロジェクトに組み込む場合は、ルートで Android Library（このリポジトリでは AGP 8.9.2）と Kotlin Android（2.1.20）のプラグインを宣言し、Google Maven と Maven Central を有効にしてください。ソースは JDK 17 と Android SDK 35 でビルドします。

- `LiveInk` にタッチイベントを受け取る View を渡し、`view` を確定済みの線を描くレイヤーの上に置きます。必要に応じて、追加後に `view.eagerInit()` で描画を準備します。
- 対象のペンのイベントを `recordForPrediction` に渡し、接触開始・移動・終了時に `start`・`add`・`finish` を呼びます。`start` にはブラシ、View 座標を描画座標へ変換する行列、操作を識別するトークンを渡します。
- 完了コールバックで受け取った Jetpack Ink の `Stroke` を保持し、その場で確定済みレイヤーを再描画します。`InkStrokeRenderer.create()` で描画でき、表示用に形状をキャッシュする場合は `createForDisplay()` を使います。
- 入力を取り消すときは `cancelActiveStroke`、画面を離れるときは `settle` を呼びます。`settle` は描き終えて描画レイヤーからの受け渡しを待つ線を回収し、途中の入力を破棄します。

ノートごとの保存、画像・用紙の合成、レイヤーの表示範囲とキャッシュ方針は利用側が管理します。Misq notes での接続例は [InkCanvasView.kt](../app/src/main/java/jp/handwriting/note/editor/InkCanvasView.kt) です。

## ビルド

```powershell
.\gradlew.bat :drawing:assembleRelease :drawing:testDebugUnitTest
```

AAR は `drawing/build/outputs/aar/drawing-release.aar` に生成されます。AAR を直接配布する場合は、利用側でも [build.gradle.kts](build.gradle.kts) の AndroidX 依存関係を解決してください。
