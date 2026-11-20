# Galaxy Glass Tools

[English](README.en.md) · [開発者向け](docs/development.md)

GalaxyのSystemUI、One UIホーム、Samsung Capture、画面を持つシステムアプリにガラス効果を適用するVector／libxposed対応モジュールです。クイックパネル、通知とロック画面、音量パネル、ホームのフォルダやドック、撮影の操作画面を個別に切り替えられます。

1. Galaxy Glass Toolsをインストールします。
2. 移行する場合は、One UI Home Tweaks 0.21.52とGalaxy Capture Tools 0.4.15以降に更新します。ガラスの設定は旧アプリから一度引き継ぎます。
3. VectorでGalaxy Glass Toolsを有効にし、SystemUI、One UIホーム、Samsung Captureから使う対象を選びます。
4. ほかのシステムアプリも使う場合は「システムアプリを一括で適用範囲に追加」を押し、Vectorで許可します。
5. 対象アプリを開き直します。SystemUIなどは対象プロセスの再読み込みで反映できます。

システムアプリでは、標準のツールバー、タブ、検索バー、ナビゲーション、カード、ダイアログ、ポップアップ、下から開くシートに適用します。共有シート、Samsungキーボードの背景や候補欄、プリインストール版Chromeのツールバーにも個別の描画処理があります。DeXのタイトルバーはSystemUIの適用範囲に含まれます。仕事用プロファイルで使う場合は、そのプロファイルのシステムアプリをVectorの適用範囲に追加します。

認証画面や保護されたウィンドウ、パスワード入力時のキーボードは純正表示を使います。

確認対象はGalaxy S26 Ultra（SM-S948Q）のAndroid 17 BZI9です。Android 16 AZHLではSamsung Capture用の経路を用意しています。アプリ内プレビューはモジュールを有効にする前にも使えます。

設定画面からOne UIホームとSamsung Captureを個別に再読み込みできます。Root権限を許可すると、ホームはすぐに開き、Samsung Captureは次回の撮影時に起動します。録画や画面共有の実行中はSamsung Captureの再読み込みを止めます。
