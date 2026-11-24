package com.relay.sms.domain.model

data class BlockedNumber(
    val id: Long,
    val rawNumber: String,
    val normalizedNumber: String,
    val label: String?,
    val createdAt: Long,
)
