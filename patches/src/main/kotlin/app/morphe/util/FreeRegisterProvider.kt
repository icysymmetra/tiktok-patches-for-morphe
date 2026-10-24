/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.util

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.util.FreeRegisterProvider.Companion.conditionalBranchOpcodes
import app.morphe.util.FreeRegisterProvider.Companion.logFreeRegisterSearch
import app.morphe.util.FreeRegisterProvider.Companion.returnOpcodes
import app.morphe.util.FreeRegisterProvider.Companion.switchOpcodes
import app.morphe.util.FreeRegisterProvider.Companion.unconditionalBranchOpcodes
import app.morphe.util.FreeRegisterProvider.Companion.writeOpcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcode.*
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.util.EnumSet

/**
 * Finds free registers at a specific index in a method.
 * Allows allocating multiple free registers for a given index.
 *
 * If you only need a single free register, instead use [findFreeRegister].
 *
 * @param index Index you need a use a free register at.
 * @param numberOfFreeRegistersNeeded The maximum number of free registers you may get using
 *                                    [FreeRegisterProvider.getFreeRegister].
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 *
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every register
 *                                  before any registers are wrote to, or if no register survives
 *                                  every arm of a switch or branch after it.
 */
fun Method.getFreeRegisterProvider(index: Int, numberOfFreeRegistersNeeded: Int, registersToExclude: List<Int>) =
    FreeRegisterProvider(this, index, numberOfFreeRegistersNeeded, registersToExclude)

/**
 * Finds free registers at a specific index in a method.
 * Allows allocating multiple free registers for a given index.
 *
 * If you only need a single free register, instead use [findFreeRegister].
 *
 * @param index Index you need a use a free register at.
 * @param numberOfFreeRegistersNeeded The minimum free registers to find.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 *
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every register
 *                                  before any registers are wrote to, or if no register survives
 *                                  every arm of a switch or branch after it.
 */
fun Method.getFreeRegisterProvider(index: Int, numberOfFreeRegistersNeeded: Int, vararg registersToExclude: Int) =
    FreeRegisterProvider(this, index, numberOfFreeRegistersNeeded, *registersToExclude)

