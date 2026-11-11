# Updated automatically by .github/workflows/release.yml on every release.
class Adbw < Formula
  desc "Wireless adb that survives Wi-Fi drops"
  homepage "https://github.com/aglose/adbw"
  url "https://github.com/aglose/adbw/releases/download/v0.2.1/adbw"
  version "0.2.1"
  sha256 "dad67303620e5101171b74eb40498dc95cbc3edb76b089a09ddcda5d8fd18f49"
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
