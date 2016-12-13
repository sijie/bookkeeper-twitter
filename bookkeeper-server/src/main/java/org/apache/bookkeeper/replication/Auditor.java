/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.replication;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.bookkeeper.client.BookieClusterManager;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeperAdmin;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerChecker;
import org.apache.bookkeeper.client.LedgerFragment;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.meta.LedgerManagerFactory;
import org.apache.bookkeeper.meta.LedgerManager;
import org.apache.bookkeeper.meta.LedgerUnderreplicationManager;
import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.Processor;
import org.apache.bookkeeper.replication.ReplicationException.BKAuditException;
import org.apache.bookkeeper.replication.ReplicationException.CompatibilityException;
import org.apache.bookkeeper.replication.ReplicationException.UnavailableException;
import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.Gauge;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.zookeeper.ZooKeeperClient;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.SettableFuture;

import static org.apache.bookkeeper.replication.ReplicationStats.PUBLISHED_UNDERREPLICATED_LEDGERS;
import static org.apache.bookkeeper.replication.ReplicationStats.UNDERREPLICATED_LEDGERS;
import static org.apache.bookkeeper.util.BookKeeperConstants.UNDER_REPLICATION_NODE;

/**
 * Auditor is a single entity in the entire Bookie cluster and will be watching
 * all the bookies under 'ledgerrootpath/available' zkpath. When any of the
 * bookie failed or disconnected from zk, he will start initiating the
 * re-replication activities by keeping all the corresponding ledgers of the
 * failed bookie as underreplicated znode in zk.
 */
public class Auditor {
    private static final Logger LOG = LoggerFactory.getLogger(Auditor.class);
    private final ServerConfiguration conf;
    private ZooKeeper zkc;
    private BookieClusterManager bcm;
    private BookieLedgerIndexer bookieLedgerIndexer;
    private LedgerManager ledgerManager;
    private LedgerUnderreplicationManager ledgerUnderreplicationManager;
    private final ScheduledExecutorService bookieCheckerExecutor;
    private final ScheduledExecutorService urLedgerCheckerExecutor;
    private final String bookieIdentifier;
    private Map<BookieSocketAddress, Set<Long>> ledgerDetails;
    private Set<Long> underreplicatedLedgers;

    // auditor stats
    private StatsLogger statsLogger;
    private Counter published_underreplicated_ledgers;


    public Auditor(final String bookieIdentifier, ServerConfiguration conf,
                   ZooKeeper zkc) throws UnavailableException {
        this(bookieIdentifier, conf, zkc, null, NullStatsLogger.INSTANCE);
    }