class FreeRegisterProvider internal constructor(
    val method: Method,
    index: Int,
    numberOfFreeRegistersNeeded: Int,
    registersToExclude: List<Int>
) {
    internal constructor(
        method: Method,
        index: Int,
        numberOfFreeRegistersNeeded: Int,
        vararg registersToExclude: Int
    ) : this(method, index, numberOfFreeRegistersNeeded, registersToExclude.toList())

    // Cannot use MutableList because removeFirst() crashes on older Android devices.
    // https://youtrack.jetbrains.com/issue/KT-71375/
    private var freeRegisters: ArrayDeque<Int> = ArrayDeque(
        method.findFreeRegisters(index, numberOfFreeRegistersNeeded, registersToExclude)
    )

    private val originallyExcludedRegisters = registersToExclude
    private val allocatedFreeRegisters = mutableListOf<Int>()

    /**
     * Returns a free register and removes it from the available list.
     *
     * @return A free register number
     * @throws IllegalStateException if no free registers are available
     */
    fun getFreeRegister(): Int {
        if (freeRegisters.isEmpty()) {
            throw IllegalStateException("No free registers available")
        }
        val register = freeRegisters.removeFirst()
        allocatedFreeRegisters.add(register)
        return register
    }

    /**
     * Returns a free 4-bit register (v0-v15) and removes it from the available list.
     *
     * @return A free register number
     * @throws IllegalStateException if no free 4-bit registers are available.
     */
    fun getFreeRegister4Bit(): Int {
        val register = getFreeRegister()
        if (register >= 16) {
            throw IllegalStateException("Lowest free register is: $register")
        }
        return register
    }

    /**
     * Returns all registers that have been allocated via [getFreeRegister].
     * This does not include the originally excluded registers.
     *
     * @return List of registers that have been allocated, in allocation order
     */
    fun getAllocatedFreeRegisters(): List<Int> = allocatedFreeRegisters.toList()

    /**
     * Returns all registers that are considered "in use" or unsafe to use. This includes the
     * excluded registers originally passed in, all registers that are unsuitable to use
     * (registers are read from by the original code), and all free registers previously provided
     * by this class using [getFreeRegister].
     *
     * @return List of all registers that are unsafe to use at this time.
     */
    fun getUsedAndUnAvailableRegisters(): List<Int> {
        val allRegisters = 0 until method.implementation!!.registerCount
        return (allocatedFreeRegisters + originallyExcludedRegisters + (allRegisters - freeRegisters.toSet()))
            .distinct()
    }

    /**
     * @return The number of free registers still available.
     */
    fun availableCount(): Int = freeRegisters.size

    /**
     * Checks if there are any free registers available.
     */
    fun hasFreeRegisters(): Boolean = freeRegisters.isNotEmpty()

    internal companion object {
        val conditionalBranchOpcodes: EnumSet<Opcode> = EnumSet.of(
            IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE,
            IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ
        )

        val unconditionalBranchOpcodes: EnumSet<Opcode> = EnumSet.of(
            GOTO, GOTO_16, GOTO_32
        )

        val switchOpcodes: EnumSet<Opcode> = EnumSet.of(
            PACKED_SWITCH, SPARSE_SWITCH
        )

        val returnOpcodes: EnumSet<Opcode> = EnumSet.of(
            RETURN_VOID, RETURN, RETURN_WIDE, RETURN_OBJECT, RETURN_VOID_NO_BARRIER,
            THROW
        )

        val writeOpcodes: EnumSet<Opcode> = EnumSet.of(
            ARRAY_LENGTH,
            INSTANCE_OF,
            NEW_INSTANCE, NEW_ARRAY,
            MOVE, MOVE_FROM16, MOVE_16, MOVE_WIDE, MOVE_WIDE_FROM16, MOVE_WIDE_16, MOVE_OBJECT,
            MOVE_OBJECT_FROM16, MOVE_OBJECT_16, MOVE_RESULT, MOVE_RESULT_WIDE, MOVE_RESULT_OBJECT, MOVE_EXCEPTION,
            CONST, CONST_4, CONST_16, CONST_HIGH16, CONST_WIDE_16, CONST_WIDE_32,
            CONST_WIDE, CONST_WIDE_HIGH16, CONST_STRING, CONST_STRING_JUMBO,
            CONST_CLASS,
            IGET, IGET_WIDE, IGET_OBJECT, IGET_BOOLEAN, IGET_BYTE, IGET_CHAR, IGET_SHORT,
            IGET_VOLATILE, IGET_WIDE_VOLATILE, IGET_OBJECT_VOLATILE,
            SGET, SGET_WIDE, SGET_OBJECT, SGET_BOOLEAN, SGET_BYTE, SGET_CHAR, SGET_SHORT,
            SGET_VOLATILE, SGET_WIDE_VOLATILE, SGET_OBJECT_VOLATILE,
            AGET, AGET_WIDE, AGET_OBJECT, AGET_BOOLEAN, AGET_BYTE, AGET_CHAR, AGET_SHORT,
            // Arithmetic and logical operations.
            ADD_DOUBLE_2ADDR, ADD_DOUBLE, ADD_FLOAT_2ADDR, ADD_FLOAT, ADD_INT_2ADDR,
            ADD_INT_LIT8, ADD_INT, ADD_LONG_2ADDR, ADD_LONG, ADD_INT_LIT16,
            AND_INT_2ADDR, AND_INT_LIT8, AND_INT_LIT16, AND_INT, AND_LONG_2ADDR, AND_LONG,
            DIV_DOUBLE_2ADDR, DIV_DOUBLE, DIV_FLOAT_2ADDR, DIV_FLOAT, DIV_INT_2ADDR,
            DIV_INT_LIT16, DIV_INT_LIT8, DIV_INT, DIV_LONG_2ADDR, DIV_LONG,
            DOUBLE_TO_FLOAT, DOUBLE_TO_INT, DOUBLE_TO_LONG,
            FLOAT_TO_DOUBLE, FLOAT_TO_INT, FLOAT_TO_LONG,
            INT_TO_BYTE, INT_TO_CHAR, INT_TO_DOUBLE, INT_TO_FLOAT, INT_TO_LONG, INT_TO_SHORT,
            LONG_TO_DOUBLE, LONG_TO_FLOAT, LONG_TO_INT,
            MUL_DOUBLE_2ADDR, MUL_DOUBLE, MUL_FLOAT_2ADDR, MUL_FLOAT, MUL_INT_2ADDR,
            MUL_INT_LIT16, MUL_INT_LIT8, MUL_INT, MUL_LONG_2ADDR, MUL_LONG,
            NEG_DOUBLE, NEG_FLOAT, NEG_INT, NEG_LONG,
            NOT_INT, NOT_LONG,
            OR_INT_2ADDR, OR_INT_LIT16, OR_INT_LIT8, OR_INT, OR_LONG_2ADDR, OR_LONG,
            REM_DOUBLE_2ADDR, REM_DOUBLE, REM_FLOAT_2ADDR, REM_FLOAT, REM_INT_2ADDR,
            REM_INT_LIT16, REM_INT_LIT8, REM_INT, REM_LONG_2ADDR, REM_LONG,
            RSUB_INT_LIT8, RSUB_INT,
            SHL_INT_2ADDR, SHL_INT_LIT8, SHL_INT, SHL_LONG_2ADDR, SHL_LONG,
            SHR_INT_2ADDR, SHR_INT_LIT8, SHR_INT, SHR_LONG_2ADDR, SHR_LONG,
            SUB_DOUBLE_2ADDR, SUB_DOUBLE, SUB_FLOAT_2ADDR, SUB_FLOAT, SUB_INT_2ADDR,
            SUB_INT, SUB_LONG_2ADDR, SUB_LONG,
            USHR_INT_2ADDR, USHR_INT_LIT8, USHR_INT, USHR_LONG_2ADDR, USHR_LONG,
            XOR_INT_2ADDR, XOR_INT_LIT16, XOR_INT_LIT8, XOR_INT, XOR_LONG_2ADDR, XOR_LONG,
            // Comparisons. Each reads two wide or two float values and writes one narrow
            // register with -1, 0 or 1. Leaving them out made writeRegister answer null for
            // them, so nothing here knew they write at all: the free-register search never
            // saw the register they free, and the literal walk never saw the literal they
            // destroy.
            CMP_LONG, CMPG_DOUBLE, CMPG_FLOAT, CMPL_DOUBLE, CMPL_FLOAT,
        )

        /**
         * For debugging and development.
         */
        internal const val logFreeRegisterSearch = false
    }
}

