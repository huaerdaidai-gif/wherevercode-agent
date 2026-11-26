<div align="center">

# WhereverCode Agent

**One workspace. Every Agent.**

**手持ちの AI Agent を一つのアプリに集め、デバイスをまたいで協働。**

オープンソース · Android / iOS ネイティブ · ローカル優先

[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE) [![Release](https://img.shields.io/github/v/release/huaerdaidai-gif/wherevercode-agent)](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/latest)

[简体中文](README.md) · [English](README.en.md) · [日本語](README.ja.md)

</div>

## ダウンロードと使い方

[Android APK](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/download/v0.5.6/wherevercode-agent-0.5.6-238.apk) · [iOS IPA](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/download/v0.5.6/WhereverCodeAgent-unsigned-0.5.6-238.ipa) · [Release v0.5.6](https://github.com/huaerdaidai-gif/wherevercode-agent/releases/tag/v0.5.6) · [All releases](https://github.com/huaerdaidai-gif/wherevercode-agent/releases)

現在のリリース：**v0.5.6 / ビルド 238**（開発初期版）。Android は APK、iOS は未署名 IPA を提供します。iOS は Apple の制約に従い、Sideloadly や AltStore などで自己署名・サイドロードしてください。

1. アプリをインストールし、コントロール画面でモデルまたは Agent を設定します。
2. 作業先の PC または対応端末で、アプリが表示する Hand インストールコマンドを実行し、デバイス側コンポーネントを起動してペアリング・認可します。既存 CLI Agent は事前にインストール・ログインしてください。
3. 対話相手を選んで会話・タスク依頼を開始します。協働にはチャンネルを作成し、必要な接続を設定します。

基本的なローカル利用に VPS の購入は不要です。モデルサービスには自身のアカウントや API キーが必要な場合があり、無料枠は各提供元の条件に従います。

## なぜ WhereverCode？

Codex、Claude Code、Antigravity、Grok Build、Kiro など、既に使っている AI Agent や複数の AI アカウントを置き換えるのではなく、一つのモバイルワークスペースで協働させます。

目標は、**ユーザーが目的設定・意思決定・承認を行い、Agent がタスク配分・実行・報告・成果物の提供を担うこと**です。特定ベンダーや固定役割に縛られません。コードと作業領域は原則としてユーザー管理下の端末に保持しますが、クラウド AI に送信した認可済みコンテキストは各提供元で処理されます。

## 現在の機能

| 領域 | v0.5.6 の状態 |
| --- | --- |
| 対話・プロジェクト | ネイティブ UI、対話相手の手動選択、プロジェクト・ファイル管理の実装 |
| デバイス・Agent | Hand の導入、ペアリング、検出、遠隔実行、権限制御の実装 |
| モデル | 独立したモデル管理、無料サービスのプリセット、モデルルーティングの実装 |
| チャンネル | 両 OS のローカルチャンネル、メッセージ、メンバー、DM、タスク配分と状態通知。完全な統一は進行中 |
| LAN | 両 OS の LAN サーバー実装。ネットワークと認可に応じて利用 |
| クラウド協働 | プラットフォーム・MCP 接続実装。v0.5.2 のリリース記録に端末間タスク配分、オフライン再配信、状態通知あり |
| セキュリティ | ペアリング、暗号化通信、タスク資格情報、実行権限の実装 |

ソースとリリース記録に基づく範囲であり、現ビルドの全経路で実機 E2E 検証済みという意味ではありません。v0.5.6 はチャンネルのフリーズを修正し、Android の画面を iOS にさらに統一しました。

## 四種類の Agent

| 席位 | 接続方法 | 状態 |
| --- | --- | --- |
| デバイス Agent | Hand による対応 CLI Agent の検出 | 実装あり |
| プラットフォーム Agent | プラットフォーム接続・Webhook | 接続実装あり、起動機能は改善中 |
| クラウド Agent | MCP コネクタ | 接続実装あり |
| Web Agent | 自分の ChatGPT、Claude、Gemini、Grok などの Web アカウント | アプリ統合予定 |

個別対話では相手を手動選択します。モデルプールのルーティングと Agent の選択は別です。全席位のホットプラグと即時オン・オフは目標です。

## 三層ネットワーク

- **ローカル層**：アプリ内チャンネルサービス。Android には既定で無効のループバック限定 MCP タスク配分サービスもあります。
- **LAN 層**：認可された LAN サービス・Hand 接続。固定の公開サーバーは不要です。
- **端末間層**：現行のプラットフォーム接続による遠隔協働。将来は近距離検出、公開鍵アドレス指定、動的集合点、NAT 越え、リレーによる自己組織化で VPS 購入を不要にすることを目指します。

三層の完全な機能一致、自動フェイルオーバー、ネットワークをまたぐ自己組織化は未達の目標です。

## ロードマップ

- [ ] チャンネルの両 OS 統一：メッセージ、編集・取り消し、ファイル参照、予約送信、タスク配分、構造化された応答。
- [ ] デスクトップでの Web 席位検証をアプリに統合し、提供元の利用規約・自動化ルールを遵守。
- [ ] デバイス・Agent・モデルの独立した管理、ホットプラグ、即時切り替えを改善。
- [ ] 利用枠の把握、回復時刻の報告、タスク交代、チェックポイント引き継ぎ。利用可能な無料モデルを優先。
- [ ] プッシュ優先のシグナリング、自律実行・レビュー・再試行・復旧ループを改善。
- [ ] 三層サービス統合と固定 VPS 不要の自己組織化ネットワークを完成。

OSS の無償提供は外部 AI の無制限無料利用を意味しません。推論不要のシグナリングでも、タスク実行にはトークンを消費する場合があります。

## 開発・貢献

本リポジトリは旧 `wherevercode` プラットフォームとは別の独立 App です。Android は Kotlin/Gradle、iOS は Swift/SwiftUI を使用します。

[Android ビルド](android/README.md) · [iOS ビルド](ios/README.md) · [Hand サーバー](server/README.md) · [過去の P0 監査](docs/p0/README.md) · [セキュリティ](SECURITY.md) · [貢献ガイド](CONTRIBUTING.md) · [Apache-2.0](LICENSE)

試用・不具合報告・コード貢献を歓迎します。バージョン、端末、再現手順を記載し、トークン、秘密鍵、署名ファイル、非公開の作業データはアップロードしないでください。

*独立した OSS プロジェクトです。第三者製品への言及は公式提携や推奨を意味しません。*
