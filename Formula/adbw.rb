# Updated automatically by .github/workflows/release.yml on every release.
class Adbw < Formula
  desc "Wireless adb that survives Wi-Fi drops"
  homepage "https://github.com/aglose/adbw"
  url "https://github.com/aglose/adbw/releases/download/v0.2.0/adbw"
  version "0.2.0"
  sha256 "764e29c16e81dca5366baae57b924ca37157e00544d6a0fb0fc6401e8eb6c0cc"
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
