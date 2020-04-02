package net.corda.node.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.millis
import net.corda.core.utilities.seconds
import net.corda.node.services.Permissions
import net.corda.node.services.statemachine.StaffedFlowHospital
import net.corda.nodeapi.internal.persistence.RolledBackDatabaseSessionException
import net.corda.testing.contracts.DummyState
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DummyCommandData
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.PersistenceException
import javax.persistence.Table
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests that I need
 *
 * 1)   No database error works (data saved) ✅
 * 2)   Constraint violation without a flush breaks (no data saved and savepoint released) ✅
 * 3)   Constraint violation with a flush breaks (no data saved and savepoint released) ✅
 * 4)   Constraint violation with a flush that is caught works (no data saved) ✅
 * 5)   Constraint violation with a flush that is caught outside of the entity manager block works (no data saved) ✅
 * 6)   Constraint violation inside a single entity manager breaks (no data saved) ✅
 * 7)   Constraint violation on a single entity when saving multiple entities breaks ✅
 *      (no data saved / test transactionality within an entity manager block)
 * 8)   Constraint violation on a single entity when saving multiple entities breaks works ✅
 *      (no data saved / test transactionality within an entity manager block)
 * 9)   Constraint violation with a flush that is caught inside an entity manager and more data is saved afterwards in a new entity manager (the extra data should be saved) ✅
 * 10)  Constraint violation with a flush that is caught inside an entity manager and more data is saved afterwards in the same entity manager (throws exception) ✅
 * 11)  Constraint violation with a flush that is caught outside an entity manager and more data is saved afterwards in a new entity manager (the extra data should be saved) ✅
 * 12)  Data is only saved when a suspension point is reached ✅
 * 13)  Parameterize all the above tests to have an intermediate commit ✅
 * 14)  Flow can continue processing normally after catching exception inside entity manager ✅
 *
 * I should also test constraint violations within a single entity manager
 *
 * entity manager inside an entity manager?
 */
@RunWith(Parameterized::class)
class FlowEntityManagerTest(var commitStatus: CommitStatus) {

    private companion object {
        val entityWithIdOne = CustomTableEntity(1, "Dan", "This won't work")
        val anotherEntityWithIdOne = CustomTableEntity(1, "Rick", "I'm pretty sure this will work")
        val entityWithIdTwo = CustomTableEntity(2, "Ivan", "This will break existing CorDapps")
        val entityWithIdThree = CustomTableEntity(3, "Some other guy", "What am I doing here?")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data() = listOf(CommitStatus.INTERMEDIATE_COMMIT, CommitStatus.NO_INTERMEDIATE_COMMIT)
    }

    @CordaSerializable
    enum class CommitStatus { INTERMEDIATE_COMMIT, NO_INTERMEDIATE_COMMIT }

    @Before
    fun before() {
        StaffedFlowHospital.onFlowDischarged.clear()
        StaffedFlowHospital.onFlowKeptForOvernightObservation.clear()
    }

