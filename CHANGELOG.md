# Changelog

## [0.5.0](https://github.com/akky1023/Misq-Notes/compare/v0.4.0...v0.5.0) (2026-11-04)


### ⚠ BREAKING CHANGES

* 保存形式を変更

### 機能追加

* 保存形式を変更 ([9ace591](https://github.com/akky1023/Misq-Notes/commit/9ace5911fc66e6972984ce83691e752e87fb3a9c))


### 修正

* いらない保存処理をカット ([0cca87f](https://github.com/akky1023/Misq-Notes/commit/0cca87f30b44aebcbf10b46cc8eb6b66be9f637f))
* インデックスの自動整理を追加 ([ad089da](https://github.com/akky1023/Misq-Notes/commit/ad089da253862e78a471abe22b08d63a4ef68b71))
* スクロール・ピンチできない問題がでてきたので修正 ([0d6b655](https://github.com/akky1023/Misq-Notes/commit/0d6b655df22e68147043d6b8594e2d0f6420b98a))
* できるだけ読み込み時間を短くする工夫 ([de4f1cb](https://github.com/akky1023/Misq-Notes/commit/de4f1cb430dfaa2bf7ba4c0962a7c6c04abbb43b))
* パフォーマンスを大幅に改善 ([ec22682](https://github.com/akky1023/Misq-Notes/commit/ec22682e0ec501da9a880d45bf79f0b93b0e23f3))
* プレビュの動作を改善 ([b60acb5](https://github.com/akky1023/Misq-Notes/commit/b60acb57343abb5befb3248dbb7ece5f691a4eff))
* 保存・読み込み処理を高速化 ([4c5de9c](https://github.com/akky1023/Misq-Notes/commit/4c5de9cfc167fddfd2e281650b3b14d71c30b11a))
* 削除・マージできない問題 ([4add5f5](https://github.com/akky1023/Misq-Notes/commit/4add5f5f18f503312bc443de2eddca8fee52472a))
* 外部保存で待たされる問題を修正 ([48d0205](https://github.com/akky1023/Misq-Notes/commit/48d02056058b1df7781aa04a79aa44b7fd8be88a))
* 拡大縮小がもうちょっとスムーズになるかも ([f8c557f](https://github.com/akky1023/Misq-Notes/commit/f8c557fde9d86cced80f215021f11e1c6dd66124))
* 現在処理をスピナーの下に書くようにした ([e2baad6](https://github.com/akky1023/Misq-Notes/commit/e2baad662b959e064582b6ed647100066e6bc7ea))
* 筆跡端に小さなコブがつく問題を修正 ([32e64ae](https://github.com/akky1023/Misq-Notes/commit/32e64ae2f18cc5a8947c386ad7537fbd7511638b))


### 性能改善

* スクロール先の輪郭を継続して先読み ([8a3a61a](https://github.com/akky1023/Misq-Notes/commit/8a3a61ac1e836b25de5c5ee61bd6e75addc29f01))
* 内容不変時のプレビュー再生成を省略 ([02765e7](https://github.com/akky1023/Misq-Notes/commit/02765e7948e36d7081177ad6a4174a8b8d33974b))
* 周辺キャッシュの再集計を間引く ([d201720](https://github.com/akky1023/Misq-Notes/commit/d201720d1eaa90a1d95982c3eba8c232fa2ef703))
* 画面端の筆跡の描画結果を再利用 ([dabd2d4](https://github.com/akky1023/Misq-Notes/commit/dabd2d47529bfc7f5a391ecc5bbe7889e806dac1))
* 筆跡パックの読み取りをバッファ化 ([bdf813a](https://github.com/akky1023/Misq-Notes/commit/bdf813a3cacecd3f7bf9279b924642c0af74ebd5))
* 筆跡一覧のページ探索を削減 ([f17c5e9](https://github.com/akky1023/Misq-Notes/commit/f17c5e92d59e9273a810b781b9afd7f73aedb081))
* 表示候補検索の重複処理を削減 ([2408321](https://github.com/akky1023/Misq-Notes/commit/240832189291fd8329e0522f3171bc57c5449d3b))
* 表示用の輪郭生成をまとめて再利用 ([b621ba8](https://github.com/akky1023/Misq-Notes/commit/b621ba84e4109eda4e3a83420979feec0e410f1f))

## [0.4.0](https://github.com/akky1023/Misq-Notes/compare/v0.3.1...v0.4.0) (2026-11-02)


### 機能追加

* マージ機能の追加 ([1c5524d](https://github.com/akky1023/Misq-Notes/commit/1c5524d2cd40d2611596d3ab1931a1999d53944a))
* 外部保存をガチ外部保存に変更 ([8dee38f](https://github.com/akky1023/Misq-Notes/commit/8dee38fa0dd1481799810499e9486f609c2df6d3))


### 修正

* 外部ストレージに保存する際クラッシュする問題の修正 ([2eafc92](https://github.com/akky1023/Misq-Notes/commit/2eafc92cc1e5965fb7b8a848559ce6a58795f040))
* 筆跡の欠けができる問題を修正 ([bb45fb1](https://github.com/akky1023/Misq-Notes/commit/bb45fb1cd3a0958e4be9ac999ca2a70b88f7a596))

## [0.3.1](https://github.com/akky1023/Misq-Notes/compare/v0.3.0...v0.3.1) (2026-10-28)


### 修正

* ハンドルの位置・プレビュ画面の横幅を記憶する ([ff38688](https://github.com/akky1023/Misq-Notes/commit/ff386881922f3765af42b0cd377f45d2716dee6e))
* プレビュの開閉ボタン ([e1628e1](https://github.com/akky1023/Misq-Notes/commit/e1628e1211a8f69544cef39871b9602d5a28e5b9))
* プレビュ画面のスリム化 ([1d0ad93](https://github.com/akky1023/Misq-Notes/commit/1d0ad93e0c492a1f5725e9d916521f9cd53dbf2d))
* メモリ不足でクラッシュする問題 ([c06757a](https://github.com/akky1023/Misq-Notes/commit/c06757a254269eb6ec1cb00297139646044d686c))
* 星タブをボタンに変更 ([f0c1580](https://github.com/akky1023/Misq-Notes/commit/f0c15802e6c08eba047475004953883b8f0d3a99))
* 星ボタンのアニメーション ([1211faf](https://github.com/akky1023/Misq-Notes/commit/1211fafecd116e7b5de33977e23fd5dd7ed0bce0))

## [0.3.0](https://github.com/akky1023/Misq-Notes/compare/v0.2.0...v0.3.0) (2026-10-27)


### 機能追加

* preview of whole pages ([3e5ae60](https://github.com/akky1023/Misq-Notes/commit/3e5ae60302dd6cc4ee8e1522a2105cf33f2f8a57))
* star-mark 機能 ([32c871c](https://github.com/akky1023/Misq-Notes/commit/32c871cdb6d0f29fe01aee0f2ace0ddfb1c3fe4c))
* ファストスクロールバー ([d92526e](https://github.com/akky1023/Misq-Notes/commit/d92526e079f9eb601455439913f56ed262b4d31d))


### 修正

* ストロークキャッシュの選択UI ([fb47356](https://github.com/akky1023/Misq-Notes/commit/fb47356e748c17f2907e4d0ff9b90d0d9c1f8f69))

## [0.2.0](https://github.com/akky1023/Misq-Notes/compare/v0.1.3...v0.2.0) (2026-10-26)


### ⚠ BREAKING CHANGES

* 新保存形式

### 機能追加

* 新保存形式 ([42ac3d7](https://github.com/akky1023/Misq-Notes/commit/42ac3d7718e74424fbf1e33fca148aa1c7f3bc01))


### 修正

* ツールボックスのUI ([10045dd](https://github.com/akky1023/Misq-Notes/commit/10045dde1972ab681cd03e1ff01bf423a301d3cc))
* メディアインポート機能の強化 ([9966178](https://github.com/akky1023/Misq-Notes/commit/9966178144aff21c28063df452d9cd420fb0acb2))
