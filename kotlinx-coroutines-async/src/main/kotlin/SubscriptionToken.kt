package kotlinx.channels

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater

interface SubscriptionToken {

    object AlwaysActive : SubscriptionToken {
        override val isActive: Boolean
            get() = true

        override fun release(): Boolean {
            // do nothing
            return true
        }
    }

    /**
     * Can be read without synchronization
     */
    val isActive: Boolean

    /**
     * After this call [isActive] is may become false, and it will never be turned to true again.
     * Can be called without synchronization.
     *
     * returns the previous value of [isActive]
     */
    fun release(): Boolean
}

class SimpleSubscriptionToken : SubscriptionToken {

    private companion object {
        private val ACTIVE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(SimpleSubscriptionToken::class.java, "active")
    }

    @Volatile
    private var active = 1

    override val isActive: Boolean
        get() = active > 0

    override fun release(): Boolean {
        return ACTIVE_UPDATER.compareAndSet(this, 1, 0)
    }

}