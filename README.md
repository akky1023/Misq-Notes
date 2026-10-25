# Misq notes

Xiaomi Pad 7 & Xiaomi Focus Pen向けの手書きノートアプリです．

ノートは端末内に自動保存します．インターネット権限は原則不要です．バックアップはsyncthingとかを使ってください．

## 開発について

Kotlin，Jetpack Compose，Jetpack Inkを使用しています．

- [ビルド・テスト・署名](BUILD.md)
- [端末内の保存形式と復旧処理](docs/storage-format.md)
- [外部保存の形式と移行・競合処理](docs/packed-storage.md)
- [実機での基本的な確認手順](docs/production-validation.md)

## ライセンス

MIT