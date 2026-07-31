package com.hisn.app.audit

/**
 * Severity of an audit finding, ordered from benign to most serious.
 * Every finding is honest about being a *hygiene / indicator* signal — none of
 * this proves or disproves the presence of nation-state spyware.
 */
enum class Severity(val rank: Int) {
    OK(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4)
}

/**
 * A single result from the system auditor.
 *
 * @param title short Arabic title
 * @param detail what was observed
 * @param recommendation what the user can do about it
 */
data class Finding(
    val id: String,
    val title: String,
    val detail: String,
    val recommendation: String,
    val severity: Severity
)
