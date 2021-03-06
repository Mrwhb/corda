package net.corda.node.services.upgrade

import net.corda.core.contracts.StateRef
import net.corda.core.contracts.UpgradedContract
import net.corda.core.internal.NamedCacheFactory
import net.corda.core.node.services.ContractUpgradeService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.node.utilities.PersistentMap
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Table

@Suppress("MagicNumber") // database column length
class ContractUpgradeServiceImpl(cacheFactory: NamedCacheFactory) : ContractUpgradeService, SingletonSerializeAsToken() {

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}contract_upgrades")
    class DBContractUpgrade(
            @Id
            @Column(name = "state_ref", length = 176, nullable = false)
            var stateRef: String = "",

            /** refers to the UpgradedContract class name*/
            @Column(name = "contract_class_name", nullable = true)
            var upgradedContractClassName: String? = ""
    )

    private companion object {
        fun createContractUpgradesMap(cacheFactory: NamedCacheFactory): PersistentMap<String, String, DBContractUpgrade, String> {
            return PersistentMap(
                    "ContractUpgradeService_upgrades",
                    toPersistentEntityKey = { it },
                    fromPersistentEntity = { Pair(it.stateRef, it.upgradedContractClassName ?: "") },
                    toPersistentEntity = { key: String, value: String ->
                        DBContractUpgrade().apply {
                            stateRef = key
                            upgradedContractClassName = value
                        }
                    },
                    persistentEntityClass = DBContractUpgrade::class.java,
                    cacheFactory = cacheFactory
            )
        }
    }

    private val authorisedUpgrade = createContractUpgradesMap(cacheFactory)

    fun start() {
        authorisedUpgrade.preload()
    }

    override fun getAuthorisedContractUpgrade(ref: StateRef) = authorisedUpgrade[ref.toString()]

    override fun storeAuthorisedContractUpgrade(ref: StateRef, upgradedContractClass: Class<out UpgradedContract<*, *>>) {
        authorisedUpgrade[ref.toString()] = upgradedContractClass.name
    }

    override fun removeAuthorisedContractUpgrade(ref: StateRef) {
        authorisedUpgrade.remove(ref.toString())
    }
}
