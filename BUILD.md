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

本番APKは`.signing/handwriting.p12`で署名します。形式はPKCS12、鍵のエイリアスは`handwriting`です。パスワードは環境変数`ANDROID_KEYSTORE_PASSWORD`を優先し、未設定の場合はローカルの`.signing/signing.properties`の`storePassword`を使います。

署名設定がない環境では未署名のAPKが生成されるため、インストール・配布前に自身の鍵で署名してください。既存アプリへの上書き更新には、同じapplicationIdと同じ署名鍵が必要です。

署名鍵・パスワード・ローカル設定はGitへ追加しないでください。署名済みAPKの出力先は`app/build/outputs/apk/release/app-release.apk`です。

## GitHubでの自動ビルドとリリース

リポジトリは[akky1023/Misq-Notes](https://github.com/akky1023/Misq-Notes)です。デフォルトブランチは`main`、開発ブランチは`develop`です。

- `main`・`develop`へのpushとPRで、単体テスト・lint・検証APK・本番APKのビルドを実行します。
- 検証APKはActionsの`Misq-notes-test-*` artifactからダウンロードできます。本番署名鍵は通常のCIには渡しません。
- `develop`から`main`への取り込みは、`fix: ...`や`feat: ...`のコミットが`main`に残る方法で行います。Squash mergeではPRタイトルも同じ形式にします。
- `main`へのpushでrelease-pleaseがバージョン・変更履歴を更新するリリースPRを作成します。
- リリースPRをマージすると`vX.Y.Z`タグとGitHub Releaseを作成し、テスト・lint・本番署名・証明書検証後にAPKと`SHA256SUMS`を添付します。

バージョンは`version.txt`で管理し、release-pleaseが更新します。Androidの`versionCode`は`major × 1,000,000 + minor × 1,000 + patch`から求めます（minorとpatchは999以下）。`0.1.3`は`1003`となり、従来の`6`から上書き更新できます。`.release-please-manifest.json`はrelease-pleaseが追跡する直近バージョンです。

### GitHub側の初期設定

リポジトリのSettings → Secrets and variables → Actionsに、次のRepository secretsを登録します。

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 既存の`.signing/handwriting.p12`をBase64化した値 |
| `ANDROID_KEYSTORE_PASSWORD` | 同じ鍵の`storePassword` |

既存のインストールへ更新できるよう、署名鍵を作り直さないでください。リリース時には証明書のSHA-256が`ec56f22a99c5b4ecbce8668b9c5cc71df365cb18bcac49e1fa1f4e5e3a177275`であることも検証します。Secretsの値をコミットやログへ記載しないでください。

Settings → Actions → Generalで「Allow GitHub Actions to create and approve pull requests」を有効にします。workflow自身が必要な`contents`・`issues`・`pull-requests`の書き込み権限を指定します。

release-pleaseは標準の`GITHUB_TOKEN`を使い、本番ビルドは同じworkflowの後続jobで実行します。このため、リリースイベントで別workflowを起動するためのPATは不要です。Botが作成したリリースPRのCIには、GitHub側で実行承認が必要になる場合があります。PRの差分を確認し、Actionsで対象の実行を承認してください。マージ後も`main` CIと本番ビルドjobの両方で検証します。

リリース後は`main`を`develop`へ取り込み、次の開発へバージョン更新を引き継いでください。

### APK添付に失敗した場合

Secrets不足や一時的な障害でAPKの作成・添付に失敗した場合は、原因を解消してActions → Release → Run workflowを開き、ブランチ`main`と既存のタグ（例：`v0.1.4`）を指定します。対象タグのソースを再ビルドして同じReleaseへ添付します。タグ欄を空にするとリリースPRの更新だけを実行します。

## 使用技術

バージョンは[ルートのビルド設定](build.gradle.kts)、[アプリのビルド設定](app/build.gradle.kts)、[Gradle Wrapper設定](gradle/wrapper/gradle-wrapper.properties)で管理しています。

- Gradle 8.11.1 / Android Gradle Plugin 8.9.2
- Kotlin 2.1.20 / JVM 17
- Jetpack Compose BOM 2025.04.01
- Jetpack Ink 1.0.0 / MotionPrediction 1.0.0

[READMEへ戻る](README.md)