/**
 * Starting from and including the instruction at index [index],
 * finds the next register that is written to and not read from. If a return instruction
 * is encountered, then the lowest unused register is returned.
 *
 * This method should work for all situations including inserting at a branch statement,
 * but this may not work if the index is at or just before a branch, or if the branch
 * paths have no common free registers.
 *
 * If you need multiple free registers, then instead use [Method.getFreeRegisterProvider].
 *
 * @param index Index you need a use a free register at.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 * @return The lowest register number (usually a 4-bit register) that is free at the given index.
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every register
 *                                  before any registers are wrote to, or if no register survives
 *                                  every arm of a switch or branch after it, or if the index is
 *                                  at/before a branch statement and the method has an unusually high
 *                                  amount of branching where no common free registers exist in both branch paths.
 */
fun Method.findFreeRegister(
    index: Int,
    vararg registersToExclude: Int
) = findFreeRegisters(
    startIndex = index,
    numberOfFreeRegistersNeeded = 1,
    registersToExclude = registersToExclude.toList()
).first()

/**
 * Starting from and including the instruction at index [index],
 * finds the next register that is written to and not read from. If a return instruction
 * is encountered, then the lowest unused register is returned.
 *
 * This method should work for all situations including inserting at a branch statement,
 * but this may not work if the index is at or just before a branch, or if the branch
 * paths have no common free registers.
 *
 * If you need multiple free registers, then instead use [Method.getFreeRegisterProvider].
 *
 * @param index Index you need a use a free register at.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 * @return The lowest register number (usually a 4-bit register) that is free at the given index.
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every register
 *                                  before any registers are wrote to, or if no register survives
 *                                  every arm of a switch or branch after it, or if the index is
 *                                  at/before a branch statement and the method has an unusually high
 *                                  amount of branching where no common free registers exist in both branch paths.
 */
