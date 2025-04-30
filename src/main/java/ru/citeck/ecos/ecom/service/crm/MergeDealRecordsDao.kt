package ru.citeck.ecos.ecom.service.crm

import org.springframework.stereotype.Component
import ru.citeck.ecos.commons.data.ObjectData
import ru.citeck.ecos.ecom.service.crm.dto.MergeInfo
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.regex.Pattern

@Component
class MergeDealRecordsDao(recordsService: RecordsService?) : AbstractMergeCrmRecordsDao(recordsService) {

    companion object {
        private const val AMOUNT_ATT = "amount"
        private const val COMPANY_ATT = "company"
        private const val CONTRACT_ATT = "contract"
        private const val NUMBER_OF_USERS = "numberOfUsers"
        private const val CREATED_FROM_LEAD_ATT = "createdFromLead"

        private const val CRM_LINK_DEAL = "crm-links:deal"

        private val COMMENT_MERGED_MARK: Pattern =
            Pattern.compile("Комментрий от [0-9]{2}.[0-9]{2}.[0-9]{4} из сделки [0-9]+")
    }

    override fun getId(): String {
        return "merge-deal"
    }

    override fun mergeSpecificAttributes(mergeInfo: MergeInfo, mergedAtts: ObjectData?) {
        mergeAtt(mergeInfo, AMOUNT_ATT, mergedAtts)
        mergeAtt(mergeInfo, NUMBER_OF_USERS, mergedAtts)
        mergeAtt(mergeInfo, COMPANY_ATT, mergedAtts)
        mergeAtt(mergeInfo, "$CONTRACT_ATT?id", mergedAtts)
        mergeAtt(mergeInfo, "$CREATED_FROM_LEAD_ATT?id", mergedAtts)

        val addedOrders = addAssoc(mergeInfo, ORDERS_ATT, mergedAtts)
        linkCrmDeal(mergeInfo.mergeIn, addedOrders)

        val addedPayments = addAssoc(mergeInfo, PAYMENTS_ATT, mergedAtts)
        linkCrmDeal(mergeInfo.mergeIn, addedPayments)
    }

    private fun linkCrmDeal(deal: EntityRef, refsToLink: List<String>) {
        refsToLink.forEach { ref ->
            val atts = RecordAtts(ref)
            atts[CRM_LINK_DEAL] = deal
            recordsService.mutate(atts)
        }
    }

    override fun getCommentMergedMarkPattern(): Pattern {
        return COMMENT_MERGED_MARK
    }

    override fun getEntityTypeDisplayName(): String {
        return "Сделка"
    }


}

