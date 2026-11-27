package br.com.ia369.atendente.application;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializa atualizações concorrentes para o mesmo callId usando striped locks (sem vazamento de memória).
 */
@Component
public class CallProcessingLock {

    private static final int STRIPE_COUNT = 256;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    public CallProcessingLock() {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public <T> T execute(UUID callId, Supplier<T> action) {
        ReentrantLock lock = locks[(callId.hashCode() & 0x7FFFFFFF) % STRIPE_COUNT];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void run(UUID callId, Runnable action) {
        execute(callId, () -> {
            action.run();
            return null;
        });
    }
}

