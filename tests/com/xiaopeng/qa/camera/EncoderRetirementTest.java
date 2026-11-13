package com.xiaopeng.qa.camera;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests production admission, publication and actual thread-exit observation without Android. */
public final class EncoderRetirementTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
    }
    private static void finish(EncoderRetirement run) throws Exception {
        CountDownLatch cleaned = new CountDownLatch(1);
        CountDownLatch mayExit = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            run.nativeReleasedByCurrentWorker();
            cleaned.countDown();
            await(mayExit);
        });
        worker.start();
        await(cleaned);
        check(!EncoderRetirement.isFree(), "native cleanup alone does not free live worker");
        check(EncoderRetirement.acquire() == null, "cannot admit while cleanup worker lives");
        mayExit.countDown();
        worker.join();
        check(EncoderRetirement.isFree(), "actual worker exit frees admission");
    }
    public static void main(String[] args) throws Exception {
        EncoderRetirement first = EncoderRetirement.acquire();
        check(first != null, "initial admission");
        check(!first.publish(), "initializing encoder not publishable");
        check(first.initialized() && first.publish(), "publish ready encoder");
        check(first.retire(), "first retirement wins");
        check(!first.publish(), "retiring surface cannot be published again");
        check(!first.retire(), "failure plus stop retires once");
        check(!first.claimCleanup(true, true), "worker exit does not imply producer detach");
        check(EncoderRetirement.acquire() == null, "blocked producer prevents another instance");
        first.producerDetached();
        check(!first.claimCleanup(true, false), "detach does not imply codec worker exit");
        check(first.claimCleanup(true, true), "cleanup after both barriers");
        check(!first.claimCleanup(true, true), "duplicate callback cannot release twice");
        finish(first);

        EncoderRetirement partial = EncoderRetirement.acquire();
        CountDownLatch initEntered = new CountDownLatch(1);
        CountDownLatch finishInit = new CountDownLatch(1);
        AtomicBoolean lateReady = new AtomicBoolean(true);
        Thread initializer = new Thread(() -> {
            initEntered.countDown();
            await(finishInit); // Deterministic blocked-driver stand-in outside state monitor.
            lateReady.set(partial.initialized());
        });
        initializer.start();
        await(initEntered);
        check(partial.retire(), "stop remains available while init blocked");
        check(!partial.publish(), "no surface publication during blocked init");
        check(!partial.claimCleanup(false, true), "cannot clean partial resources before init exit");
        check(!EncoderRetirement.isFree(), "blocked init keeps process reservation");
        finishInit.countDown();
        initializer.join();
        check(!lateReady.get(), "stop rejects late initialization success");
        check(!partial.needsProducerBarrier(), "unpublished partial init needs no producer gate");
        check(partial.claimCleanup(true, true), "partial resources cleaned after init exit");
        first.producerDetached();
        check(EncoderRetirement.acquire() == null, "old detach cannot free new admission");
        finish(partial);

        for (int i = 0; i < 100; i++) {
            EncoderRetirement race = EncoderRetirement.acquire();
            check(race.initialized(), "race initialized");
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean published = new AtomicBoolean();
            Thread publisher = new Thread(() -> { await(start); published.set(race.publish()); });
            Thread stopper = new Thread(() -> { await(start); race.retire(); });
            publisher.start(); stopper.start(); start.countDown();
            publisher.join(); stopper.join();
            check(race.needsProducerBarrier() == published.get(), "publication and stop linearize");
            check(!race.publish(), "no republish after concurrent stop");
            if (published.get()) {
                check(!race.claimCleanup(true, true), "published race requires producer detach");
                race.producerDetached();
            }
            check(race.claimCleanup(true, true), "race cleanup admitted exactly once");
            finish(race);
        }
        System.out.println("EncoderRetirementTest: PASS (blocked init, thread exit, 100 publication/stop races)");
    }
}
