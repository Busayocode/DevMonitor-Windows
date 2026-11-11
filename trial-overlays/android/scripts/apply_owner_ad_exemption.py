from pathlib import Path
import re

p = Path("MainActivity.kt")
s = p.read_text(encoding="utf-8")

# Reuse the exact Firebase UID gate already protecting the private Broadcast Centre.
# This avoids introducing a second owner identity or exposing the UID in the UI.
old = """        if (BuildConfig.MONETIZED_BUILD) {
            MobileAds.initialize(this)
            DevMonitorInterstitialAd.preload(this)
            DevMonitorRewardedAd.preload(this)
        }"""
new = """        val isVerifiedAppOwner = runCatching {
            BroadcastReleaseManager(this).isCurrentUserBroadcastAdmin()
        }.getOrDefault(false)
        if (BuildConfig.MONETIZED_BUILD && !isVerifiedAppOwner) {
            MobileAds.initialize(this)
            DevMonitorInterstitialAd.preload(this)
            DevMonitorRewardedAd.preload(this)
        }"""
if old not in s:
    raise SystemExit("AdMob startup initialization block not found.")
s = s.replace(old, new, 1)

old = "if (adsRemoved || !BuildConfig.MONETIZED_BUILD) return"
new = "if (adsRemoved || !BuildConfig.MONETIZED_BUILD || broadcastReleaseManager.isCurrentUserBroadcastAdmin()) return"
if old not in s:
    raise SystemExit("Interstitial ad gate not found.")
s = s.replace(old, new, 1)

old = "if (BuildConfig.MONETIZED_BUILD && !adsRemoved) {\n                            DevMonitorBannerAd"
new = "if (BuildConfig.MONETIZED_BUILD && !adsRemoved && !broadcastReleaseManager.isCurrentUserBroadcastAdmin()) {\n                            DevMonitorBannerAd"
if old not in s:
    raise SystemExit("Banner ad gate not found.")
s = s.replace(old, new, 1)

# Keep export entitlement limits intact, but never show a rewarded ad to the owner.
old = """                                    } else if (rewardCredits > 0) launchExport() else {
                                        pendingRewardCredit = true
                                        DevMonitorRewardedAd.show(this@MainActivity, onRewarded = {"""
new = """                                    } else if (rewardCredits > 0) launchExport() else if (broadcastReleaseManager.isCurrentUserBroadcastAdmin()) {
                                        pendingRewardCredit = false
                                        message = "The owner account is exempt from advertisements. Existing export limits and premium credits remain unchanged."
                                    } else {
                                        pendingRewardCredit = true
                                        DevMonitorRewardedAd.show(this@MainActivity, onRewarded = {"""
if old not in s:
    raise SystemExit("Rewarded export ad branch not found.")
s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")
print("Owner-only AdMob exemption applied using the existing private Broadcast Centre Firebase UID gate; subscription/export limits are unchanged.")