    @Test(timeout = 300_000)
    fun `entities can be saved using entity manager without a flush`() {
        // Don't run this test with both parameters to save time
        if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) return
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::EntityManagerSaveEntitiesWithoutAFlushFlow)
                    .returnValue.getOrThrow(Duration.of(10, ChronoUnit.SECONDS))
                assertEquals(0, counter)
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(3, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `entities can be saved using entity manager with a flush`() {
        // Don't run this test with both parameters to save time
        if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) return
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::EntityManagerSaveEntitiesWithAFlushFlow)
                    .returnValue.getOrThrow(Duration.of(10, ChronoUnit.SECONDS))
                assertEquals(0, counter)
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(3, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `entities saved inside an entity manager are only committed when a flow suspends`() {
        // Don't run this test with both parameters to save time
        if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) return
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {

                var beforeCommitEntities: List<CustomTableEntity>? = null
                EntityManagerSaveEntitiesWithoutAFlushFlow.beforeCommitHook = {
                    beforeCommitEntities = it
                }
                var afterCommitEntities: List<CustomTableEntity>? = null
                EntityManagerSaveEntitiesWithoutAFlushFlow.afterCommitHook = {
                    afterCommitEntities = it
                }

                it.proxy.startFlow(::EntityManagerSaveEntitiesWithoutAFlushFlow)
                    .returnValue.getOrThrow(Duration.of(10, ChronoUnit.SECONDS))
                assertEquals(0, counter)
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(3, entities.size)
                assertEquals(0, beforeCommitEntities!!.size)
                assertEquals(3, afterCommitEntities!!.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation without a flush breaks`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                assertFailsWith<TimeoutException> {
                    it.proxy.startFlow(::EntityManagerErrorWithoutAFlushFlow, commitStatus)
                        .returnValue.getOrThrow(Duration.of(10, ChronoUnit.SECONDS))
                }
                assertEquals(3, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(1, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation with a flush breaks`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                assertFailsWith<TimeoutException> {
                    it.proxy.startFlow(::EntityManagerErrorWithAFlushFlow, commitStatus)
                        .returnValue.getOrThrow(Duration.of(10, ChronoUnit.SECONDS))
                }
                assertEquals(3, counter)
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(1, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation with a flush that is caught inside an entity manager block saves no data`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::EntityManagerWithAFlushCatchErrorInsideTheEntityManagerFlow, commitStatus)
                    .returnValue.getOrThrow(20.seconds)
                assertEquals(0, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(1, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation with a flush that is caught outside the entity manager block saves no data`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(::EntityManagerWithAFlushCatchErrorOutsideTheEntityManagerFlow, commitStatus)
                    .returnValue.getOrThrow(20.seconds)
                assertEquals(0, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(1, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation within a single entity manager block throws and saves no data`() {
        // Don't run this test with both parameters to save time
        if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) return
        var dischargeCounter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++dischargeCounter }
        var observationCounter = 0
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ -> ++observationCounter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                assertFailsWith<TimeoutException> {
                    it.proxy.startFlow(::EntityManagerErrorInsideASingleEntityManagerFlow)
                        .returnValue.getOrThrow(20.seconds)
                }
                // Goes straight to observation due to throwing [EntityExistsException]
                assertEquals(0, dischargeCounter)
                assertEquals(1, observationCounter)
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(0, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation on a single entity when saving multiple entities does not save any entities`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                assertFailsWith<TimeoutException> {
                    it.proxy.startFlow(::EntityManagerSavingMultipleEntitiesWithASingleErrorFlow, commitStatus)
                        .returnValue.getOrThrow(20.seconds)
                }
                assertEquals(3, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(1, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation on a single entity when saving multiple entities and catching the error does not save any entities`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(
                    ::EntityManagerSavingMultipleEntitiesWithASingleCaughtErrorFlow,
                    commitStatus
                ).returnValue.getOrThrow(20.seconds)
                assertEquals(0, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(1, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation that is caught inside an entity manager and more data is saved afterwards inside a new entity manager should save the extra data`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(
                    ::EntityManagerCatchErrorAndSaveMoreEntitiesInANewEntityManager,
                    commitStatus
                ).returnValue.getOrThrow(20.seconds)
                assertEquals(0, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(3, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation that is caught inside an entity manager and more data is saved afterwards inside the same entity manager should throw an exception`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                assertFailsWith<RolledBackDatabaseSessionException> {
                    it.proxy.startFlow(
                        ::EntityManagerCatchErrorAndSaveMoreEntitiesInTheSameEntityManager,
                        commitStatus
                    ).returnValue.getOrThrow(20.seconds)
                }
                assertEquals(0, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                when (commitStatus) {
                    CommitStatus.INTERMEDIATE_COMMIT -> assertEquals(1, entities.size)
                    CommitStatus.NO_INTERMEDIATE_COMMIT -> assertEquals(0, entities.size)
                }
            }
        }
    }

    @Test(timeout = 300_000)
    fun `constraint violation that is caught outside an entity manager and more data is saved afterwards inside a new entity manager should save the extra data`() {
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                it.proxy.startFlow(
                    ::EntityManagerCatchErrorOutsideTheEntityManagerAndSaveMoreEntitiesInANewEntityManager,
                    commitStatus
                ).returnValue.getOrThrow(20.seconds)
                assertEquals(0, counter)
                // 1 entity exists to trigger constraint violation
                val entities = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow()
                assertEquals(3, entities.size)
            }
        }
    }

    @Test(timeout = 300_000)
    @Ignore
    fun `constraint violation that is caught inside an entity manager should allow a flow to continue processing as normal`() {
        // Don't run this test with both parameters to save time
        if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) return
        var counter = 0
        StaffedFlowHospital.onFlowDischarged.add { _, _ -> ++counter }
        val user = User("mark", "dadada", setOf(Permissions.all()))
        driver(DriverParameters(startNodesInProcess = true)) {

            val nodeAHandle = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user)).getOrThrow()
            val nodeBHandle = startNode(providedName = BOB_NAME, rpcUsers = listOf(user)).getOrThrow()
            CordaRPCClient(nodeAHandle.rpcAddress).start(user.username, user.password).use {
                val txId =
                    it.proxy.startFlow(::EntityManagerWithFlushCatchAndInteractWithOtherPartyFlow, nodeBHandle.nodeInfo.singleIdentity())
                        .returnValue.getOrThrow(20.seconds)
                assertEquals(0, counter)
                val txFromVault = it.proxy.stateMachineRecordedTransactionMappingSnapshot().firstOrNull()?.transactionId
                assertEquals(txId, txFromVault)
                val entity = it.proxy.startFlow(::GetCustomEntities).returnValue.getOrThrow().single()
                assertEquals(entityWithIdOne, entity)
            }
        }
        assertEquals(0, counter)
    }

    @StartableByRPC
    class EntityManagerSaveEntitiesWithoutAFlushFlow : FlowLogic<Unit>() {

        companion object {
            var beforeCommitHook: ((entities: List<CustomTableEntity>) -> Unit)? = null
            var afterCommitHook: ((entities: List<CustomTableEntity>) -> Unit)? = null
        }

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
                persist(entityWithIdTwo)
                persist(entityWithIdThree)
            }
            beforeCommitHook?.invoke(serviceHub.cordaService(MyService::class.java).getEntities())
            sleep(1.millis)
            afterCommitHook?.invoke(serviceHub.cordaService(MyService::class.java).getEntities())
        }
    }

    @CordaService
    class MyService(private val services: AppServiceHub) : SingletonSerializeAsToken() {

        val executors: ExecutorService = Executors.newFixedThreadPool(1)

        fun getEntities(): List<CustomTableEntity> {
            return executors.submit<List<CustomTableEntity>> {
                services.database.transaction {
                    session.run {
                        val criteria = criteriaBuilder.createQuery(CustomTableEntity::class.java)
                        criteria.select(criteria.from(CustomTableEntity::class.java))
                        createQuery(criteria).resultList
                    }
                }
            }.get()
        }
    }

    @StartableByRPC
    class EntityManagerSaveEntitiesWithAFlushFlow : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
                persist(entityWithIdTwo)
                persist(entityWithIdThree)
                flush()
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerErrorWithoutAFlushFlow(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerErrorWithAFlushFlow(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
                flush()
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerWithAFlushCatchErrorInsideTheEntityManagerFlow(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
                try {
                    flush()
                } catch (e: PersistenceException) {
                    logger.info("Caught the exception!")
                }
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerWithAFlushCatchErrorOutsideTheEntityManagerFlow(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            try {
                serviceHub.withEntityManager {
                    persist(anotherEntityWithIdOne)
                    flush()
                }
            } catch (e: PersistenceException) {
                logger.info("Caught the exception!")
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerErrorInsideASingleEntityManagerFlow : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
                persist(anotherEntityWithIdOne)
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerSavingMultipleEntitiesWithASingleErrorFlow(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
                persist(entityWithIdTwo)
                persist(entityWithIdThree)
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerSavingMultipleEntitiesWithASingleCaughtErrorFlow(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
                persist(entityWithIdTwo)
                persist(entityWithIdThree)
                try {
                    flush()
                } catch (e: PersistenceException) {
                    logger.info("Caught the exception!")
                }
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerCatchErrorAndSaveMoreEntitiesInANewEntityManager(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
                try {
                    flush()
                } catch (e: PersistenceException) {
                    logger.info("Caught the exception!")
                }
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(entityWithIdTwo)
                persist(entityWithIdThree)
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerCatchErrorAndSaveMoreEntitiesInTheSameEntityManager(private val commitStatus: CommitStatus) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
                try {
                    flush()
                } catch (e: PersistenceException) {
                    logger.info("Caught the exception!")
                }
                persist(entityWithIdTwo)
                persist(entityWithIdThree)
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerCatchErrorOutsideTheEntityManagerAndSaveMoreEntitiesInANewEntityManager(private val commitStatus: CommitStatus) :
        FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            try {
                serviceHub.withEntityManager {
                    persist(anotherEntityWithIdOne)
                }
            } catch (e: PersistenceException) {
                logger.info("Caught the exception!")
            }
            if (commitStatus == CommitStatus.INTERMEDIATE_COMMIT) {
                sleep(1.millis)
            }
            serviceHub.withEntityManager {
                persist(entityWithIdTwo)
                persist(entityWithIdThree)
            }
            sleep(1.millis)
        }
    }

    @StartableByRPC
    class EntityManagerWithFlushCatchAndInteractWithOtherPartyFlow(private val party: Party) : FlowLogic<SecureHash>() {

        @Suspendable
        override fun call(): SecureHash {
            serviceHub.withEntityManager {
                persist(entityWithIdOne)
            }
            serviceHub.withEntityManager {
                persist(anotherEntityWithIdOne)
                try {
                    flush()
                } catch (e: PersistenceException) {
                    logger.info("Caught the exception!")
                }
            }
            return subFlow(CreateATransactionFlow(party))
        }
    }

    @InitiatingFlow
    class CreateATransactionFlow(val party: Party) : FlowLogic<SecureHash>() {
        @Suspendable
        override fun call(): SecureHash {
            val session = initiateFlow(party)
            val tx = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first()).apply {
                addOutputState(DummyState(participants = listOf(ourIdentity, party)))
                addCommand(DummyCommandData, ourIdentity.owningKey, party.owningKey)
            }
            val stx = serviceHub.signInitialTransaction(tx)
            val ftx = subFlow(CollectSignaturesFlow(stx, listOf(session)))
            return subFlow(FinalityFlow(ftx, session)).id
        }
    }

    @InitiatedBy(CreateATransactionFlow::class)
    class CreateATransactionResponder(val session: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val stx = subFlow(object : SignTransactionFlow(session) {
                override fun checkTransaction(stx: SignedTransaction) {
                }
            })
            subFlow(ReceiveFinalityFlow(session, stx.id))
        }
    }

    @StartableByRPC
    class GetCustomEntities : FlowLogic<List<CustomTableEntity>>() {
        @Suspendable
        override fun call(): List<CustomTableEntity> {
            return serviceHub.withEntityManager {
                val criteria = criteriaBuilder.createQuery(CustomTableEntity::class.java)
                criteria.select(criteria.from(CustomTableEntity::class.java))
                createQuery(criteria).resultList.also {
                    logger.info("results = $it")
                }
            }
        }
    }

    @Entity
    @Table(name = "custom_table")
    @CordaSerializable
    data class CustomTableEntity constructor(
        @Id
        @Column(name = "id", nullable = false)
        var id: Int,
        @Column(name = "name", nullable = false)
        var name: String,
        @Column(name = "quote", nullable = false)
        var quote: String
    )

    object CustomSchema

    object CustomMappedSchema : MappedSchema(CustomSchema::class.java, 1, listOf(CustomTableEntity::class.java))
}