fun Method.findFreeRegister(
    index: Int,
    registersToExclude: List<Int>
) = findFreeRegisters(
    startIndex = index,
    numberOfFreeRegistersNeeded = 1,
    registersToExclude = registersToExclude
).first()

private fun Method.findFreeRegisters(
    startIndex: Int,
    numberOfFreeRegistersNeeded: Int,
    registersToExclude: List<Int>
): List<Int> {
    if (logFreeRegisterSearch) println(" Searching startIndex: $startIndex method: $this")

    val freeRegisters = findFreeRegistersInternal(
        startIndex = startIndex,
        numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
        currentDepth = 0,
        foundFreeRegistersAtIndex = mutableMapOf(),
        registersToExclude = registersToExclude,
        offsetArray = buildInstructionOffsetArray()
    )

    if (freeRegisters.isEmpty()) {
        // Should only happen when nothing is free down every path out of the start index.
        throw IllegalArgumentException("Could not find a free register from startIndex: " +
                "$startIndex excluding: $registersToExclude")
    }

    if (logFreeRegisterSearch) println(" final free registers found: $freeRegisters")

    // 4-bit registers first, and the lowest of each class first within it. The comparator
    // here used to answer 0 for any two registers of the same class, and the set behind it
    // keeps discovery order, so what came back was the first one found rather than the lowest
    // one, which is what every caller reads and what the documentation above promises.
    return freeRegisters.sortedWith(compareBy({ it >= 16 }, { it }))
}

/**
 * Returns all free registers found starting from [startIndex].Follows branches up to [maxDepth].
 *
 * @param startIndex Inclusive starting index.
 * @param numberOfFreeRegistersNeeded The minimum free registers to ensure will be returned.
 * @param currentDepth Current branching depth. Value of zero means no branching has been followed yet.
 * @param foundFreeRegistersAtIndex Map of instruction index to list of free registers previously found.
 * @param registersToExclude Registers to exclude from consideration.
 * @param offsetArray Map from instruction index to code offset.
 * @return List of all free registers found.
 */
