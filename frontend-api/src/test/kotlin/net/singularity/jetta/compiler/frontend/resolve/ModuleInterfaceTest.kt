package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.SeqType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `.jctx` artifact is what lets a call site link against an already-compiled module without
 * re-resolving its source, so what it round-trips is the contract: the declared type (arity,
 * boxing, the type stamped on the call) and the `Atom`-parameter flavours, neither of which is
 * recoverable from the JVM descriptor.
 */
class ModuleInterfaceTest {

    private fun roundTripType(type: net.singularity.jetta.compiler.frontend.ir.Atom) {
        val text = TypeCodec.render(type)
        assertEquals(type, TypeCodec.parse(text), "round trip of '$text'")
    }

    @Test
    fun `a ground arrow type round-trips`() {
        roundTripType(ArrowType(GroundedType.INT, GroundedType.INT))
    }

    @Test
    fun `a higher-order arrow type round-trips`() {
        roundTripType(
            ArrowType(
                ArrowType(GroundedType.INT, GroundedType.BOOLEAN),
                GroundedType.ATOM,
                GroundedType.EXPRESSION,
            )
        )
    }

    @Test
    fun `a multivalued return — the bag — round-trips`() {
        roundTripType(ArrowType(GroundedType.ATOM, SeqType(GroundedType.INT)))
    }

    @Test
    fun `every ground type round-trips under its own name`() {
        GroundedType.entries.forEach { roundTripType(ArrowType(it, it)) }
    }

    /** `(->)` with no components is hyperon's unit type; FunctionRewriter maps it to `Atom`, but
     *  the codec must not lose its footing on the empty component list either. */
    @Test
    fun `an arrow with no components parses back to an empty arrow`() {
        val empty = ArrowType(emptyList())
        assertEquals(empty, TypeCodec.parse(TypeCodec.render(empty)))
    }

    @Test
    fun `an absent type is null in both directions`() {
        assertEquals(ModuleInterface.ABSENT, TypeCodec.render(null))
        assertNull(TypeCodec.parse(ModuleInterface.ABSENT))
        assertNull(TypeCodec.parse(""))
    }

    @Test
    fun `a full entry round-trips, parameter flavours included`() {
        val entry = ModuleInterfaceEntry(
            name = "filter-atom",
            owner = "hstdlib",
            descriptor = "(Lnet/singularity/jetta/compiler/frontend/ir/Expression;)Ljava/util/List;",
            multivalued = true,
            signature = "(Lnet/singularity/jetta/compiler/frontend/ir/Expression;)Ljava/util/List<Ljava/lang/Integer;>;",
            declaredType = ArrowType(GroundedType.EXPRESSION, SeqType(GroundedType.INT)),
            inertAtomParams = setOf(0),
            templateAtomParams = setOf(2, 1),
        )
        val line = ModuleInterface.render(entry)
        assertEquals(entry, ModuleInterface.parse(line))
        assertEquals(8, line.split('\t').size, "one column per interface field")
    }

    @Test
    fun `an entry with nothing optional round-trips`() {
        val entry = ModuleInterfaceEntry(name = "f", owner = "Prog", descriptor = "(I)I", multivalued = false)
        assertEquals(entry, ModuleInterface.parse(ModuleInterface.render(entry)))
    }

    /**
     * The runtime reads only the first four columns. A table written by an older compiler — four
     * columns, no interface — must still parse, because that is the compatibility direction the
     * artifact promises.
     */
    @Test
    fun `a four-column line from an older compiler still parses`() {
        val entry = ModuleInterface.parse("f\tProg\t(I)I\ttrue")
        assertEquals(
            ModuleInterfaceEntry(name = "f", owner = "Prog", descriptor = "(I)I", multivalued = true),
            entry,
        )
    }

    @Test
    fun `blank and truncated lines are skipped, not guessed at`() {
        assertNull(ModuleInterface.parse(""))
        assertNull(ModuleInterface.parse("   "))
        assertNull(ModuleInterface.parse("f\tProg\t(I)I"))
    }

    @Test
    fun `parseAll skips the blanks a rendered table ends with`() {
        val entries = listOf(
            ModuleInterfaceEntry("f", "Prog", "(I)I", false),
            ModuleInterfaceEntry("g", "Prog", "(I)Ljava/util/List;", true),
        )
        assertEquals(entries, ModuleInterface.parseAll(ModuleInterface.renderAll(entries) + "\n\n"))
    }

    @Test
    fun `an entry hands codegen a JvmMethod with the parameter flavours intact`() {
        val jvm = ModuleInterfaceEntry(
            name = "eqa",
            owner = "hstdlib",
            descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            multivalued = false,
            inertAtomParams = setOf(0, 1),
        ).toJvmMethod()
        assertEquals("hstdlib", jvm.owner)
        assertEquals(setOf(0, 1), jvm.inertAtomParams)
    }
}
