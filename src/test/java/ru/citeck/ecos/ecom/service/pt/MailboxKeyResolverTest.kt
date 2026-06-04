package ru.citeck.ecos.ecom.service.pt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import ru.citeck.ecos.commons.data.DataValue
import ru.citeck.ecos.records2.predicate.model.Predicate
import ru.citeck.ecos.records2.predicate.model.ValuePredicate
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.entity.EntityRef

class MailboxKeyResolverTest {

    private lateinit var recordsService: RecordsService
    private lateinit var resolver: MailboxKeyResolver

    private val projectRef = EntityRef.valueOf("emodel/project@PRJCTMNG")
    private val issueRef = EntityRef.valueOf("emodel/ept-issue@issue-1")

    private val projectsByKey = mutableMapOf<String, EntityRef>()
    private val issuesByKey = mutableMapOf<String, EntityRef>()
    private val issueLinkProject = mutableMapOf<EntityRef, EntityRef>()

    private var projectQueryCount = 0
    private var issueQueryCount = 0

    @BeforeEach
    fun setup() {
        recordsService = mock()
        resolver = MailboxKeyResolver(recordsService)
        projectsByKey.clear()
        issuesByKey.clear()
        issueLinkProject.clear()
        projectQueryCount = 0
        issueQueryCount = 0

        doAnswer { inv ->
            val query = inv.getArgument<RecordsQuery>(0)
            val predicate = query.getQuery(Predicate::class.java) as ValuePredicate
            val value = predicate.getValue().asText()
            when (query.sourceId) {
                "emodel/project" -> {
                    projectQueryCount++
                    projectsByKey[value] ?: EntityRef.EMPTY
                }
                "emodel/ept-issue" -> {
                    issueQueryCount++
                    issuesByKey[value] ?: EntityRef.EMPTY
                }
                else -> EntityRef.EMPTY
            }
        }.whenever(recordsService).queryOne(any<RecordsQuery>())

        whenever(recordsService.getAtt(any<EntityRef>(), eq("link-project:project?id")))
            .thenAnswer { inv ->
                val ref = inv.getArgument<EntityRef>(0)
                val projectLink = issueLinkProject[ref] ?: EntityRef.EMPTY
                DataValue.createStr(projectLink.toString())
            }
    }

    private fun registerProject(key: String, ref: EntityRef = projectRef) {
        projectsByKey[key] = ref
    }

    private fun registerIssue(issueKey: String, ref: EntityRef = issueRef, project: EntityRef = projectRef) {
        issuesByKey[issueKey] = ref
        issueLinkProject[ref] = project
    }

    @Test
    fun `R1 unknown key returns null`() {
        assertThat(resolver.resolve("[OK]")).isNull()
        assertThat(resolver.resolve("")).isNull()
        assertThat(resolver.resolve(null)).isNull()
    }

    @Test
    fun `R2 project key resolves to project`() {
        registerProject("PRJCTMNG")
        val resolved = resolver.resolve("[PRJCTMNG] Hello")
        assertThat(resolved).isNotNull
        assertThat(resolved!!.projectRef).isEqualTo(projectRef)
        assertThat(resolved.hasIssue()).isFalse()
    }

    @Test
    fun `R3 issue key without brackets resolves to issue and project`() {
        registerIssue("PRJCTMNG-33")
        val resolved = resolver.resolve("PRJCTMNG-33: details")
        assertThat(resolved).isNotNull
        assertThat(resolved!!.issueRef).isEqualTo(issueRef)
        assertThat(resolved.projectRef).isEqualTo(projectRef)
    }

    @Test
    fun `R4 Fwd and Re prefixes are stripped`() {
        registerIssue("PRJCTMNG-33")
        val resolved = resolver.resolve("Re: Fwd: [PRJCTMNG-33] Subject")
        assertThat(resolved).isNotNull
        assertThat(resolved!!.issueRef).isEqualTo(issueRef)
    }

    @Test
    fun `R5 cache hit avoids second DB query`() {
        registerIssue("PRJCTMNG-33")
        resolver.resolve("PRJCTMNG-33")
        resolver.resolve("PRJCTMNG-33")
        assertThat(issueQueryCount).isEqualTo(1)
    }

    @Test
    fun `lowercase does not match`() {
        registerIssue("PRJCTMNG-33")
        val resolved = resolver.resolve("prjctmng-33 lowercase")
        assertThat(resolved).isNull()
    }

    @Test
    fun `issue match wins over plain project match`() {
        registerProject("PRJCTMNG")
        registerIssue("PRJCTMNG-33")
        val resolved = resolver.resolve("[PRJCTMNG] reviewing PRJCTMNG-33 today")
        assertThat(resolved).isNotNull
        assertThat(resolved!!.issueRef).isEqualTo(issueRef)
    }

    @Test
    fun `issue key missing, falls back to project key`() {
        registerProject("PRJCTMNG")
        val resolved = resolver.resolve("PRJCTMNG-9999 no such issue")
        assertThat(resolved).isNotNull
        assertThat(resolved!!.hasIssue()).isFalse()
        assertThat(resolved.projectRef).isEqualTo(projectRef)
    }

    @Test
    fun `strip prefixes handles Russian and multiple layers`() {
        assertThat(resolver.stripPrefixes("Re: Fwd: Переслано: actual"))
            .isEqualTo("actual")
        assertThat(resolver.stripPrefixes("ответ: hello"))
            .isEqualTo("hello")
    }

    @Test
    fun `extract candidates returns unique values preserving order`() {
        val candidates = resolver.extractCandidates("[AAA] BBB-1 [AAA] CCC")
        assertThat(candidates.map { it.raw }).containsExactly("AAA", "BBB-1", "CCC")
    }
}
