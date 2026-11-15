from pathlib import Path

main = Path("app/src/main/java/com/aziitech/simregmonitor/MainActivity.kt")
s = main.read_text(encoding="utf-8")

def once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label}; found {count}")
    s = s.replace(old, new, 1)

once('private const val SETTING_SMS_SUB_ID = "sms_subscription_id"\n',
     'private const val SETTING_SMS_SUB_ID = "sms_subscription_id"\n'
     'private const val SETTING_SMS_SIM_MODE = "sms_subscription_selection_mode"\n'
     'private const val SMS_SIM_MODE_AUTO_GLO = "AUTO_GLO"\n'
     'private const val SMS_SIM_MODE_MANUAL = "MANUAL"\n', "SIM setting keys")

once('''    private fun simLabel(sims: List<SubscriptionInfo>, subId: Int): String {
''', '''    private fun isGlobacomSubscription(sim: SubscriptionInfo): Boolean {
        val hasOperatorCode: Boolean
        val isGloCode: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mcc = sim.mccString.orEmpty()
            val mnc = sim.mncString.orEmpty()
            hasOperatorCode = mcc.isNotBlank() && mnc.isNotBlank() && mcc != "000" && mnc != "000"
            isGloCode = mcc == "621" && mnc == "50"
        } else {
            hasOperatorCode = sim.mcc > 0 && sim.mnc >= 0
            isGloCode = sim.mcc == 621 && sim.mnc == 50
        }
        if (isGloCode) return true
        if (hasOperatorCode) return false
        val carrier = sim.carrierName?.toString().orEmpty()
        return Regex("\\\\b(glo|globacom)\\\\b", RegexOption.IGNORE_CASE).containsMatchIn(carrier)
    }

    private fun isSubscriptionReady(subscriptionId: Int): Boolean {
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
            !hasPermission(Manifest.permission.READ_PHONE_STATE)
        ) return false
        return runCatching {
            val active = SubscriptionManager.from(this).activeSubscriptionInfoList.orEmpty()
            if (active.none { it.subscriptionId == subscriptionId }) return@runCatching false
            val telephony = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            telephony.createForSubscriptionId(subscriptionId).simState == android.telephony.TelephonyManager.SIM_STATE_READY
        }.getOrDefault(false)
    }

    private fun smsManagerForSubscription(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requireNotNull(getSystemService(SmsManager::class.java)).createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }

    private fun simLabel(sims: List<SubscriptionInfo>, subId: Int): String {
''', "SIM identification helpers")

once('''        return try {
            val subId = subscriptionId ?: SubscriptionManager.getDefaultSmsSubscriptionId()
            val manager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                SmsManager.getDefault()
            }
''', '''        return try {
            val subId = subscriptionId ?: return false
            if (!isSubscriptionReady(subId)) return false
            val manager = smsManagerForSubscription(subId)
''', "direct sender SIM routing")

once('''        if (!hasPermission(Manifest.permission.SEND_SMS)) return@withContext SmsDeliverySendResult(false, false, false, false, 0, false)
        var attempt = 0
''', '''        if (!hasPermission(Manifest.permission.SEND_SMS)) return@withContext SmsDeliverySendResult(false, false, false, false, 0, false)
        val subId = subscriptionId ?: return@withContext SmsDeliverySendResult(false, false, false, false, 0, false)
        if (!isSubscriptionReady(subId)) return@withContext SmsDeliverySendResult(false, false, false, false, 0, false)
        var attempt = 0
''', "delivery sender subscription requirement")

once('''            val token = SmsDeliveryTracker.begin()
            try {
                val subId = subscriptionId ?: SubscriptionManager.getDefaultSmsSubscriptionId()
                val manager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    SmsManager.getSmsManagerForSubscriptionId(subId)
                } else SmsManager.getDefault()
''', '''            if (!isSubscriptionReady(subId)) {
                return@withContext SmsDeliverySendResult(false, deliveryReportingObserved, false, false, attempt - 1, false)
            }
            val token = SmsDeliveryTracker.begin()
            try {
                val manager = smsManagerForSubscription(subId)
''', "per-send SIM revalidation")

