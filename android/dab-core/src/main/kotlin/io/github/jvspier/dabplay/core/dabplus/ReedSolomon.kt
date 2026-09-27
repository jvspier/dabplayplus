package io.github.jvspier.dabplay.core.dabplus

/**
 * Reed-Solomon over GF(2^8) as used by DAB+ (ETSI TS 102 563): RS(120,110), shortened from RS(255,245),
 * field polynomial x^8+x^4+x^3+x^2+1 (0x11D), generator roots alpha^0..alpha^9 (fcr = 0), alpha = 2.
 * Codeword bytes are polynomial coefficients, highest degree first. Corrects up to nsym/2 = 5 byte errors.
 */
class ReedSolomon(private val nsym: Int = 10) {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) { exp[i] = x; log[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun div(a: Int, b: Int): Int { require(b != 0); return if (a == 0) 0 else exp[(log[a] + 255 - log[b]) % 255] }
    private fun pow(a: Int, p: Int) = exp[Math.floorMod(log[a] * p, 255)]
    private fun inv(a: Int) = exp[255 - log[a]]

    private fun polyEval(p: IntArray, x: Int): Int { var y = p[0]; for (i in 1 until p.size) y = mul(y, x) xor p[i]; return y }
    private fun polyScale(p: IntArray, x: Int) = IntArray(p.size) { mul(p[it], x) }
    private fun polyAdd(p: IntArray, q: IntArray): IntArray {
        val r = IntArray(maxOf(p.size, q.size))
        for (i in p.indices) r[i + r.size - p.size] = p[i]
        for (i in q.indices) r[i + r.size - q.size] = r[i + r.size - q.size] xor q[i]
        return r
    }
    private fun polyMul(p: IntArray, q: IntArray): IntArray {
        val r = IntArray(p.size + q.size - 1)
        for (j in q.indices) for (i in p.indices) r[i + j] = r[i + j] xor mul(p[i], q[j])
        return r
    }

    private val generator: IntArray by lazy {
        var g = intArrayOf(1)
        for (i in 0 until nsym) g = polyMul(g, intArrayOf(1, pow(2, i)))
        g
    }

    /** Systematic encoding: returns msg followed by nsym parity bytes. */
    fun encode(msg: ByteArray): ByteArray {
        val out = IntArray(msg.size + nsym)
        for (i in msg.indices) out[i] = msg[i].toInt() and 0xFF
        for (i in msg.indices) {
            val c = out[i]
            if (c != 0) for (j in 1 until generator.size) out[i + j] = out[i + j] xor mul(generator[j], c)
        }
        return ByteArray(out.size) { if (it < msg.size) msg[it] else out[it].toByte() }
    }

    /** Syndromes with a leading 0 (index shift 1), as in the classic formulation. */
    private fun syndromes(cw: IntArray) = IntArray(nsym + 1) { if (it == 0) 0 else polyEval(cw, pow(2, it - 1)) }

    fun check(cw: ByteArray): Boolean {
        val c = IntArray(cw.size) { cw[it].toInt() and 0xFF }
        return syndromes(c).all { it == 0 }
    }

    /** Corrects [cw] in place. Returns the number of corrected bytes, or -1 if uncorrectable. */
    fun decode(cw: ByteArray): Int {
        val msg = IntArray(cw.size) { cw[it].toInt() and 0xFF }
        val synd = syndromes(msg)
        if (synd.all { it == 0 }) return 0

        // Berlekamp-Massey: error locator polynomial
        var errLoc = intArrayOf(1)
        var oldLoc = intArrayOf(1)
        for (i in 0 until nsym) {
            val k = i + 1
            var delta = synd[k]
            for (j in 1 until errLoc.size) delta = delta xor mul(errLoc[errLoc.size - 1 - j], synd[k - j])
            oldLoc = oldLoc + 0
            if (delta != 0) {
                if (oldLoc.size > errLoc.size) {
                    val newLoc = polyScale(oldLoc, delta)
                    oldLoc = polyScale(errLoc, inv(delta))
                    errLoc = newLoc
                }
                errLoc = polyAdd(errLoc, polyScale(oldLoc, delta))
            }
        }
        var lead = 0
        while (lead < errLoc.size && errLoc[lead] == 0) lead++
        errLoc = errLoc.copyOfRange(lead, errLoc.size)
        val errs = errLoc.size - 1
        if (errs * 2 > nsym) return -1

        // Chien search over the (shortened) codeword
        val rev = errLoc.reversedArray()
        val errPos = ArrayList<Int>()
        for (i in msg.indices) if (polyEval(rev, pow(2, i)) == 0) errPos += msg.size - 1 - i
        if (errPos.size != errs) return -1

        // Forney: error magnitudes
        val coefPos = errPos.map { msg.size - 1 - it }
        var eLoc = intArrayOf(1)
        for (p in coefPos) eLoc = polyMul(eLoc, polyAdd(intArrayOf(1), intArrayOf(pow(2, p), 0)))
        val prod = polyMul(synd.reversedArray(), eLoc)
        val evalLen = eLoc.size          // remainder mod x^(errs+1): keep the lowest errs+1 coefficients
        val errEval = prod.copyOfRange(prod.size - evalLen, prod.size).reversedArray()
        val x = coefPos.map { pow(2, -(255 - it)) }
        val e = IntArray(msg.size)
        for ((i, xi) in x.withIndex()) {
            val xiInv = inv(xi)
            var locPrime = 1
            for ((j, xj) in x.withIndex()) if (j != i) locPrime = mul(locPrime, 1 xor mul(xiInv, xj))
            if (locPrime == 0) return -1
            val y = mul(xi, polyEval(errEval.reversedArray(), xiInv))   // fcr = 0: Xi^(1-fcr) = Xi
            e[errPos[i]] = div(y, locPrime)
        }
        for (i in msg.indices) msg[i] = msg[i] xor e[i]
        if (!syndromes(msg).all { it == 0 }) return -1
        for (i in msg.indices) cw[i] = msg[i].toByte()
        return errs
    }
}
