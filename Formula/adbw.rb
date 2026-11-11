# Updated automatically by .github/workflows/release.yml on every release.
class Adbw < Formula
  desc "Wireless adb that survives Wi-Fi drops"
  homepage "https://github.com/aglose/adbw"
  url "https://github.com/aglose/adbw/releases/download/v0.3.0/adbw"
  version "0.3.0"
  sha256 "c272e114c849fae2305a417fa2a7c0fb5f9d7e346317ab9cc4e5a27f150a15b2"
  license "MIT"

  uses_from_macos "python"

  def install
    bin.install "adbw"
  end

  def caveats
    <<~EOS
      adbw needs adb. If you don't have it:
        brew install --cask android-platform-tools
    EOS
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/adbw --version")
  end
end