private fun Method.findFreeRegistersInternal(
    startIndex: Int,
    numberOfFreeRegistersNeeded: Int,
    currentDepth: Int,
    foundFreeRegistersAtIndex: MutableMap<Int, Set<Int>?>,
    registersToExclude: List<Int>,
    offsetArray: IntArray
): List<Int> {
    check(implementation != null) {
        "Method has no implementation: $this"
    }
    check(startIndex >= 0 && startIndex < instructions.count()) {
        "startIndex out of bounds: $startIndex methodInstructionCount: ${instructions.count()}"
    }
    check(numberOfFreeRegistersNeeded > 0) {
        "numberOfFreeRegistersNeeded must be greater than zero: $numberOfFreeRegistersNeeded"
    }

    fun Collection<Int>.numberOf4BitRegisters() = this.count { it < 16 }

    if (foundFreeRegistersAtIndex.containsKey(startIndex)) {
        // Null means this index is still being explored further up the stack, which is what a
        // loop looks like from here. Its answer is half worked out, so there is nothing honest
        // to hand back: the registers it has recorded so far are the ones proved free before
        // the branch, not the ones free around the whole loop.
        val explored = foundFreeRegistersAtIndex[startIndex] ?: return emptyList()
        // A branch index that has already been explored to the end.
        return (explored - registersToExclude.toSet()).toList()
    }
    foundFreeRegistersAtIndex[startIndex] = null

    val usedRegisters = registersToExclude.toMutableSet()
    val freeRegisters = mutableSetOf<Int>()

    /**
     * Records the whole answer for this index, not the part of it worked out before a branch.
     *
     * <p>The set used to be published as it was being filled. A frame that ends in a branch
     * returns its own registers plus what the branches agree on, and only the first half was
     * ever memoised, so the second visitor to that index got less than the truth and a search
     * that should have found four registers found one.
     */
    fun remember(answer: List<Int>): List<Int> {
        foundFreeRegistersAtIndex[startIndex] = answer.toSet()
        return answer
    }

    for (i in startIndex until instructions.count()) {
        val instruction = getInstruction(i)
        val instructionRegisters = instruction.registersUsedIncludingWideHalves

        // Check for write-only register.
        val writeRegister = instruction.writeRegister
        if (writeRegister != null && writeRegister !in usedRegisters) {
            // Check if this register is ONLY written to (not also read)
            // Count occurrences of writeRegister in instructionRegisters.
            val occurrences = instructionRegisters.count { it == writeRegister }
            // An opcode like add-int/2addr writes its destination and reads it in the same
            // breath, but the destination appears once in the register list, so counting
            // occurrences alone called it write-only and handed out a register the host was
            // still accumulating into.
            val readsItsDestination = instruction.opcode.name.endsWith("/2addr")
            // A wide write covers two registers, and only the low half is named. Handing the
            // named one out is handing out half of a value the host is about to read back.
            val writesAPair = instruction.touchesWideRegisters
            // If it appears only once, it's write-only (to write).
            // If it appears more than once, it's also read.
            if (occurrences <= 1 && !readsItsDestination && !writesAPair) {
                if (logFreeRegisterSearch) println(" found free register at $i: $writeRegister " +
                        "opcode: " + instruction.opcode + " reference: " + (instruction.getReference()))
                freeRegisters.add(writeRegister)
                // If the requested number of free registers is found and this is not a branch,
                // then no additional searching is needed.
                // But if this is a branch, then this all free registers should be found
                // because the intersection of free registers from different branches may be
                // less than the requested number of registers.
                if (currentDepth == 0 && freeRegisters.numberOf4BitRegisters() >= numberOfFreeRegistersNeeded) {
                    return remember(freeRegisters.toList())
                }
            }
        }

        // Mark all registers used by this instruction as "used".
        usedRegisters.addAll(instructionRegisters)

        // If we hit a return, all unused registers on this path are free.
        if (instruction.isReturnInstruction) {
            val allRegisters = (0 until implementation!!.registerCount).toList()
            val unusedRegisters = allRegisters - usedRegisters
            freeRegisters.addAll(unusedRegisters)
            if (logFreeRegisterSearch) println(" encountered return index: $i and found: $freeRegisters")
            return remember(freeRegisters.toList())
        }

        if (instruction.isSwitchInstruction) {
            if (logFreeRegisterSearch) println(" encountered switch index: $i opcode: " + instruction.opcode)

            // A switch used to end the search here, and everything after it was given up on.
            // That is safe, but it hands back only what was proved free before the switch, and
            // when that is nothing the caller is told the method cannot be patched at all. It is
            // the same shape as a conditional branch with more than two arms: a register is free
            // at the switch when it is free down every arm and down the fall-through, so the
            // answer is the intersection of all of them. R8 turns a chain of string comparisons
            // into one of these, so a method that is an if-else chain in one TikTok build is a
            // packed switch in the next and nothing about the patch has changed.
            val paths = switchPathIndices(instruction, i, offsetArray)
            if (paths == null) {
                // A payload this could not read. Fall back to the old leaf-node answer rather
                // than guess at control flow, so an unreadable switch is no worse than before.
                if (logFreeRegisterSearch) println(" switch payload unreadable, treating as a leaf")
                return remember(freeRegisters.toList())
            }

            val usedRegistersList = usedRegisters.toList()
            var shared: Set<Int>? = null
            for (path in paths) {
                val pathFreeRegisters = findFreeRegistersInternal(
                    startIndex = path,
                    numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                    currentDepth = currentDepth + 1,
                    foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                    registersToExclude = usedRegistersList,
                    offsetArray = offsetArray
                )
                if (logFreeRegisterSearch) println(" switch arm $path registers: $pathFreeRegisters")
                shared = shared?.intersect(pathFreeRegisters.toSet()) ?: pathFreeRegisters.toSet()
                // Nothing survives every arm, so no later arm can put anything back.
                if (shared.isEmpty()) break
            }

            return remember((freeRegisters + (shared ?: emptySet())).toList())
        }

        if (instruction.isUnconditionalBranchInstruction) {
            if (logFreeRegisterSearch) println(" encountered unconditional branch index: $i opcode: " + instruction.opcode)

            // Continue searching from the go-to index.
            return remember((freeRegisters + findFreeRegistersInternal(
                startIndex = getBranchTargetInstructionIndex(instruction, i, offsetArray),
                numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                currentDepth = currentDepth, // Same depth since it's a continuation of single path.
                foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                registersToExclude = usedRegisters.toList(),
                offsetArray = offsetArray
            )).toList())
        }

        if (instruction.isConditionalBranchInstruction) {
            if (logFreeRegisterSearch) println(" encountered conditional branch index: $i opcode: " + instruction.opcode)
            val usedRegistersList = usedRegisters.toList()

            val branchFreeRegisters = findFreeRegistersInternal(
                startIndex = getBranchTargetInstructionIndex(instruction, i, offsetArray),
                numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                currentDepth = currentDepth + 1,
                foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                registersToExclude = usedRegistersList,
                offsetArray = offsetArray
            )
            if (logFreeRegisterSearch) println(" branch registers: $branchFreeRegisters")

            val fallThruFreeRegisters = findFreeRegistersInternal(
                startIndex = i + 1,
                numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                currentDepth = currentDepth + 1,
                foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                registersToExclude = usedRegistersList,
                offsetArray = offsetArray
            )
            if (logFreeRegisterSearch) println(" fall thru registers: $fallThruFreeRegisters")

            return remember(
                (freeRegisters + branchFreeRegisters.intersect(fallThruFreeRegisters.toSet()))
                    .toList()
            )
        }
    }

    // A return or branch instruction will be encountered before all instructions can be iterated.
    // Some methods have switch payload instructions after the last actual instruction,
    // but these cannot be reached through normal control flow.
    throw IllegalArgumentException("Start index is outside normal control flow: $startIndex")
}

