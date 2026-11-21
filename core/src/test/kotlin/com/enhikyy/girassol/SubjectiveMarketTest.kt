package com.enhikyy.girassol

import com.enhikyy.girassol.core.world.CREDIT
import com.enhikyy.girassol.core.world.FOOD
import com.enhikyy.girassol.core.world.SubjectiveMarket
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectiveMarketTest {
    @Test
    fun quoteReflectsMarginalUtilityScarcityAndSellerSurplus() {
        val ledger = StockFlowDoubleEntryLedger()
        val calm = SubjectiveMarket.quote(
            ledger = ledger,
            resourceType = FOOD,
            currencyType = CREDIT,
            quantity = 2.0,
            buyerNeed = 0.9,
            buyerInventory = 1.8,
            sellerInventory = 4.1,
            communalInventory = 8.0
        )
        val urgent = SubjectiveMarket.quote(
            ledger = ledger,
            resourceType = FOOD,
            currencyType = CREDIT,
            quantity = 2.0,
            buyerNeed = 0.1,
            buyerInventory = 0.0,
            sellerInventory = 6.0,
            communalInventory = 0.0
        )

        assertNotNull(calm)
        assertNotNull(urgent)
        assertTrue("fome + escassez devem elevar o lance", urgent!!.unitPrice > calm!!.unitPrice)
        assertTrue(urgent.buyerUrgency > calm.buyerUrgency)
        assertTrue(urgent.communalScarcity > calm.communalScarcity)
        assertEquals(urgent.unitPrice * 2.0, urgent.totalPrice, 0.011)
    }

    @Test
    fun bilateralExchangeIsAtomicAndBecomesPriceHistory() {
        val ledger = StockFlowDoubleEntryLedger()
        ledger.mintInitialEndowment("seller", FOOD, 6.0, 1L, "seed-food")
        ledger.mintInitialEndowment("buyer", CREDIT, 10.0, 1L, "seed-credit")

        val quote = SubjectiveMarket.quote(
            ledger = ledger,
            resourceType = FOOD,
            currencyType = CREDIT,
            quantity = 2.0,
            buyerNeed = 0.1,
            buyerInventory = 0.0,
            sellerInventory = 6.0,
            communalInventory = 1.0
        )!!
        val committed = ledger.exchange(
            firstFrom = "seller",
            firstTo = "buyer",
            firstResource = FOOD,
            firstQuantity = 2.0,
            secondFrom = "buyer",
            secondTo = "seller",
            secondResource = CREDIT,
            secondQuantity = quote.totalPrice,
            tick = 2L,
            eventHash = "trade-1"
        )

        assertTrue(committed)
        assertEquals(2.0, ledger.getBalance("buyer", FOOD), 1e-6)
        assertEquals(4.0, ledger.getBalance("seller", FOOD), 1e-6)
        assertEquals(10.0 - quote.totalPrice, ledger.getBalance("buyer", CREDIT), 1e-6)
        assertEquals(quote.totalPrice, ledger.getBalance("seller", CREDIT), 1e-6)
        assertEquals(2, ledger.recentEntries(10).count { it.canonicalEventHash == "trade-1" })
        assertTrue(ledger.verifyZeroSumConservation(FOOD))
        assertTrue(ledger.verifyZeroSumConservation(CREDIT))

        val nextQuote = SubjectiveMarket.quote(
            ledger = ledger,
            resourceType = FOOD,
            currencyType = CREDIT,
            quantity = 2.0,
            buyerNeed = 0.2,
            buyerInventory = 0.0,
            sellerInventory = 6.0,
            communalInventory = 1.0
        )!!
        assertTrue("a cotação seguinte deve incorporar o preço da troca", nextQuote.referenceUnitPrice > 1.5)
    }

    @Test
    fun insufficientCounterpartyBalanceDoesNotTransferEitherAsset() {
        val ledger = StockFlowDoubleEntryLedger()
        ledger.mintInitialEndowment("seller", FOOD, 6.0, 1L, "seed-food")
        ledger.mintInitialEndowment("buyer", CREDIT, 1.0, 1L, "seed-credit")

        val success = ledger.exchange(
            firstFrom = "seller",
            firstTo = "buyer",
            firstResource = FOOD,
            firstQuantity = 2.0,
            secondFrom = "buyer",
            secondTo = "seller",
            secondResource = CREDIT,
            secondQuantity = 3.0,
            tick = 2L,
            eventHash = "trade-rejected"
        )

        assertFalse(success)
        assertEquals(6.0, ledger.getBalance("seller", FOOD), 1e-6)
        assertEquals(0.0, ledger.getBalance("buyer", FOOD), 1e-6)
        assertEquals(1.0, ledger.getBalance("buyer", CREDIT), 1e-6)
        assertEquals(0.0, ledger.getBalance("seller", CREDIT), 1e-6)
        assertTrue(ledger.verifyZeroSumConservation(FOOD))
        assertTrue(ledger.verifyZeroSumConservation(CREDIT))
    }
}
