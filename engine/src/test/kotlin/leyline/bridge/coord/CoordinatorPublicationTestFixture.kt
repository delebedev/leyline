package leyline.bridge.coord

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun shutdownWhilePublicationWaits(
    coordinator: MatchCutCoordinator,
    publish: () -> Unit,
): Throwable? {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    coordinator.beforePublicationLock = {
        entered.countDown()
        check(release.await(3, TimeUnit.SECONDS))
    }
    val failure = AtomicReference<Throwable>()
    val publisher =
        Thread {
            runCatching(publish).onFailure(failure::set)
        }.also(Thread::start)
    check(entered.await(3, TimeUnit.SECONDS))

    coordinator.shutdown()
    release.countDown()
    publisher.join(3_000)
    check(!publisher.isAlive)
    return failure.get()
}
