package kotlinx.coroutines

import rx.Observable
import rx.subjects.AsyncSubject

/**
 * Run asynchronous computations based on [c] coroutine parameter
 *
 * Execution starts immediately within the 'asyncRx' call and it runs until
 * the first suspension point is reached ('*await' call for some Observable instance).
 * Remaining part of coroutine will be executed as it's passed into 'subscribe'
 * call of awaited Observable.
 *
 * @param c a coroutine representing reactive computations
 *
 * @return Observable with single value containing expression returned from coroutine
 */
fun <T> asyncRx(
        c: @Suspend() (() -> T)
): Observable<T> {
    val result: AsyncSubject<T> = AsyncSubject.create<T>()
    coroutine(c) {
        handleException {
            result.onError(it)
        }

        handleResult {
            result.onNext(it)
            result.onCompleted()
        }
    }.start()

    return result
}


suspend fun <V> Observable<V>.awaitFirst(): V = first().awaitOne()

suspend fun <V> Observable<V>.awaitLast(): V = last().awaitOne()

suspend fun <V> Observable<V>.awaitSingle(): V = single().awaitOne()

private suspend fun <V> Observable<V>.awaitOne(): V = runWithCurrentContinuation<V> { x ->
    subscribe(x::resume, x::resumeWithException)
}

suspend fun <V> Observable<V>.applyForEachAndAwait(
        block: (V) -> Unit
) = runWithCurrentContinuation<Unit> { x->
    this.subscribe(block, x::resumeWithException, { x.resume(Unit) })
}
