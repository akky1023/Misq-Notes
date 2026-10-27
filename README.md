# Misq notes

Xiaomi Pad 7 & Xiaomi Focus Pen向けの手書きノートアプリです．

ノートは端末内に自動保存します．インターネット権限は原則不要です．バックアップはsyncthingとかを使ってください．

左上のプレビューボタンでノート全体を確認でき，青色の枠をドラッグすると表示位置を移動できます．
プレビューは表示範囲に追従してスクロールし，総合設定のノート設定で表示位置を左右に変更できます（初期値は左）．
画面端の細く薄いバーは現在の表示位置を示します．

## 開発について

Kotlin，Jetpack Compose，Jetpack Inkを使用しています．

- [ビルド・テスト・署名](BUILD.md)
- [端末内の保存形式と復旧処理](docs/storage-format.md)
- [1冊1ファイルの保存形式（テスト版）](docs/misq-storage.md)
- [外部保存の形式と移行・競合処理](docs/packed-storage.md)
- [実機での基本的な確認手順](docs/production-validation.md)

## ライセンス

MIT