private fun Method.buildInstructionOffsetArray(): IntArray {
    val instructionCount = instructions.count()
    val offsetArray = IntArray(instructionCount) { -1 }
    var currentOffset = 0

    for (i in 0 until instructionCount) {
        val instruction = getInstruction(i)

        // A payload gets no offset of its own, because nothing branches to one directly: the
        // switch instruction that owns it does. It still occupies code units though, and
        // stepping over it without counting them left every later instruction at an offset
        // short by however long the payload was, so a branch past it resolved to the wrong
        // instruction or to none at all.
        if (!instruction.opcode.format.isPayloadFormat) {
            offsetArray[i] = currentOffset
        }
        currentOffset += instruction.codeUnits
    }

    return offsetArray
}

/**
 * Every instruction index control flow can reach from a packed or sparse switch: one per arm of
 * its payload, plus the fall-through that runs when no key matched.
 *
 * <p>Both halves take resolving. The payload is not in [offsetArray], because nothing branches
 * to a payload except the switch that owns it, so its offset is found by walking code units. The
 * arm offsets inside the payload are relative to the switch instruction, not to the payload.
 *
 * @return The reachable indices, or null if the payload could not be read as one.
 */
private fun Method.switchPathIndices(
    instruction: Instruction,
    index: Int,
    offsetArray: IntArray
): List<Int>? {
    if (instruction !is OffsetInstruction) return null
    val switchOffset = offsetArray[index]
    if (switchOffset < 0) return null

    val payload = instructionAtCodeOffset(switchOffset + instruction.codeOffset) as? SwitchPayload
        ?: return null

    val paths = LinkedHashSet<Int>()
    for (element in payload.switchElements) {
        paths.add(findInstructionIndexByOffset(switchOffset + element.offset, offsetArray))
    }
    // The fall-through is a path like any other, and it is the one every key that matched
    // nothing takes. Leaving it out would call a register free that the default arm writes to.
    val fallThrough = index + 1
    if (fallThrough < instructions.count()) paths.add(fallThrough)

    return paths.toList()
}

