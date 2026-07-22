package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable

/**
 * A discrimination trie (à la Vampire / CeTTa) over the store's atoms, giving `match`
 * candidate retrieval in **O(query size)** instead of the O(store) full scan the lazy
 * [IndexerImpl] used to run per pattern. This is the "PE-based trie" step: a single
 * structural index maintained incrementally over the whole space, walked per query —
 * the inverse of building a per-pattern index by scanning.
 *
 * ## What it indexes
 * Each stored atom is flattened to a pre-order token stream ([tokenize]): an expression
 * becomes `ExpK(arity)` followed by its children's tokens; a `Symbol`/`Special`/grounded
 * value becomes a literal key; a **stored** `Variable` becomes the wildcard `varChild`
 * branch (a rule head like `(Implication (Evaluation (human $x)) …)` matches any query at
 * the `$x` position). The trie is a prefix tree over those token streams; a leaf holds the
 * store indices of every atom whose encoding ends there.
 *
 * ## Retrieval is an over-approximation — by design
 * [lookup] returns a **superset** of the truly-matching store atoms: it enforces structural
 * compatibility (head symbols, arities, grounded values, wildcard positions) but NOT
 * cross-position variable consistency (e.g. the same query var appearing twice, or a stored
 * `$x` used in two places having to agree). That exact check stays in
 * `IndexerImpl.matchAndCapture`, which runs only on the candidates. The sole hard invariant
 * here is **no false negatives**: any atom that could match MUST be returned. Unknown atom
 * kinds therefore degrade to a wildcard rather than being dropped.
 *
 * ## Wildcards, both directions
 *  - a **stored** variable ([varChild]) matches any one query subterm → on lookup, taking
 *    `varChild` consumes the whole query subterm at the current position (its token span);
 *  - a **query** variable matches any one stored subterm → on lookup, it advances the trie
 *    past exactly one complete stored subterm from every frontier node ([skipOneSubterm]).
 *
 * Not thread-safe; callers hold the single-threaded space.
 */
internal class DiscriminationTrie {

    private sealed interface TKey
    private data class SymK(val name: String) : TKey
    private data class SpcK(val value: Any?) : TKey
    private data class GndK(val value: Any?) : TKey
    private data class ExpK(val arity: Int) : TKey

    private class Node {
        val children = HashMap<TKey, Node>()
        var varChild: Node? = null
        var leaves: MutableList<Int>? = null
    }

    private val root = Node()

    fun clear() {
        root.children.clear()
        root.varChild = null
        root.leaves = null
    }

    // ── Encoding ────────────────────────────────────────────────────────────

    /** Pre-order token stream; `null` marks a wildcard (a stored/query [Variable]). */
    private fun tokenize(atom: Atom, out: MutableList<TKey?>) {
        when (atom) {
            is Symbol -> out.add(SymK(atom.name))
            is Special -> out.add(SpcK(atom.value))
            is Grounded<*> -> out.add(GndK(atom.value))
            is Variable -> out.add(null)
            is Expression -> {
                out.add(ExpK(atom.atoms.size))
                atom.atoms.forEach { tokenize(it, out) }
            }
            // Unknown kind → wildcard, so it can never be wrongly filtered out.
            else -> out.add(null)
        }
    }

    private fun tokenize(atom: Atom): List<TKey?> = ArrayList<TKey?>().also { tokenize(atom, it) }

    /** Token length of the subterm starting at [i] (1 for a leaf, arity-recursive for `ExpK`). */
    private fun span(toks: List<TKey?>, i: Int): Int {
        val t = toks[i]
        if (t is ExpK) {
            var p = i + 1
            repeat(t.arity) { p += span(toks, p) }
            return p - i
        }
        return 1
    }

    // ── Insertion ───────────────────────────────────────────────────────────

    fun insert(atom: Expression, storeIndex: Int) {
        var node = root
        for (t in tokenize(atom)) {
            node = if (t == null) {
                node.varChild ?: Node().also { node.varChild = it }
            } else {
                node.children.getOrPut(t) { Node() }
            }
        }
        (node.leaves ?: ArrayList<Int>().also { node.leaves = it }).add(storeIndex)
    }

    // ── Lookup ──────────────────────────────────────────────────────────────

    /** Store indices of every atom that could match [query], ascending (store order). */
    fun lookup(query: Expression): List<Int> {
        val toks = tokenize(query)
        val result = sortedSetOf<Int>()
        // (node, queryPos) already expanded. `Node` has no equals override, so a Pair uses
        // the node's identity — a hash collision between two distinct nodes is resolved by
        // identity equals, never by dropping one (which would be a false negative).
        val visited = HashSet<Pair<Node, Int>>()
        // Frontier entries pack (node, queryPos); different branches advance the query
        // pointer by different amounts (a stored-var wildcard skips a whole query subterm),
        // so positions are not uniform across the frontier.
        val work = ArrayDeque<Pair<Node, Int>>()
        work.addLast(root to 0)
        while (work.isNotEmpty()) {
            val entry = work.removeLast()
            val (node, pos) = entry
            if (!visited.add(entry)) continue
            if (pos == toks.size) {
                node.leaves?.let { result.addAll(it) }
                continue
            }
            val t = toks[pos]
            if (t == null) {
                // Query variable: matches any one stored subterm → skip one in the trie.
                for (n in skipOneSubterm(node)) work.addLast(n to (pos + 1))
            } else {
                // Exact structural branch.
                node.children[t]?.let { work.addLast(it to (pos + 1)) }
                // Stored variable matches the whole query subterm at this position.
                node.varChild?.let { work.addLast(it to (pos + span(toks, pos))) }
            }
        }
        return result.toList()
    }

    /** Continuation nodes reached by consuming exactly one complete stored subterm. */
    private fun skipOneSubterm(node: Node): List<Node> {
        val res = ArrayList<Node>()
        for ((k, child) in node.children) {
            if (k is ExpK) res.addAll(skipN(child, k.arity)) else res.add(child)
        }
        node.varChild?.let { res.add(it) }
        return res
    }

    /** Continuation nodes after consuming [count] complete stored subterms from [node]. */
    private fun skipN(node: Node, count: Int): List<Node> {
        var frontier = listOf(node)
        repeat(count) { frontier = frontier.flatMap { skipOneSubterm(it) } }
        return frontier
    }
}
