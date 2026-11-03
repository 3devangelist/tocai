package pt.tocai.app.rules

/**
 * Mudanças de "minutos antes" por evento ainda por confirmar (ex.: na escolha da manhã).
 * Valor = minutos escolhidos; lista vazia = voltar a "como os outros" (apagar os minutos próprios).
 */
object PendingOffsets {

    private const val MAX_OFFSET = 1440

    /** Normaliza uma escolha do diálogo: null (como os outros) -> []; senão sem repetidos, 0..1440, decrescente. */
    fun choice(offsets: List<Int>?): List<Int> =
        offsets.orEmpty().filter { it in 0..MAX_OFFSET }.distinct().sortedDescending()

    /** Minutos próprios efetivos: [base] (os gravados) com as mudanças [pending] por cima. */
    fun merge(base: Map<String, List<Int>>, pending: Map<String, List<Int>>): Map<String, List<Int>> {
        if (pending.isEmpty()) return base
        val out = LinkedHashMap(base)
        for ((key, list) in pending) {
            if (list.isEmpty()) out.remove(key) else out[key] = list
        }
        return out
    }

    /** Minutos próprios atuais de [key] tendo em conta as mudanças pendentes; null = como os outros. */
    fun current(key: String, saved: List<Int>?, pending: Map<String, List<Int>>): List<Int>? =
        if (key in pending) pending.getValue(key).ifEmpty { null } else saved

    /** Para guardar no Bundle: duas listas paralelas (chaves e CSV; "" = como os outros). */
    fun encode(pending: Map<String, List<Int>>): Pair<ArrayList<String>, ArrayList<String>> {
        val keys = ArrayList<String>(pending.size)
        val values = ArrayList<String>(pending.size)
        for ((k, v) in pending) {
            keys += k
            values += v.joinToString(",")
        }
        return keys to values
    }

    fun decode(keys: List<String>?, values: List<String>?): Map<String, List<Int>> {
        if (keys == null || values == null || keys.size != values.size) return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()
        for (i in keys.indices) {
            val parsed = values[i].split(',').mapNotNull { it.trim().toIntOrNull() }
            out[keys[i]] = choice(parsed)
        }
        return out
    }
}
