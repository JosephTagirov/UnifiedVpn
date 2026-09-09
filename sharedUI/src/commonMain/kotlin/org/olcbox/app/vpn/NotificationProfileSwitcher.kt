package org.olcbox.app.vpn

internal fun notificationProfileTargetId(
    orderedProfileIds: List<String>,
    activeProfileId: String?,
    steps: Int
): String? {
    if (steps == 0) return null

    val profileIds = orderedProfileIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
    if (profileIds.size < 2) return null

    val currentIndex = profileIds.indexOf(activeProfileId?.trim()).takeIf { it >= 0 } ?: 0
    val targetIndex = ((currentIndex.toLong() + steps) % profileIds.size)
        .let { if (it < 0) it + profileIds.size else it }
        .toInt()
    return profileIds[targetIndex].takeUnless { it == profileIds[currentIndex] }
}

internal fun notificationSelectedProfileTargetId(
    orderedProfileIds: List<String>,
    runningProfileId: String?,
    selectedProfileId: String?
): String? {
    val profileIds = orderedProfileIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toSet()
    val selected = selectedProfileId?.trim().orEmpty()
    if (selected.isEmpty() || selected !in profileIds) return null
    return selected.takeUnless { it == runningProfileId?.trim() }
}

internal fun notificationSafeProfileName(value: String, fallback: String = "Profile"): String {
    return value
        .replace(NOTIFICATION_PROFILE_UNSAFE_TEXT, " ")
        .trim()
        .take(MAX_NOTIFICATION_PROFILE_NAME_LENGTH)
        .ifBlank { fallback }
}

private const val MAX_NOTIFICATION_PROFILE_NAME_LENGTH = 80
private val NOTIFICATION_PROFILE_UNSAFE_TEXT = Regex("[\\p{Cc}\\p{Cf}\\s]+")
