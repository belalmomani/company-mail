package com.shegrowsdev.mail.admin

import java.time.LocalDate
import java.time.ZoneId

/** A reporting period: [fromMillis] inclusive, [toExclusiveMillis] exclusive, whole local days. */
data class Period(val fromMillis: Long, val toExclusiveMillis: Long, val firstDay: LocalDate, val lastDay: LocalDate)

enum class PeriodPreset { TODAY, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, LAST_MONTH, CUSTOM }

object Periods {
    fun of(firstDay: LocalDate, lastDay: LocalDate, zone: ZoneId): Period {
        val (a, b) = if (lastDay.isBefore(firstDay)) lastDay to firstDay else firstDay to lastDay
        return Period(
            fromMillis = a.atStartOfDay(zone).toInstant().toEpochMilli(),
            toExclusiveMillis = b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            firstDay = a,
            lastDay = b,
        )
    }

    fun preset(preset: PeriodPreset, today: LocalDate, zone: ZoneId): Period = when (preset) {
        PeriodPreset.TODAY -> of(today, today, zone)
        PeriodPreset.LAST_7_DAYS -> of(today.minusDays(6), today, zone)
        PeriodPreset.LAST_30_DAYS -> of(today.minusDays(29), today, zone)
        PeriodPreset.THIS_MONTH -> of(today.withDayOfMonth(1), today, zone)
        PeriodPreset.LAST_MONTH -> {
            val firstOfLast = today.withDayOfMonth(1).minusMonths(1)
            of(firstOfLast, firstOfLast.withDayOfMonth(firstOfLast.lengthOfMonth()), zone)
        }
        PeriodPreset.CUSTOM -> of(today, today, zone)
    }
}

data class ResponseSample(val incomingRefId: Long, val replyRefId: Long, val millis: Long, val external: Boolean)

data class ResponseGroup(val count: Int, val averageMillis: Long?)

data class ResponseSummary(val overall: ResponseGroup, val external: ResponseGroup, val internal: ResponseGroup)

data class TransactionCounts(val open: Int, val overdue: Int)

/**
 * Definitions used by the dashboard (shown to the user as well):
 *
 * - **Message** = one e-mail. **Transaction** = a conversation (messages linked by Message-ID /
 *   In-Reply-To / References) that has at least one unfinished task or pending follow-up.
 * - **Open transaction** = a transaction with an unfinished task or a pending follow-up (as of now).
 * - **Overdue transaction** = an open transaction in which an unfinished task or a pending follow-up
 *   has a due time in the past. Items without a due time are never overdue.
 * - **Response time** = time from the receipt of an incoming message to the first reply sent from the
 *   same account in the same transaction. A reply whose In-Reply-To names the message wins; otherwise the
 *   first later reply in the transaction counts. Only incoming messages that have a recorded reply are
 *   included; later replies are ignored. External and internal senders are also reported separately.
 */
object Stats {

    fun responseSamples(refs: List<StatRef>, fromMillis: Long, toExclusiveMillis: Long): List<ResponseSample> {
        val outgoing = refs
            .filter { it.direction == Direction.OUTGOING && it.dateMillis != null }
            .groupBy { it.transactionId to it.accountId }

        return refs.filter { it.direction == Direction.INCOMING }.mapNotNull { incoming ->
            val received = incoming.dateMillis ?: return@mapNotNull null
            if (received < fromMillis || received >= toExclusiveMillis) return@mapNotNull null

            val later = outgoing[incoming.transactionId to incoming.accountId].orEmpty()
                .filter { (it.dateMillis ?: Long.MIN_VALUE) > received }
            if (later.isEmpty()) return@mapNotNull null

            val incomingId = ThreadLinker.normalizeId(incoming.messageId)
            val direct = if (incomingId == null) emptyList<StatRef>()
            else later.filter { ThreadLinker.normalizeId(it.inReplyTo) == incomingId }
            val reply = (if (direct.isNotEmpty()) direct else later).minByOrNull { it.dateMillis ?: Long.MAX_VALUE }
                ?: return@mapNotNull null
            val replyAt = reply.dateMillis ?: return@mapNotNull null
            ResponseSample(incoming.id, reply.id, replyAt - received, incoming.external)
        }
    }

    private fun group(samples: List<ResponseSample>): ResponseGroup =
        ResponseGroup(samples.size, if (samples.isEmpty()) null else samples.sumOf { it.millis } / samples.size)

    fun summarize(samples: List<ResponseSample>): ResponseSummary = ResponseSummary(
        overall = group(samples),
        external = group(samples.filter { it.external }),
        internal = group(samples.filter { !it.external }),
    )

    fun isOverdue(item: OpenItem, now: Long): Boolean =
        item.openTaskDues.any { it != null && it < now } || item.pendingFollowUpDues.any { it < now }

    fun transactionCounts(items: List<OpenItem>, now: Long): TransactionCounts {
        val open = items.filter { it.openTaskDues.isNotEmpty() || it.pendingFollowUpDues.isNotEmpty() }
        return TransactionCounts(open = open.size, overdue = open.count { isOverdue(it, now) })
    }
}

/** When a follow-up reminder is due to be shown. Each stage is notified at most once. */
object ReminderRules {
    /** 0 = nothing yet, 1 = approaching (within the lead time), 2 = overdue. */
    fun stageFor(dueAt: Long, now: Long, leadMillis: Long): Int = when {
        now >= dueAt -> 2
        now >= dueAt - leadMillis -> 1
        else -> 0
    }

    fun shouldNotify(stage: Int, alreadyNotified: Int): Boolean = stage > alreadyNotified
}

object SerialFormat {
    /** `000123` – the number shown in the official register. */
    fun format(seq: Long): String = seq.toString().padStart(6, '0')
}

/** Splits a duration into whole days, hours and minutes (the smaller units are what is left over). */
object Durations {
    data class Parts(val days: Long, val hours: Long, val minutes: Long)

    fun parts(millis: Long): Parts {
        val totalMinutes = (millis.coerceAtLeast(0L)) / 60_000L
        return Parts(totalMinutes / 1440, (totalMinutes % 1440) / 60, totalMinutes % 60)
    }
}