/**
 * The instruction at a code offset, payloads included.
 *
 * <p>[findInstructionIndexByOffset] deliberately cannot answer this: its array leaves payloads at
 * -1 so an ordinary branch can never resolve into one. A switch is the one thing that does.
 */
private fun Method.instructionAtCodeOffset(codeOffset: Int): Instruction? {
    var offset = 0
    for (i in 0 until instructions.count()) {
        val instruction = getInstruction(i)
        if (offset == codeOffset) return instruction
        if (offset > codeOffset) return null
        offset += instruction.codeUnits
    }
    return null
}

/**
 * Returns an instruction index for a given branch instruction.
 *
 * @param instruction The branch instruction
 * @param index Current instruction index
 * @param offsetArray Array mapping instruction index to code offset.
 */
private fun Method.getBranchTargetInstructionIndex(
    instruction: Instruction,
    index: Int,
    offsetArray: IntArray
): Int {
    // Not `index > 0`: a method whose first instruction is a branch is ordinary, and this
    // threw on it rather than following it.
    check(index >= 0 && index < offsetArray.size) {
        "Invalid index: $index"
    }
    val currentOffset = offsetArray[index]

    return when (instruction.opcode) {
        GOTO, GOTO_16, GOTO_32,
        IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE,
        IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ -> {
            val offset = (instruction as OffsetInstruction).codeOffset
            val targetOffset = currentOffset + offset
            // Find the instruction index at this offset.
            findInstructionIndexByOffset(targetOffset, offsetArray)
        }
        // A switch jumps to a payload that carries its own target list, which is more than one
        // answer and does not fit here. The switch handler resolves those itself.
        else -> throw IllegalStateException("Unsupported opcode: ${instruction.opcode}")
    }
}

/**
 * Finds the instruction index for a given code offset.
 *
 * @param targetOffset Target code offset in 16-bit units
 * @param offsetArray Array mapping instruction index to code offset (-1 for payloads)
 * @return Instruction index at the target offset, or null if not found
 */
private fun Method.findInstructionIndexByOffset(
    targetOffset: Int,
    offsetArray: IntArray
): Int {
    // Simple linear search using indexOfFirst
    val index = offsetArray.indexOfFirst { it == targetOffset }
    if (index >= 0) {
        return index
    }

    // Should never happen.
    // Code has been tested on hundreds of random methods on all instruction indices,
    // but maybe some weird code exists that this has overlooked.
    throw IllegalArgumentException("Could not find exact instruction offset for method: " +
            "$this at offset: $targetOffset. Please file a bug report in the Morphe patches repo"
    )
}

