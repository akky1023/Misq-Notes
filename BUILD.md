# ビルドガイド

## 必要な環境

- Android Studio
- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools 35.0.0

Gradle Wrapperを同梱しています。Gradle本体や依存ライブラリの初回取得には、開発PCのインターネット接続が必要です。

リポジトリをAndroid Studioで開き、SDKの場所を設定してください。コマンドラインでビルドする場合も、`JAVA_HOME`とSDKの場所（`local.properties`の`sdk.dir`など）を設定します。`local.properties`はGitへ追加しません。

## ビルドとテスト

リポジトリのルートで実行します。以下はWindows PowerShellの例です。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
```

macOS／Linuxでは、`.\gradlew.bat`を`./gradlew`に置き換えてください。

接続したAndroid端末で実機テストを行う場合は、USBデバッグを有効にして実行します。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

APKの出力先とアプリの識別情報は次のとおりです。本番版と検証版は別のアプリとしてインストールされます。

| 種類 | applicationId | アプリ名 | APKの出力先 |
| --- | --- | --- | --- |
| 本番版（release） | `net.neomenia.misqnotes` | Misq notes | `app/build/outputs/apk/release/` |
| 検証版（debug） | `net.neomenia.misqnotes.probe` | Misq notes テスト版 / Misq notes Test | `app/build/outputs/apk/debug/app-debug.apk` |

単体テストやビルドの成功だけでは、実機のペン入力・物理ボタン・描画性能を確認できません。端末での確認には[基本的な確認手順](docs/production-validation.md)も参照してください。

## 本番APKと署名

```powershell
.\gradlew.bat :app:assembleRelease
```

現在のビルド設定は、ローカルの`.signing/signing.properties`がある場合に、`.signing/handwriting.p12`を使って本番APKへ署名します。プロパティは`storePassword`、鍵のエイリアスは`handwriting`、形式はPKCS12です。

署名設定がない環境では未署名のAPKが生成されるため、インストール・配布前に自身の鍵で署名してください。既存アプリへの上書き更新には、同じapplicationIdと同じ署名鍵が必要です。

署名鍵・パスワード・ローカル設定はGitへ追加しないでください。署名済みAPKの出力先は`app/build/outputs/apk/release/app-release.apk`です。

## 使用技術

バージョンは[ルートのビルド設定](build.gradle.kts)、[アプリのビルド設定](app/build.gradle.kts)、[Gradle Wrapper設定](gradle/wrapper/gradle-wrapper.properties)で管理しています。

- Gradle 8.11.1 / Android Gradle Plugin 8.9.2
- Kotlin 2.1.20 / JVM 17
- Jetpack Compose BOM 2025.04.01
- Jetpack Ink 1.0.0 / MotionPrediction 1.0.0

[READMEへ戻る](README.md)
