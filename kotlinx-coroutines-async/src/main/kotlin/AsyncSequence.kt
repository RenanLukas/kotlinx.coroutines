package kotlinx.coroutines

import java.util.*
import java.util.concurrent.CompletableFuture

// https://github.com/dotnet/roslyn/issues/261

interface AsyncIterator<T> {
    fun hasNext(): CompletableFuture<Boolean>
    fun next(): T
}

interface AsyncSequence<T> {
    fun iterator(): AsyncIterator<T>
}

fun <T> asyncSequenceOf(vararg elements: T): AsyncSequence<T> {
    return asyncGenerate {
        for (element in elements) {
            yield(element)
        }
    }
}

fun </*@kotlin.internal.OnlyInputTypes*/ T> AsyncSequence<T>.contains(element: T): CompletableFuture<Boolean> {
    return async {
        val iterator = iterator()
        while (await(iterator.hasNext())) {
            if (iterator.next() == element) return@async true
        }
        false
    }
}


fun <T> AsyncSequence<T>.forEach(body: (T) -> Unit): CompletableFuture<Unit> {
    return async {
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

fun <T, R> AsyncSequence<T>.map(mapper: (T) -> R): AsyncSequence<R> {
    return asyncGenerate {
        val iterator = iterator()
        while (await(iterator.hasNext())) {
            yield(mapper(iterator.next()))
        }
    }
}

fun <T> AsyncSequence<T>.filter(predicate: (T) -> Boolean): AsyncSequence<T> {
    return asyncGenerate {
        val iterator = iterator()
        while (await(iterator.hasNext())) {
            val next = iterator.next()
            if (predicate(next)) {
                yield(next)
            }
        }
    }
}