/**
 * @return The registers used by this instruction.
 */
/**
 * Whether this opcode's registers hold a long or a double, each of which occupies the register
 * named and the one above it.
 *
 * <p>Deliberately over-wide: it names every opcode whose mnemonic mentions a wide value at all,
 * and both halves of every register such an opcode touches are then treated as busy, including
 * the narrow ones (the destination of long-to-int, say). Marking a neighbour as busy costs at
 * most one register that was in fact free. The other direction hands out the high half of a
 * live long, and the host comes back to a number nobody wrote.
 */
internal val Instruction.touchesWideRegisters: Boolean
    get() = opcode.name.let { name ->
        name.contains("wide") || name.contains("long") || name.contains("double")
    }

/** The result types that fit in one register, named after the last "-to-" of a conversion. */
private val NARROW_RESULT_TYPES = setOf("int", "float", "byte", "char", "short")

/**
 * Whether the register this instruction writes is the low half of a pair.
 *
 * <p>[touchesWideRegisters] is deliberately over-wide, which is right where the cost is one
 * register wrongly called busy and wrong where the cost is refusing to patch. Two families
 * mention a wide value and answer in a single register: a conversion away from one, where the
 * destination type is whatever follows the last "-to-", and a comparison of two, which answers
 * with -1, 0 or 1. Reading long-to-int as a wide write made the literal walk give up on a
 * method where nothing had touched the literal at all.
 */
internal val Instruction.writesAWideRegister: Boolean
    get() {
        val name = opcode.name
        if (name.startsWith("cmp")) return false
        val convertsAt = name.lastIndexOf("-to-")
        if (convertsAt >= 0) return name.substring(convertsAt + 4) !in NARROW_RESULT_TYPES
        return touchesWideRegisters
    }

/** [registersUsed], with the high half of each register for an opcode that works in pairs. */
internal val Instruction.registersUsedIncludingWideHalves: List<Int>
    get() {
        val named = registersUsed
        return if (touchesWideRegisters) named + named.map { it + 1 } else named
    }

val Instruction.registersUsed: List<Int>
    get() = when (this) {
        is FiveRegisterInstruction -> {
            when (registerCount) {
                0 -> listOf()
                1 -> listOf(registerC)
                2 -> listOf(registerC, registerD)
                3 -> listOf(registerC, registerD, registerE)
                4 -> listOf(registerC, registerD, registerE, registerF)
                else -> listOf(registerC, registerD, registerE, registerF, registerG)
            }
        }

        is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        is RegisterRangeInstruction -> (startRegister until (startRegister + registerCount)).toList()
        else -> emptyList()
    }

/**
 * @return The register that is written to by this instruction,
 *         or NULL if this is not a write opcode.
 */
val Instruction.writeRegister: Int?
    get() {
        if (this.opcode !in writeOpcodes) {
            return null
        }
        if (this !is OneRegisterInstruction) {
            throw IllegalStateException("Not a write instruction: $this")
        }
        return registerA
    }

/**
 * This differs from [isUnconditionalBranchInstruction] in that it does not include unconditional goto.
 *
 * @return If this instruction is a conditional branch (multiple branch paths).
 */
internal val Instruction.isConditionalBranchInstruction: Boolean
    get() = this.opcode in conditionalBranchOpcodes

/**
 * @return If this instruction is a GOTO opcode.
 */
internal val Instruction.isUnconditionalBranchInstruction: Boolean
    get() = this.opcode in unconditionalBranchOpcodes

/**
* @return If this instruction is a switch opcode.
*/
internal val Instruction.isSwitchInstruction: Boolean
    get() = this.opcode in switchOpcodes

/**
 * @return If this instruction returns or throws.
 */
internal val Instruction.isReturnInstruction: Boolean
    get() = this.opcode in returnOpcodes
