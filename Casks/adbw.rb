# Updated automatically by .github/workflows/release.yml on every release.
#
# A cask, not a formula: adbw is one prebuilt file. A formula without a bottle counts as a build
# from source, and Homebrew then insists on up-to-date Xcode Command Line Tools just to copy it.
cask "adbw" do
  version "0.3.0"
  sha256 "c272e114c849fae2305a417fa2a7c0fb5f9d7e346317ab9cc4e5a27f150a15b2"

  url "https://github.com/aglose/adbw/releases/download/v#{version}/adbw"
  name "adbw"
  desc "Wireless adb that survives Wi-Fi drops"
  homepage "https://github.com/aglose/adbw"

  binary "adbw"

  caveats <<~EOS
    adbw needs python3 (3.9 or newer) and adb on your PATH. If you don't have them:
      brew install python android-platform-tools
  EOS
end
