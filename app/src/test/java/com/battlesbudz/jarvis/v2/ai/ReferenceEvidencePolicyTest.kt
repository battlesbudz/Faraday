package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ReferenceEvidencePolicyTest {
    @Test fun acronymRequiresBothTermAndConversationDomain() {
        val query = "FPJ\nConversation domain: I'm making nutrients using Korean Natural Farming"
        assertFalse(ReferenceEvidencePolicy.relevant(query, "Fernando Poe Jr.", "FPJ was an actor and politician."))
        assertTrue(ReferenceEvidencePolicy.relevant(query, "Korean Natural Farming", "Fermented plant juice (FPJ) is used in Korean Natural Farming."))
    }
    @Test fun holidayTriviaCannotVerifyShopOffer() {
        assertFalse(ReferenceEvidencePolicy.relevant("Today is National Coffee Day and Stewart says free coffee", "International Coffee Day", "National coffee celebrations include free coffee."))
    }
    @Test fun retrievesRelevantBodyParagraphBeyondIntro() {
        val article = "Korean Natural Farming uses indigenous microorganisms.\n\n" + "Unrelated history.\n".repeat(200) + "Fermented plant juice (FPJ) uses brown sugar and young plants in Korean Natural Farming."
        val passage = ReferenceEvidencePolicy.passage("FPJ\nConversation domain: Korean Natural Farming", "Korean Natural Farming", article)
        assertTrue(passage.contains("brown sugar")); assertTrue(passage.length <= 1800)
    }
    @Test fun missingAcronymOrEmptyEvidenceIsNotSuccess() {
        assertFalse(ReferenceEvidencePolicy.relevant("FPJ\nConversation domain: Korean Natural Farming", "Korean Natural Farming", "Uses indigenous microorganisms."))
        assertFalse(ReferenceEvidencePolicy.relevant("Jack Herer", "Jack Herer", ""))
    }
    @Test fun genericFoundingWordsAndOneWordOfTheSubjectDoNotEstablishRelevance() {
        assertFalse(ReferenceEvidencePolicy.relevant("Where was the first trucker bell founded?", "Georgie & Mandy's First Marriage", "Her father founded the family business in the first season."))
        assertFalse(ReferenceEvidencePolicy.relevant("Where was the first Taco Bell founded?", "Edward Harold Bell", "Bell was the first fugitive featured on a television show."))
        assertFalse(ReferenceEvidencePolicy.relevant("Fear was Taco Bell founded. Fear.", "The League", "Taco uses the password Taco and the episode was called The Fear Boners."))
        assertTrue(ReferenceEvidencePolicy.relevant("Where was the first Taco Bell founded?", "Taco Bell", "The first restaurant opened in Downey, California."))
        assertFalse(ReferenceEvidencePolicy.relevant("Use Wikipedia.", "Wikipedia", "A free online encyclopedia."))
    }
    @Test fun bothWordsOfALowercaseSubjectAreRequiredAndSearchUsesTheLiteralSubject() {
        assertFalse(ReferenceEvidencePolicy.relevant("jack herer", "Jack Black", "Jack is an actor."))
        assertTrue(ReferenceEvidencePolicy.relevant("jack herer", "Jack Herer", "Jack Herer was an American author."))
        assertEquals("Taco Bell", ReferenceEvidencePolicy.searchQuery("Where was the first Taco Bell founded?"))
        assertEquals("trucker bell", ReferenceEvidencePolicy.searchQuery("Where was the first trucker bell founded?"))
    }

    @Test fun firstLocationHistoryOutranksCurrentHeadquarters() {
        val article = "Taco Bell is a restaurant chain headquartered in Irvine.\n\n" +
            "Taco Bell has many restaurant menus and promotions.\n\n".repeat(80) +
            "The first Taco Bell was opened in Downey, California, in 1962."
        val passage = ReferenceEvidencePolicy.passage("Where was the first Taco Bell founded?", "Taco Bell", article, 300)
        assertTrue(passage.contains("Downey"))
        assertTrue(passage.length <= 300)
    }

}
