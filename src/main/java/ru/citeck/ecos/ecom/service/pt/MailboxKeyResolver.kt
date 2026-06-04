package ru.citeck.ecos.ecom.service.pt

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.citeck.ecos.context.lib.auth.AuthContext
import ru.citeck.ecos.records2.predicate.PredicateService
import ru.citeck.ecos.records2.predicate.model.Predicates
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.concurrent.ConcurrentHashMap

@Component
class MailboxKeyResolver(
    private val recordsService: RecordsService
) {

    companion object {
        private const val CACHE_TTL_MS = 60_000L
        private const val PROJECT_SRC = "emodel/project"
        private const val ISSUE_SRC = "emodel/ept-issue"

        private val log = KotlinLogging.logger {}

        private val STRIP_PREFIXES = listOf(
            "re:",
            "fwd:",
            "fw:",
            "ответ:",
            "переслано:"
        )

        private val KEY_REGEX = Regex("\\[?([A-Z][A-Z0-9]{1,9})(?:-(\\d+))?]?")
    }

    private data class CacheEntry(
        val resolved: ResolvedMailboxKey?,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun resolve(subject: String?): ResolvedMailboxKey? {
        if (subject.isNullOrBlank()) {
            return null
        }
        val stripped = stripPrefixes(subject)
        val candidates = extractCandidates(stripped)
        if (candidates.isEmpty()) {
            return null
        }

        val resolved = mutableListOf<ResolvedMailboxKey>()
        for (candidate in candidates) {
            val cached = getCached(candidate.raw)
            val match = if (cached != null) {
                cached
            } else {
                val fresh = resolveFromDb(candidate)
                putCache(candidate.raw, fresh)
                fresh
            }
            if (match != null) {
                resolved.add(match)
            }
        }

        if (resolved.isEmpty()) {
            return null
        }

        val issueMatch = resolved.firstOrNull { it.hasIssue() }
        if (issueMatch != null) {
            if (resolved.size > 1) {
                log.warn {
                    "Multiple mailbox keys resolved in subject=\"$subject\". " +
                        "Using issue ${issueMatch.issueKey}. All: ${resolved.map { it.displayKey() }}"
                }
            }
            return issueMatch
        }

        if (resolved.size > 1) {
            log.warn {
                "Multiple project keys resolved in subject=\"$subject\". " +
                    "Using first: ${resolved.first().projectKey}. All: ${resolved.map { it.displayKey() }}"
            }
        }
        return resolved.first()
    }

    internal fun stripPrefixes(subject: String): String {
        var current = subject.trimStart()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in STRIP_PREFIXES) {
                if (current.startsWith(prefix, ignoreCase = true)) {
                    current = current.substring(prefix.length).trimStart()
                    changed = true
                    break
                }
            }
        }
        return current
    }

    internal fun extractCandidates(subject: String): List<KeyCandidate> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<KeyCandidate>()
        for (match in KEY_REGEX.findAll(subject)) {
            val projectKey = match.groupValues[1]
            val issueNumber = match.groupValues.getOrNull(2).orEmpty()
            val raw = if (issueNumber.isEmpty()) projectKey else "$projectKey-$issueNumber"
            if (seen.add(raw)) {
                result.add(KeyCandidate(raw, projectKey, issueNumber))
            }
        }
        return result
    }

    private fun resolveFromDb(candidate: KeyCandidate): ResolvedMailboxKey? {
        return AuthContext.runAsSystem { resolveFromDbImpl(candidate) }
    }

    private fun resolveFromDbImpl(candidate: KeyCandidate): ResolvedMailboxKey? {
        if (candidate.issueNumber.isNotEmpty()) {
            val issueKey = "${candidate.projectKey}-${candidate.issueNumber}"
            val issueRef = queryOne(ISSUE_SRC, "issueKey", issueKey)
            if (EntityRef.isNotEmpty(issueRef)) {
                val projectRef = recordsService.getAtt(issueRef, "link-project:project?id")
                    .asText()
                    .let { if (it.isBlank()) EntityRef.EMPTY else EntityRef.valueOf(it) }
                if (EntityRef.isEmpty(projectRef)) {
                    log.warn { "Issue $issueKey has no project link" }
                    return null
                }
                return ResolvedMailboxKey(
                    projectRef = projectRef,
                    issueRef = issueRef,
                    projectKey = candidate.projectKey,
                    issueKey = issueKey
                )
            }
            val projectRef = queryOne(PROJECT_SRC, "key", candidate.projectKey)
            if (EntityRef.isNotEmpty(projectRef)) {
                log.warn { "Issue $issueKey not found, falling back to project ${candidate.projectKey}" }
                return ResolvedMailboxKey(
                    projectRef = projectRef,
                    projectKey = candidate.projectKey
                )
            }
            return null
        }
        val projectRef = queryOne(PROJECT_SRC, "key", candidate.projectKey)
        if (EntityRef.isNotEmpty(projectRef)) {
            return ResolvedMailboxKey(
                projectRef = projectRef,
                projectKey = candidate.projectKey
            )
        }
        return null
    }

    private fun queryOne(sourceId: String, att: String, value: String): EntityRef {
        return recordsService.queryOne(
            RecordsQuery.create()
                .withSourceId(sourceId)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq(att, value))
                .build()
        ) ?: EntityRef.EMPTY
    }

    private fun getCached(raw: String): ResolvedMailboxKey? {
        val entry = cache[raw] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            cache.remove(raw, entry)
            return null
        }
        return entry.resolved
    }

    private fun putCache(raw: String, resolved: ResolvedMailboxKey?) {
        cache[raw] = CacheEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    internal fun clearCache() = cache.clear()

    internal data class KeyCandidate(
        val raw: String,
        val projectKey: String,
        val issueNumber: String
    )

    private fun ResolvedMailboxKey.displayKey(): String = if (hasIssue()) issueKey else projectKey
}
