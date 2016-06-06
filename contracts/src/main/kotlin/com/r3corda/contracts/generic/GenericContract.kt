package com.r3corda.contracts.generic

import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.SecureHash

/**
 * Created by sofusmortensen on 23/05/16.
 */

val GENERIC_PROGRAM_ID = GenericContract()

class GenericContract : Contract {

    data class State(override val notary: Party,
                     val details: Kontract) : ContractState {
        override val contract = GENERIC_PROGRAM_ID
    }

    interface Commands : CommandData {

        // transition according to business rules defined in contract
        data class Action(val name: String) : Commands

        // replace parties
        // must be signed by all parties present in contract before and after command
        class Move : TypeOnlyCommandData(), Commands

        // must be signed by all parties present in contract
        class Issue : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: TransactionForVerification) {

        requireThat {
            "transaction has a single command".by (tx.commands.size == 1 )
        }

        val cmd = tx.commands.requireSingleCommand<GenericContract.Commands>()

        val outState = tx.outStates.single() as State

        val value = cmd.value

        when (value) {
            is Commands.Action -> {
                val inState = tx.inStates.single() as State
                val actions = actions(inState.details)
                val actions2 = actions2(inState.details)
                requireThat {
                    "action must be defined" by ( actions2.containsKey(value.name) )
                    "action must be authorized" by ( cmd.signers.any { actions[ value.name ]!!.contains(it) } )
                    "output state must match action result state" by ( actions2[ value.name ]!!.kontract.equals(outState.details))
                }
            }
            is Commands.Issue -> {
                requireThat {
                    "the transaction is signed by all involved parties" by ( liableParties(outState.details).all { it in cmd.signers } )
                    "the transaction has no input states" by tx.inStates.isEmpty()
                }
            }
            else -> throw IllegalArgumentException("Unrecognised command")
        }

    }

    override val legalContractReference: SecureHash
        get() = throw UnsupportedOperationException()

    fun generateIssue(tx: TransactionBuilder, kontract: Kontract, at: PartyAndReference, notary: Party) {
        check(tx.inputStates().isEmpty())
        tx.addOutputState( State(notary, kontract) )
        tx.addCommand(Commands.Issue(), at.party.owningKey)
    }
}