    public Auditor(final String bookieIdentifier, ServerConfiguration conf,
                   ZooKeeper zkc, BookieClusterManager bcm,
                   StatsLogger statsLogger) throws UnavailableException {
        this.conf = conf;
        this.bookieIdentifier = bookieIdentifier;
        this.zkc = zkc;
        try {
            if(bcm == null){
                BookKeeper bkc = new BookKeeper(new ClientConfiguration(conf), zkc);
                this.bcm = new BookieClusterManager(conf, bkc);
            } else {
                this.bcm = bcm;
            }
            LedgerManagerFactory ledgerManagerFactory = LedgerManagerFactory
                .newLedgerManagerFactory(conf, zkc);
            ledgerManager = ledgerManagerFactory.newLedgerManager();
            this.bookieLedgerIndexer = new BookieLedgerIndexer(ledgerManager);
            this.ledgerUnderreplicationManager = ledgerManagerFactory
                .newLedgerUnderreplicationManager();
        } catch (CompatibilityException ce) {
            throw new UnavailableException(
                "CompatibilityException while initializing Auditor", ce);
        } catch (IOException ioe) {
            throw new UnavailableException(
                "IOException while initializing Auditor", ioe);
        } catch (KeeperException ke) {
            throw new UnavailableException(
                "KeeperException while initializing Auditor", ke);
        } catch (InterruptedException ie) {
            throw new UnavailableException(
                "Interrupted while initializing Auditor", ie);
        }

        this.statsLogger = statsLogger;
        this.published_underreplicated_ledgers = this.statsLogger.getCounter(PUBLISHED_UNDERREPLICATED_LEDGERS);
        this.statsLogger.registerGauge(UNDERREPLICATED_LEDGERS, new Gauge<Number>() {
            @Override
            public Number getDefaultValue() {
                return 0;
            }

            @Override
            public Number getSample() {
                return underreplicatedLedgers == null ? 0 : underreplicatedLedgers.size();
            }
        });
        bookieCheckerExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AuditorCheckBookie-" + bookieIdentifier);
                t.setDaemon(true);
                return t;
            }
        });
        urLedgerCheckerExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AuditorCheckUlLedgers-" + bookieIdentifier);
                t.setDaemon(true);
                return t;
            }
        });

    }

    private void submitShutdownTask() {
        synchronized (this) {
            if (bookieCheckerExecutor.isShutdown()) {
                return;
            }
            bookieCheckerExecutor.submit(new Runnable() {
                    public void run() {
                        synchronized (Auditor.this) {
                            bookieCheckerExecutor.shutdown();
                        }
                    }
                });
        }
    }

    @VisibleForTesting
    synchronized Future<?> submitAuditTask() {
        if (bookieCheckerExecutor.isShutdown()) {
            SettableFuture<Void> f = SettableFuture.<Void>create();
            f.setException(new BKAuditException("Auditor shutting down"));
            return f;
        }
        return bookieCheckerExecutor.submit(new Runnable() {
                @SuppressWarnings("unchecked")
                public void run() {
                    try {
                        auditBookies();
                    } catch (BKException bke) {
                        LOG.error("Exception getting bookie list", bke);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOG.error("Interrupted while watching available bookies ", ie);
                    } catch (BKAuditException bke) {
                        LOG.error("Exception while watching available bookies", bke);
                    } catch (KeeperException ke) {
                        LOG.error("Exception reading bookie list", ke);
                    }
                }
            });
    }

    public void start() {
        LOG.info("I'm starting as Auditor Bookie. ID: {}", bookieIdentifier);
        // on startup watching available bookie and based on the
        // available bookies determining the bookie failures.
        synchronized (this) {
            if (bookieCheckerExecutor.isShutdown()) {
                return;
            }

            try {
                bcm.enableStats(this.statsLogger);
                bcm.start();
            } catch (BKException bke) {
                LOG.error("Couldn't get bookie list, exiting", bke);
                submitShutdownTask();
                return;
            }

            long ledgerCheckInterval = conf.getAuditorPeriodicCheckInterval();
            long bookieCheckInterval = conf.getAuditorPeriodicBookieCheckInterval();
            long urLedgerCheckInterval = conf.getAuditorURLedgerCheckInterval();

            if (ledgerCheckInterval > 0) {
                LOG.info("Auditor periodic ledger checking enabled"
                         + " 'auditorPeriodicCheckInterval' {} seconds", ledgerCheckInterval);
                bookieCheckerExecutor.scheduleAtFixedRate(LEDGER_CHECK, 0, ledgerCheckInterval, TimeUnit.SECONDS);
            } else {
                LOG.info("Periodic checking disabled");
            }

            if (bookieCheckInterval == 0) {
                LOG.info("Auditor periodic bookie checking disabled, running once check now anyhow");
                bookieCheckerExecutor.submit(BOOKIE_CHECK);
            } else {
                LOG.info("Auditor periodic bookie checking enabled"
                         + " 'auditorPeriodicBookieCheckInterval' {} seconds", bookieCheckInterval);
                bookieCheckerExecutor.scheduleAtFixedRate(BOOKIE_CHECK, 0, bookieCheckInterval, TimeUnit.SECONDS);
            }

            if (urLedgerCheckInterval > 0) {
                LOG.info("Auditor periodic underreplicated ledger checking enabled"
                        + " 'auditorPeriodUrLedgerCheckInterval' {} seconds", urLedgerCheckInterval);
                urLedgerCheckerExecutor.scheduleAtFixedRate(UR_LEDGER_CHECK, 0, urLedgerCheckInterval, TimeUnit.SECONDS);
            }
        }
    }

    private void waitIfLedgerReplicationDisabled() throws UnavailableException,
            InterruptedException {
        ReplicationEnableCb cb = new ReplicationEnableCb();
        if (!ledgerUnderreplicationManager.isLedgerReplicationEnabled()) {
            LOG.info("Ledger Auto Rereplication is disabled. Wait...");
            ledgerUnderreplicationManager.notifyLedgerReplicationEnabled(cb);
            cb.await();
        }
    }

    @SuppressWarnings("unchecked")
    private void auditBookies()
            throws BKAuditException, KeeperException,
            InterruptedException, BKException {
        LOG.info("Auditing bookies.");
        try {
            waitIfLedgerReplicationDisabled();
        } catch (UnavailableException ue) {
            LOG.error("Underreplication unavailable, skipping audit."
                      + "Will retry after a period");
            return;
        }

        // get leger to bookie map
        ledgerDetails = generateBookie2LedgersIndex();
        try {
            if (!ledgerUnderreplicationManager.isLedgerReplicationEnabled()) {
                // TODO: discard this run will introduce more traffic to zookeeper, we should just wait.
                // has been disabled while we were generating the index
                // discard this run, and schedule a new one
                bookieCheckerExecutor.submit(BOOKIE_CHECK);
                return;
            }
        } catch (UnavailableException ue) {
            LOG.error("Underreplication unavailable, skipping audit."
                      + "Will retry after a period");
            return;
        }

        // find failed bookies
        Set<BookieSocketAddress> lostBookies = findLostBookies(ledgerDetails.keySet());
        // reset the counter
        this.published_underreplicated_ledgers.clear();
        // publish suspected ledgers if any
        if (lostBookies.size() > 0) {
            LOG.info("Failed bookies : {}", lostBookies);
            handleLostBookies(lostBookies, ledgerDetails);
        } else {
            LOG.info("No bookie is suspected to be failed.");
        }
    }

    public Set<BookieSocketAddress> findLostBookies(Set<BookieSocketAddress> bookiesFromLedgers) throws BKException {
        Set<BookieSocketAddress> lostBookies = new HashSet<BookieSocketAddress>();
        Set<BookieSocketAddress> staleBookies = bcm.fetchStaleBookies();
        Set<BookieSocketAddress> activeBookies = bcm.getActiveBookies();
        lostBookies.addAll(staleBookies);
        lostBookies.addAll(Sets.difference(bookiesFromLedgers, activeBookies));
        bcm.lostBookiesChanged(lostBookies);
        return lostBookies;
    }

    private Map<BookieSocketAddress, Set<Long>> generateBookie2LedgersIndex()
            throws BKAuditException {
        return bookieLedgerIndexer.getBookieToLedgerIndex();
    }

    private void handleLostBookies(Collection<BookieSocketAddress> lostBookies,
            Map<BookieSocketAddress, Set<Long>> ledgerDetails) throws BKAuditException,
            InterruptedException {
        LOG.info("Following are the failed bookies: " + lostBookies
                + " and searching its ledgers for re-replication");

        for (BookieSocketAddress bookieIP : lostBookies) {
            // identify all the ledgers in bookieIP and publishing these ledgers
            // as under-replicated.
            publishSuspectedLedgers(bookieIP.toString(), ledgerDetails.get(bookieIP));
        }
    }

    private void publishSuspectedLedgers(String bookieIP, Set<Long> ledgers)
            throws InterruptedException, BKAuditException {

        if (null == ledgers || ledgers.size() == 0) {
            // there is no ledgers available for this bookie and just
            // ignoring the bookie failures
            LOG.info("There is no ledgers for the failed bookie: {}", bookieIP);
            return;
        }
        LOG.info("Following ledgers: {} of bookie: {} are identified as underreplicated",
                ledgers, bookieIP);
        for (Long ledgerId : ledgers) {
            try {
                ledgerUnderreplicationManager.markLedgerUnderreplicated(
                        ledgerId, bookieIP);
                this.published_underreplicated_ledgers.inc();
            } catch (UnavailableException ue) {
                throw new BKAuditException(
                        "Failed to publish underreplicated ledger: " + ledgerId
                                + " of bookie: " + bookieIP, ue);
            }
        }
    }

    /**
     * Process the result returned from checking a ledger
     */
    private class ProcessLostFragmentsCb implements GenericCallback<Set<LedgerFragment>> {
        final LedgerHandle lh;
        final AsyncCallback.VoidCallback callback;

        ProcessLostFragmentsCb(LedgerHandle lh, AsyncCallback.VoidCallback callback) {
            this.lh = lh;
            this.callback = callback;
        }

        public void operationComplete(int rc, Set<LedgerFragment> fragments) {
            try {
                if (rc == BKException.Code.OK) {
                    Set<BookieSocketAddress> bookies = Sets.newHashSet();
                    for (LedgerFragment f : fragments) {
                        bookies.addAll(f.getAddresses());
                    }
                    // TODO: publish ledger with failed bookies to reduce zookeeper accesses
                    for (BookieSocketAddress bookie : bookies) {
                        publishSuspectedLedgers(bookie.toString(),
                                                Sets.newHashSet(lh.getId()));
                    }
                }
                lh.close();
            } catch (BKException bke) {
                LOG.error("Error closing lh", bke);
                if (rc == BKException.Code.OK) {
                    rc = BKException.Code.ReplicationException;
                }
            } catch (InterruptedException ie) {
                LOG.error("Interrupted publishing suspected ledger", ie);
                Thread.currentThread().interrupt();
                if (rc == BKException.Code.OK) {
                    rc = BKException.Code.InterruptedException;
                }
            } catch (BKAuditException bkae) {
                LOG.error("Auditor exception publishing suspected ledger", bkae);
                if (rc == BKException.Code.OK) {
                    rc = BKException.Code.ReplicationException;
                }
            }

            callback.processResult(rc, null, null);
        }
    }

    /**
     * List all the ledgers and check them individually. This should not
     * be run very often.
     */
    void checkAllLedgers() throws BKAuditException, BKException,
            IOException, InterruptedException, KeeperException {
        ZooKeeper newzk = ZooKeeperClient.createConnectedZooKeeper(conf.getZkServers(), conf.getZkTimeout());

        final BookKeeper client = new BookKeeper(new ClientConfiguration(conf),
                                                 newzk);
        final BookKeeperAdmin admin = new BookKeeperAdmin(client);

        try {
            final LedgerChecker checker = new LedgerChecker(client);

            final AtomicInteger returnCode = new AtomicInteger(BKException.Code.OK);
            final CountDownLatch processDone = new CountDownLatch(1);

            Processor<Long> checkLedgersProcessor = new Processor<Long>() {
                @Override
                public void process(final Long ledgerId,
                                    final AsyncCallback.VoidCallback callback) {
                    try {
                        // TODO: blocking call in asynchronous path?? it doesn't trigger callback??
                        if (!ledgerUnderreplicationManager.isLedgerReplicationEnabled()) {
                            LOG.info("Ledger rereplication has been disabled, aborting periodic check");
                            processDone.countDown();
                            return;
                        }
                    } catch (ReplicationException.UnavailableException ue) {
                        LOG.error("Underreplication manager unavailable "
                                  +"running periodic check", ue);
                        processDone.countDown();
                        return;
                    }

                    LedgerHandle lh = null;
                    try {
                        lh = admin.openLedgerNoRecovery(ledgerId);
                        checker.checkLedger(lh, new ProcessLostFragmentsCb(lh, callback));
                    } catch (BKException.BKNoSuchLedgerExistsException bknsle) {
                        LOG.debug("Ledger was deleted before we could check it", bknsle);
                        callback.processResult(BKException.Code.OK,
                                               null, null);
                        return;
                    } catch (BKException bke) {
                        LOG.error("Couldn't open ledger " + ledgerId, bke);
                        callback.processResult(BKException.Code.BookieHandleNotAvailableException,
                                         null, null);
                        return;
                    } catch (InterruptedException ie) {
                        LOG.error("Interrupted opening ledger", ie);
                        Thread.currentThread().interrupt();
                        callback.processResult(BKException.Code.InterruptedException, null, null);
                        return;
                    } finally {
                        // TODO: potentially bad behavior since we shouldn't close ledger before all asynchronous check finished.
                        //       but it is correct right now, since lh.close() is a no-op in ReadOnlyLedgerHandle
                        if (lh != null) {
                            try {
                                lh.close();
                            } catch (BKException bke) {
                                LOG.warn("Couldn't close ledger " + ledgerId, bke);
                            } catch (InterruptedException ie) {
                                LOG.warn("Interrupted closing ledger " + ledgerId, ie);
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            };

            ledgerManager.asyncProcessLedgers(checkLedgersProcessor,
                    new AsyncCallback.VoidCallback() {
                        @Override
                        public void processResult(int rc, String s, Object obj) {
                            returnCode.set(rc);
                            processDone.countDown();
                        }
                    }, null, BKException.Code.OK, BKException.Code.ReadException);
            try {
                processDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BKAuditException(
                        "Exception while checking ledgers", e);
            }
            if (returnCode.get() != BKException.Code.OK) {
                throw BKException.create(returnCode.get());
            }
        } finally {
            admin.close();
            client.close();
            newzk.close();
        }
    }

    /**
     * Shutdown the auditor
     */
    public void shutdown() {
        LOG.info("Shutting down auditor");
        submitShutdownTask();

        try {
            while (!bookieCheckerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.warn("Executor not shutting down, interrupting");
                bookieCheckerExecutor.shutdownNow();
                urLedgerCheckerExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while shutting down auditor bookie", ie);
        }
    }

    /**
     * Return true if auditor is running otherwise return false
     *
     * @return auditor status
     */
    public boolean isRunning() {
        return !bookieCheckerExecutor.isShutdown();
    }

    private final Runnable LEDGER_CHECK = new Runnable() {
        public void run() {
            LOG.info("Running periodic check");

            try {
                if (!ledgerUnderreplicationManager.isLedgerReplicationEnabled()) {
                    LOG.info("Ledger replication disabled, skipping");
                    return;
                }
                checkAllLedgers();
            } catch (KeeperException ke) {
                LOG.error("Exception while running periodic check", ke);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while running periodic check", ie);
            } catch (BKAuditException bkae) {
                LOG.error("Exception while running periodic check", bkae);
            } catch (BKException bke) {
                LOG.error("Exception running periodic check", bke);
            } catch (IOException ioe) {
                LOG.error("I/O exception running periodic check", ioe);
            } catch (ReplicationException.UnavailableException ue) {
                LOG.error("Underreplication manager unavailable "
                    +"running periodic check", ue);
            }
        }
    };

    private final Runnable BOOKIE_CHECK = new Runnable() {
        public void run() {
            try {
                auditBookies();
            } catch (BKException bke) {
                LOG.error("Couldn't get bookie list, exiting", bke);
                submitShutdownTask();
            } catch (KeeperException ke) {
                LOG.error("Exception while watching available bookies", ke);
                submitShutdownTask();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while watching available bookies ", ie);
                submitShutdownTask();
            } catch (BKAuditException bke) {
                LOG.error("Exception while watching available bookies", bke);
                submitShutdownTask();
            }
        }
    };

    private final Runnable UR_LEDGER_CHECK = new Runnable() {
        @Override
        public void run() {
            try {
                List<String> urLedgerPaths = ledgerUnderreplicationManager.getAllUnderreplicatedLedgers();
                final Set<Long> ledgerIds = new HashSet<>();
                LOG.info("Find {} underreplicated ledgers.", urLedgerPaths.size());
                urLedgerPaths.forEach(path -> {
                    Long ledgerId = extracLedgerId(path);
                    if(ledgerId!=null){
                        ledgerIds.add(ledgerId);
                    }
                });
                underreplicatedLedgers = ledgerIds;
            } catch (UnavailableException e) {
                LOG.error("Underreplication manager unavailable while "
                    + "running periodic underreplicated ledger check", e);
            }
        }

        private Long extracLedgerId(String ledgerPath) {
            if (ledgerPath == null || ledgerPath.isEmpty()) {
                return null;
            }
            String regex = ".*/" + UNDER_REPLICATION_NODE + "/ledgers/(.+)$";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(ledgerPath);
            if (matcher.find()) {
                String hexLedgerPart = matcher.group(1).replaceAll("/", "");
                return Long.parseLong(hexLedgerPart, 16);
            }
            return null;
        }
    };


}
