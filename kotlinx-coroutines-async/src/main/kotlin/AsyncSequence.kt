package kotlinx.coroutines

import java.util.*
import java.util.concurrent.CompletableFuture

interface AsyncIterator<T> {
    fun hasNext(): CompletableFuture<Boolean>
    fun next(): T
}

interface AsyncSequence<T> {
    fun iterator(): AsyncIterator<T>
}


fun <T> AsyncSequence<T>.forEach(body: (T) -> Unit): CompletableFuture<Unit> {
    return async<Unit> {
        val iterator = iterator()
        while (await(iterator.hasNext())) {
            body(iterator.next())
        }
    }
}

fun <T> AsyncSequence<T>.toList(expectedSize: Int = -1): CompletableFuture<List<T>> {
    return async {
        val result = if (expectedSize >= 0) ArrayList<T>(expectedSize) else ArrayList()
        val iterator = iterator()
        while (await(iterator.hasNext())) {
            result += iterator.next()
        }
        result.trimToSize()
        result
    }
}