once('''            if (selectedSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                val defaultId = runCatching {
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                val chosen = activeSims.firstOrNull { it.subscriptionId == defaultId }?.subscriptionId
                    ?: activeSims.firstOrNull()?.subscriptionId
                if (chosen != null) {
                    selectedSubId = chosen
                    store.setSetting(SETTING_SMS_SUB_ID, chosen.toString())
                }
            }
''', '''            val mode = store.getSetting(SETTING_SMS_SIM_MODE)
            val selectedInfo = freshSims.firstOrNull { it.subscriptionId == selectedSubId }
            val selectedReady = selectedInfo != null && isSubscriptionReady(selectedInfo.subscriptionId)
            val readyGlo = freshSims.filter(::isGlobacomSubscription)
                .filter { isSubscriptionReady(it.subscriptionId) }
                .sortedBy { it.simSlotIndex }
            when (mode) {
                SMS_SIM_MODE_AUTO_GLO -> {
                    if (selectedInfo == null || !selectedReady || !isGlobacomSubscription(selectedInfo)) {
                        val chosen = readyGlo.firstOrNull()
                        selectedSubId = chosen?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        store.setSetting(SETTING_SMS_SUB_ID, selectedSubId.toString())
                        message = if (chosen != null) {
                            "Glo SIM ${chosen.simSlotIndex + 1} automatically selected for SMS. You can choose another SIM manually."
                        } else {
                            "No ready Glo SIM was detected. Choose a SIM manually before starting SMS checks."
                        }
                    }
                }
                SMS_SIM_MODE_MANUAL -> {
                    if (!selectedReady) {
                        selectedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        store.setSetting(SETTING_SMS_SUB_ID, selectedSubId.toString())
                    }
                }
                else -> {
                    // One-time migration: prefer Glo; preserve a previously saved active line if no Glo exists.
                    val chosen = readyGlo.firstOrNull()
                    if (chosen != null) {
                        selectedSubId = chosen.subscriptionId
                        store.setSetting(SETTING_SMS_SUB_ID, chosen.subscriptionId.toString())
                        store.setSetting(SETTING_SMS_SIM_MODE, SMS_SIM_MODE_AUTO_GLO)
                        message = "Glo SIM ${chosen.simSlotIndex + 1} automatically selected for SMS. You can choose another SIM manually."
                    } else if (selectedReady) {
                        store.setSetting(SETTING_SMS_SIM_MODE, SMS_SIM_MODE_MANUAL)
                    } else {
                        selectedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        store.setSetting(SETTING_SMS_SUB_ID, selectedSubId.toString())
                        store.setSetting(SETTING_SMS_SIM_MODE, SMS_SIM_MODE_AUTO_GLO)
                        message = "No ready Glo SIM was detected. Choose a SIM manually before starting SMS checks."
                    }
                }
            }
''', "Glo-first SIM selection")

once('''                                        selectedSubId = sim.subscriptionId
                                        store.setSetting(SETTING_SMS_SUB_ID, sim.subscriptionId.toString())
                                        showSimMenu = false
''', '''                                        selectedSubId = sim.subscriptionId
                                        store.setSetting(SETTING_SMS_SUB_ID, sim.subscriptionId.toString())
                                        store.setSetting(SETTING_SMS_SIM_MODE, SMS_SIM_MODE_MANUAL)
                                        showSimMenu = false
''', "manual SIM override")

once('''                confirmButton = {
                    TextButton(onClick = { requestPhoneStatePermission(); refreshSims() }) { Text("Refresh SIMs") }
                }
''', '''                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            store.setSetting(SETTING_SMS_SIM_MODE, SMS_SIM_MODE_AUTO_GLO)
                            selectedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                            store.setSetting(SETTING_SMS_SUB_ID, selectedSubId.toString())
                            refreshSims()
                            if (selectedSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) showSimMenu = false
                            else message = "No ready Glo SIM found. Insert/enable a Glo SIM or choose another SIM manually."
                        }) { Text("Auto-select Glo") }
                        TextButton(onClick = { requestPhoneStatePermission(); refreshSims() }) { Text("Refresh SIMs") }
                    }
                }
''', "return to automatic Glo selection")

once('''        val carrier = sim.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM"
        return "SIM ${sim.simSlotIndex + 1} • $carrier"
''', '''        val carrier = sim.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM"
        val gloTag = if (isGlobacomSubscription(sim)) " • Glo" else ""
        return "SIM ${sim.simSlotIndex + 1} • $carrier$gloTag"
''', "Glo selector label")

if "SubscriptionManager.getDefaultSmsSubscriptionId()" in s or "SmsManager.getDefault()" in s:
    raise SystemExit("Unsafe default-SIM fallback remains in MainActivity.")
if "createForSubscriptionId(subscriptionId)" not in s:
    raise SystemExit("Modern subscription-bound SmsManager path is missing.")
main.write_text(s, encoding="utf-8")
print("Glo-first SIM selection and explicit per-subscription SMS routing applied.")
