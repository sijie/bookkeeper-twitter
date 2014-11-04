package org.apache.bookkeeper.client;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultSpeculativeRequestExecutionPolicy implements SpeculativeRequestExecutionPolicy {
    private static final Logger LOG = LoggerFactory.getLogger(PendingReadOp.class);
    final int firstSpeculativeRequestTimeout;
    final int maxSpeculativeRequestTimeout;
    final int backoffMultiplier;

    public DefaultSpeculativeRequestExecutionPolicy(int firstSpeculativeRequestTimeout, int maxSpeculativeRequestTimeout, int backoffMultiplier) {
        this.firstSpeculativeRequestTimeout = firstSpeculativeRequestTimeout;
        this.maxSpeculativeRequestTimeout = maxSpeculativeRequestTimeout;
        this.backoffMultiplier = backoffMultiplier;

        // Prevent potential over flow
        if (maxSpeculativeRequestTimeout > (Integer.MAX_VALUE / backoffMultiplier)) {
            throw new IllegalArgumentException("Invalid values for maxSpeculativeRequestTimeout and backoffMultiplier");
        }
    }

    /**
     * Initialize the speculative request execution policy
     *
     * @param scheduler The scheduler service to issue the speculative request
     * @param requestExectuor The executor is used to issue the actual speculative requests
     */
    @Override
    public void initiateSpeculativeRequest(final ScheduledExecutorService scheduler, final SpeculativeRequestExectuor requestExecutor) {
        scheduleSpeculativeRead(scheduler, requestExecutor, firstSpeculativeRequestTimeout);
    }

    private void scheduleSpeculativeRead(final ScheduledExecutorService scheduler,
                                         final SpeculativeRequestExectuor requestExecutor,
                                         final long speculativeRequestTimeout) {
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    ListenableFuture<Boolean> issueNextRequest = requestExecutor.issueSpeculativeRequest();
                    Futures.addCallback(issueNextRequest, new FutureCallback<Boolean>() {
                        // we want this handler to run immediately after we push the big red button!
                        public void onSuccess(Boolean issueNextRequest) {
                            if (issueNextRequest) {
                                scheduleSpeculativeRead(scheduler, requestExecutor, Math.min(maxSpeculativeRequestTimeout,
                                    speculativeRequestTimeout * backoffMultiplier));
                            } else {
                                if(LOG.isTraceEnabled()) {
                                    LOG.trace("Stopped issuing speculative requests for {}, " +
                                        "speculativeReadTimeout = {}", requestExecutor, speculativeRequestTimeout);
                                }
                            }
                        }

                        public void onFailure(Throwable thrown) {
                            LOG.warn("Failed to issue speculative request for {}, speculativeReadTimeout = {} : ",
                                new Object[] { requestExecutor, speculativeRequestTimeout, thrown });
                        }
                    });
                }
            }, speculativeRequestTimeout, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException re) {
            LOG.warn("Failed to schedule speculative request for {}, speculativeReadTimeout = {} : ",
                new Object[] { requestExecutor, speculativeRequestTimeout, re });
        }
    }
}
