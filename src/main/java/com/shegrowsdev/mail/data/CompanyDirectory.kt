package com.shegrowsdev.mail.data

/**
 * The company's mail addresses, shown to everyone so colleagues can be found, written to and given tasks.
 *
 * This is ONLY a list of public addresses. It holds no passwords and gives no access: reading someone's mailbox
 * needs that person's own password, typed on their own device. Edit the list when the team changes.
 */
object CompanyDirectory {
    val emails: List<String> = listOf(
        "heba@shegrowsdev.com",
        "lilian@shegrowsdev.com",
        "taymaa@shegrowsdev.com",
        "bushra@shegrowsdev.com",
        "dania@shegrowsdev.com",
        "hiba@shegrowsdev.com",
        "info@shegrowsdev.com",
    )

    /** "heba@x" -> "Heba" (shown next to the address). */
    fun nameOf(email: String): String =
        email.substringBefore('@').replaceFirstChar { it.uppercase() }

    /** Directory addresses plus any account on this device, without duplicates (case-insensitive). */
    fun withAccounts(accounts: List<Account>): List<String> =
        (emails + accounts.map { it.email }).distinctBy { it.lowercase() }
}
