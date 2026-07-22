package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Predefined
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import java.util.concurrent.atomic.AtomicLong

/**
 * MeTTa's runtime type-inference engine (phase D0). MeTTa types are ordinary `Atom` terms
 * (`Number`, `Type`, `Bool`, `%Undefined%`, `Either`, `(Vec $t $x)` …), NOT the erased JVM
 * [net.singularity.jetta.compiler.frontend.ir.GroundedType] layer used by codegen. So the engine
 * works purely over `Symbol`/`Special`/`Variable`/`Grounded`/`Expression` and a list of the
 * `:`-type facts currently in the running program's own space.
 *
 * Two shapes:
 *  - [inferType] — the inferred type term of an atom, or `null` when it is ill-typed.
 *  - [unify] — Robinson unification of two type terms over a variable→term substitution.
 *
 * The `get-type` builtin ([net.singularity.jetta.runtime.JettaProgram]) is a thin wrapper: it
 * calls [inferType] with the `&self` atoms and lifts the result into a `List<Atom>` bag (empty =
 * ill-typed = the `()` empty-set the reference suite asserts).
 */
object TypeEngine {

    private val NUMBER = Symbol("Number")
    private val STRING = Symbol("String")
    private val BOOL = Symbol("Bool")
    private val UNDEF = Symbol("%Undefined%")
    private const val UNDEF_NAME = "%Undefined%"

    /** Monotonic source of globally-unique variable suffixes for [instantiate]. */
    private val freshCounter = AtomicLong(0)

    // --- surface-name helper --------------------------------------------------------------

    /**
     * The surface text of a leaf. A `Symbol` and a `Special` with the same text (notably the
     * arrow `->`, stored as `Special` in a `:` fact but potentially reached as either) denote
     * the same type-level name, so all name comparisons go through this rather than `==`.
     */
    private fun nameOf(a: Atom): String? = when (a) {
        is Symbol -> a.name
        is Special -> a.value
        else -> null
    }

    private fun isUndef(a: Atom): Boolean = nameOf(a) == UNDEF_NAME

    // --- unification ----------------------------------------------------------------------

    /** Resolve [a] through the substitution [s] until it is not a bound variable. */
    private fun walk(a: Atom, s: Map<String, Atom>): Atom =
        if (a is Variable && s.containsKey(a.name)) walk(s[a.name]!!, s) else a

    /**
     * Robinson unification. [s] maps a type-variable NAME to its bound term; unification extends
     * it in place. `%Undefined%` is the gradual-typing wildcard — it unifies with anything and
     * binds nothing (this is what makes `(: Left (-> %Undefined% Either))` accept any argument).
     * The occurs-check is omitted: the declared types in the suite are non-recursive in their
     * variables, and fresh instantiation ([instantiate]) already prevents cross-use capture.
     */
    fun unify(a0: Atom, b0: Atom, s: MutableMap<String, Atom>): Boolean {
        val a = walk(a0, s)
        val b = walk(b0, s)
        if (isUndef(a) || isUndef(b)) return true
        if (a is Variable) {
            if (!(b is Variable && b.name == a.name)) s[a.name] = b
            return true
        }
        if (b is Variable) {
            s[b.name] = a
            return true
        }
        return when {
            a is Expression && b is Expression ->
                a.atoms.size == b.atoms.size && a.atoms.indices.all { unify(a.atoms[it], b.atoms[it], s) }
            a is Expression || b is Expression -> false
            a is Grounded<*> && b is Grounded<*> -> a.value == b.value
            a is Grounded<*> || b is Grounded<*> -> false
            else -> nameOf(a) != null && nameOf(a) == nameOf(b) // Symbol/Special by text
        }
    }

    /**
     * Apply [s] to [a] and reduce any grounded-operator sub-expression that became computable
     * after substitution (bottom-up, so `(VecN String (+ $x 1))` with `$x=1` becomes
     * `(VecN String 2)`). A non-operator head, wrong arity, or a non-numeric operand leaves the
     * sub-expression inert — matching hyperon's "unreduced unless computable" rule.
     */
    private fun applySubst(a: Atom, s: Map<String, Atom>): Atom {
        val w = walk(a, s)
        return when (w) {
            is Expression -> reduceGroundedExpr(Expression(atoms = w.atoms.map { applySubst(it, s) }))
            else -> w
        }
    }

    /**
     * Resolve [a] through the substitution [s] (walk + substitute into sub-terms + grounded
     * reduction). Public entry point for the Form-2 pattern-`let` runtime helper
     * ([net.singularity.jetta.runtime.JettaProgram] `letMatch`), which unifies a pattern against a
     * value with [unify] and then reads each pattern variable's binding back out.
     */
    fun resolve(a: Atom, s: Map<String, Atom>): Atom = applySubst(a, s)

    /** Reduce a 3-element `(op x y)` grounded-op expression to its value, else return it as-is. */
    private fun reduceGroundedExpr(e: Expression): Atom {
        if (e.atoms.size != 3) return e
        val op = nameOf(e.atoms[0]) ?: return e
        return GroundedOps.apply(op, e.atoms[1], e.atoms[2]) ?: e
    }

