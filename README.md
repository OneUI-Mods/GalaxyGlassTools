# Galaxy Glass Tools

[English](README.en.md) · [開発者向け](docs/development.md)

GalaxyのSystemUI、One UIホーム、Samsung Captureにガラス効果を適用するVector／libxposed対応モジュールです。クイックパネル、通知とロック画面、音量パネル、ホームのフォルダやドック、撮影の操作画面を個別に切り替えられます。

1. Galaxy Glass Toolsをインストールします。
2. 移行する場合は、One UI Home Tweaks 0.21.52とGalaxy Capture Tools 0.4.15以降に更新します。ガラスの設定は旧アプリから一度引き継ぎます。
3. VectorでGalaxy Glass Toolsを有効にし、SystemUI、One UIホーム、Samsung Captureから使う対象を選びます。
4. 対象プロセスを再読み込みし、Galaxy Glass Toolsで表示と描画状況を確認します。

確認対象はGalaxy S26 Ultra（SM-S948Q）のAndroid 17 BZI9です。Android 16 AZHLではSamsung Capture用の経路を用意しています。アプリ内プレビューはモジュールを有効にする前にも使えます。

設定画面からOne UIホームとSamsung Captureを個別に再読み込みできます。Root権限を許可すると、ホームはすぐに開き、Samsung Captureは次回の撮影時に起動します。録画や画面共有の実行中はSamsung Captureの再読み込みを止めます。
