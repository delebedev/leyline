package leyline.bridge.handoff

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only producer queue with source-aware, ABA-safe prefix reservations.
 *
 * A reservation identifies exact stamped entries, not only structurally equal
 * values. Appends after reservation remain available for the next consumer.
 */
internal class MonotonicPrefixQueue<T> : Iterable<T> {
    internal data class Reservation<T>(
        val sourceId: Long,
        val epoch: Long,
        val reservationId: Long,
        val entries: List<Entry<T>>,
    ) {
        val values: List<T> = entries.map(Entry<T>::value)
    }

    internal data class Entry<T>(
        val sequence: Long,
        val value: T,
    )

    private val sourceId = nextSourceId.getAndIncrement()
    private val epoch = AtomicLong()
    private val nextSequence = AtomicLong()
    private val queue = ConcurrentLinkedQueue<Entry<T>>()
    private val consumptionLock = Any()
    private val pendingReservations = ArrayDeque<Long>()
    private val reservations = mutableMapOf<Long, List<Entry<T>>>()
    private var nextReservationId = 0L

    fun add(value: T) {
        queue.add(Entry(nextSequence.getAndIncrement(), value))
    }

    fun reserve(): Reservation<T> =
        synchronized(consumptionLock) {
            val reservedSequences =
                reservations.values
                    .asSequence()
                    .flatten()
                    .mapTo(mutableSetOf(), Entry<T>::sequence)
            val entries =
                queue
                    .asSequence()
                    .dropWhile { it.sequence in reservedSequences }
                    .takeWhile { it.sequence !in reservedSequences }
                    .toList()
            val reservationId = nextReservationId++
            if (entries.isNotEmpty()) {
                reservations[reservationId] = entries
                pendingReservations.addInSequenceOrder(reservationId)
            }
            Reservation(sourceId, epoch.get(), reservationId, entries)
        }

    fun validate(reservation: Reservation<T>) {
        synchronized(consumptionLock) {
            check(
                reservation.sourceId == sourceId &&
                    reservation.epoch == epoch.get() &&
                    (
                        reservation.entries.isEmpty() ||
                            pendingReservations.firstOrNull() == reservation.reservationId
                    ) &&
                    queue.hasPrefix(reservation.entries),
            ) {
                "Reserved queue prefix changed before consumption"
            }
        }
    }

    fun consume(reservation: Reservation<T>) {
        synchronized(consumptionLock) {
            validate(reservation)
            reservation.entries.forEach { expected ->
                check(queue.poll() == expected) {
                    "Reserved queue prefix changed before consumption"
                }
            }
            if (reservation.entries.isNotEmpty()) {
                check(pendingReservations.removeFirst() == reservation.reservationId)
                reservations.remove(reservation.reservationId)
            }
        }
    }

    fun release(reservation: Reservation<T>) {
        synchronized(consumptionLock) {
            if (reservation.sourceId != sourceId || reservation.epoch != epoch.get()) return
            if (reservations.remove(reservation.reservationId) == null) return
            pendingReservations.remove(reservation.reservationId)
        }
    }

    fun clear() {
        synchronized(consumptionLock) {
            queue.clear()
            epoch.incrementAndGet()
            pendingReservations.clear()
            reservations.clear()
        }
    }

    fun isEmpty(): Boolean = queue.isEmpty()

    fun isNotEmpty(): Boolean = queue.isNotEmpty()

    val size: Int get() = queue.size

    fun toList(): List<T> = queue.map(Entry<T>::value)

    override fun iterator(): Iterator<T> = toList().iterator()

    private fun ConcurrentLinkedQueue<Entry<T>>.hasPrefix(expected: List<Entry<T>>): Boolean {
        val actual = iterator()
        return expected.all { item -> actual.hasNext() && actual.next() == item }
    }

    private fun ArrayDeque<Long>.addInSequenceOrder(reservationId: Long) {
        val ordered =
            (toList() + reservationId).sortedBy { id ->
                checkNotNull(reservations[id]).first().sequence
            }
        clear()
        addAll(ordered)
    }

    private companion object {
        val nextSourceId = AtomicLong(1)
    }
}