    /**
     * Rename every variable in [t] to a globally-fresh name, consistently within [t]. Called on
     * each fetch of a variable-carrying `:` declaration so that variables from different
     * declarations — and different *uses* of the same declaration — never collide (e.g. the two
     * `Nil`s in `(Cons 5 (Cons 6 Nil))` each get their own `$a`).
     */
    private fun instantiate(t: Atom): Atom {
        val ren = HashMap<String, Variable>()
        fun go(a: Atom): Atom = when (a) {
            is Variable -> ren.getOrPut(a.name) { Variable("${a.name}#${freshCounter.incrementAndGet()}") }
            is Expression -> Expression(atoms = a.atoms.map { go(it) })
            else -> a
        }
        return go(t)
    }

    // --- arrow helpers --------------------------------------------------------------------

    private fun arrow(vararg ts: Atom): Atom = Expression(atoms = listOf(Special(Predefined.ARROW)) + ts.toList())
    private fun isArrow(t: Atom): Boolean =
        t is Expression && t.atoms.isNotEmpty() && nameOf(t.atoms[0]) == Predefined.ARROW
    private fun arrowParams(t: Atom): List<Atom> = (t as Expression).atoms.subList(1, t.atoms.size - 1)
    private fun arrowReturn(t: Atom): Atom = (t as Expression).atoms.last()

    // --- leaf types -----------------------------------------------------------------------

    private fun typeOfLiteral(g: Grounded<*>): Atom = when (g.value) {
        is Int, is Long, is Double -> NUMBER
        is String -> STRING
        is Boolean -> BOOL
        else -> UNDEF
    }

    /** Built-in operator arrow types: arithmetic → `Number`, comparisons → `Bool`. */
    private fun builtinType(op: String): Atom? = when (op) {
        "+", "-", "*", "/", "div", "%", "mod" -> arrow(NUMBER, NUMBER, NUMBER)
        "<", ">", "<=", ">=", "==", "!=" -> arrow(NUMBER, NUMBER, BOOL)
        else -> null
    }

    /** `(: subject T)` — a type fact declaring the type of [subject]. */
    private fun isTypeFact(a: Atom, subject: Atom): Boolean =
        a is Expression && a.atoms.size >= 3 &&
            nameOf(a.atoms[0]) == Predefined.TYPE && a.atoms[1] == subject

    /**
     * The type of a leaf name: a built-in operator arrow, else its `:`-declared type
     * (fresh-instantiated), else `%Undefined%` (gradual typing — an undeclared symbol is not an
     * error, it simply has no known type).
     */
    private fun typeOfSymbol(sym: Atom, atoms: List<Atom>): Atom {
        nameOf(sym)?.let { builtinType(it) }?.let { return it }
        val decl = atoms.firstOrNull { isTypeFact(it, sym) } as? Expression
        return decl?.atoms?.getOrNull(2)?.let { instantiate(it) } ?: UNDEF
    }

    // --- inference ------------------------------------------------------------------------

    /**
     * Infer the MeTTa type of [atom] against the `:` facts in [atoms], or `null` if ill-typed.
     * An application `(f a…)` looks up `f`'s arrow type, unifies each argument's inferred type
     * against the corresponding parameter, and substitutes the accumulated bindings into the
     * return type. Arity mismatch, a unification failure, or a non-arrow head applied to
     * arguments all mean ill-typed (`null`).
     */
    fun inferType(atom: Atom, atoms: List<Atom>): Atom? {
        val a = if (atom is BoundAtom) atom.atom else atom
        return when (a) {
            is Grounded<*> -> typeOfLiteral(a)
            is Variable -> UNDEF
            is Symbol, is Special -> typeOfSymbol(a, atoms)
            is Expression -> inferApp(a, atoms)
            else -> UNDEF
        }
    }

    private fun inferApp(a: Expression, atoms: List<Atom>): Atom? {
        if (a.atoms.isEmpty()) return UNDEF
        val head = a.atoms[0]
        val args = a.atoms.drop(1)

        // `=`-equality typing (d4): `(= x y)` is well-typed (type `%Undefined%`) iff both sides
        // have a type and those types unify; otherwise ill-typed. Equalities are of Atom type in
        // hyperon, which unifies with anything — modelled here as the `%Undefined%` result.
        if (nameOf(head) == Predefined.PATTERN && args.size == 2) {
            val tx = inferType(args[0], atoms) ?: return null
            val ty = inferType(args[1], atoms) ?: return null
            return if (unify(tx, ty, HashMap())) UNDEF else null
        }

        val headType = inferType(head, atoms) ?: return null
        if (!isArrow(headType)) {
            // A non-arrow head applied to no arguments is just that leaf's type; applied to
            // arguments it is ill-typed (you cannot apply a non-function).
            return if (args.isEmpty()) headType else null
        }
        val params = arrowParams(headType)
        val ret = arrowReturn(headType)
        if (params.size != args.size) return null
        val s = HashMap<String, Atom>()
        for (i in args.indices) {
            val at = inferType(args[i], atoms) ?: return null
            if (!unify(params[i], at, s)) return null
        }
        return applySubst(ret, s)
    }
}
