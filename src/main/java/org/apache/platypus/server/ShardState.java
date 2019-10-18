package org.apache.platypus.server;

import org.apache.lucene.document.Document;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.facet.taxonomy.OrdinalsReader;
import org.apache.lucene.facet.taxonomy.SearcherTaxonomyManager;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NRTCachingDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.IOUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShardState implements Closeable {
    Logger logger = LoggerFactory.getLogger(ShardState.class);

    /**
     * {@link IndexState} for the index this shard belongs to
     */
    public final IndexState indexState;

    /**
     * Where Lucene's index is written
     */
    public final Path rootDir;

    /**
     * Which shard we are in this index
     */
    public final int shardOrd;

    /**
     * Base directory
     */
    public Directory origIndexDir;

    /**
     * Possibly NRTCachingDir wrap of origIndexDir
     */
    public Directory indexDir;

    /**
     * Taxonomy directory
     */
    Directory taxoDir;

    /**
     * Only non-null for "ordinary" (not replicated) index
     */
    public IndexWriter writer;

    /**
     * Only non-null if we are primary NRT replication index
     */
    // nocommit make private again, add methods to do stuff to it:
    public NRTPrimaryNode nrtPrimaryNode;

    /**
     * Only non-null if we are replica NRT replication index
     */
    // nocommit make private again, add methods to do stuff to it:
    public NRTReplicaNode nrtReplicaNode;

    /**
     * Taxonomy writer
     */
    public DirectoryTaxonomyWriter taxoWriter;

    /**
     * Internal IndexWriter used by DirectoryTaxonomyWriter;
     * we pull this out so we can .deleteUnusedFiles after
     * a snapshot is removed.
     */
    public IndexWriter taxoInternalWriter;

    /**
     * Maps snapshot gen -&gt; version.
     */
    public final Map<Long, Long> snapshotGenToVersion = new ConcurrentHashMap<Long, Long>();

    /**
     * Holds cached ordinals; doesn't use any RAM unless it's
     * actually used when a caller sets useOrdsCache=true.
     */
    public final Map<String, OrdinalsReader> ordsCache = new HashMap<String, OrdinalsReader>();

    /**
     * Enables lookup of previously used searchers, so
     * follow-on actions (next page, drill down/sideways/up,
     * etc.) use the same searcher as the original search, as
     * long as that searcher hasn't expired.
     */
    public final SearcherLifetimeManager slm = new SearcherLifetimeManager();

    /**
     * Indexes changes, and provides the live searcher,
     * possibly searching a specific generation.
     */
    private SearcherTaxonomyManager manager;

    private ReferenceManager<IndexSearcher> searcherManager;

    /**
     * Thread to periodically reopen the index.
     */
    public ControlledRealTimeReopenThread<SearcherTaxonomyManager.SearcherAndTaxonomy> reopenThread;

    /**
     * Used with NRT replication
     */
    public ControlledRealTimeReopenThread<IndexSearcher> reopenThreadPrimary;

    /**
     * Periodically wakes up and prunes old searchers from
     * slm.
     */
    Thread searcherPruningThread;

    /**
     * Holds the persistent snapshots
     */
    public PersistentSnapshotDeletionPolicy snapshots;

    /**
     * Holds the persistent taxonomy snapshots
     */
    public PersistentSnapshotDeletionPolicy taxoSnapshots;

    private final boolean doCreate;

    private final List<HostAndPort> replicas = new ArrayList<>();

    public final Map<IndexReader, Map<String, SortedSetDocValuesReaderState>> ssdvStates = new HashMap<>();

    public final String name;

    /**
     * Restarts the reopen thread (called when the live settings have changed).
     */
    public void restartReopenThread() {
        if (reopenThread != null) {
            reopenThread.close();
        }
        if (reopenThreadPrimary != null) {
            reopenThreadPrimary.close();
        }
        // nocommit sync
        if (nrtPrimaryNode != null) {
            assert manager == null;
            assert searcherManager != null;
            assert nrtReplicaNode == null;
            // nocommit how to get taxonomy back?
            reopenThreadPrimary = new ControlledRealTimeReopenThread<IndexSearcher>(writer, searcherManager, indexState.maxRefreshSec, indexState.minRefreshSec);
            reopenThreadPrimary.setName("LuceneNRTPrimaryReopen-" + name);
            reopenThreadPrimary.start();
        } else if (manager != null) {
            if (reopenThread != null) {
                reopenThread.close();
            }
            reopenThread = new ControlledRealTimeReopenThread<SearcherTaxonomyManager.SearcherAndTaxonomy>(writer, manager, indexState.maxRefreshSec, indexState.minRefreshSec);
            reopenThread.setName("LuceneNRTReopen-" + name);
            reopenThread.start();
        }
    }

    /**
     * True if this index is started.
     */
    public boolean isStarted() {
        return writer != null || nrtReplicaNode != null || nrtPrimaryNode != null;
    }

    public String getState() {
        //TODO FIX ME: should it be read-only, etc?
        return isStarted() ? "started" : "not started";
    }

    /** Delete this shard. */
    public void deleteShard() throws IOException {
        if (rootDir != null) {
            deleteAllFiles(rootDir);
        }
    }

    private static void deleteAllFiles(Path dir) throws IOException {
        if (Files.exists(dir)) {
            if (Files.isRegularFile(dir)) {
                Files.delete(dir);
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path path : stream) {
                        if (Files.isDirectory(path)) {
                            deleteAllFiles(path);
                        } else {
                            Files.delete(path);
                        }
                    }
                }
                Files.delete(dir);
            }
        }
    }

    public static class HostAndPort {
        public final InetAddress host;
        public final int port;

        public HostAndPort(InetAddress host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public ShardState(IndexState indexState, int shardOrd, boolean doCreate) {
        this.indexState = indexState;
        this.shardOrd = shardOrd;
        if (indexState.rootDir == null) {
            this.rootDir = null;
        } else {
            this.rootDir = indexState.rootDir.resolve("shard" + shardOrd);
        }
        this.name = indexState.name + ":" + shardOrd;
        this.doCreate = doCreate;
    }

    @Override
    public synchronized void close() throws IOException {
        logger.info(String.format("ShardState.close name= %s", name));

        commit();

        List<Closeable> closeables = new ArrayList<Closeable>();
        // nocommit catch exc & rollback:
        if (nrtPrimaryNode != null) {
            closeables.add(reopenThreadPrimary);
            closeables.add(searcherManager);
            // this closes writer:
            closeables.add(nrtPrimaryNode);
            closeables.add(slm);
            closeables.add(indexDir);
            closeables.add(taxoDir);
            nrtPrimaryNode = null;
        } else if (nrtReplicaNode != null) {
            closeables.add(reopenThreadPrimary);
            closeables.add(searcherManager);
            closeables.add(nrtReplicaNode);
            closeables.add(slm);
            closeables.add(indexDir);
            closeables.add(taxoDir);
            nrtPrimaryNode = null;
        } else if (writer != null) {
            closeables.add(reopenThread);
            closeables.add(manager);
            closeables.add(slm);
            closeables.add(writer);
            closeables.add(taxoWriter);
            closeables.add(indexDir);
            closeables.add(taxoDir);
            writer = null;
        }

        IOUtils.close(closeables);
    }

    /**
     * Commit all state.
     */
    public synchronized long commit() throws IOException {

        long gen;

        // nocommit this does nothing on replica?  make a failing test!
        if (writer != null) {
            // nocommit: two phase commit?
            if (taxoWriter != null) {
                taxoWriter.commit();
            }
            gen = writer.commit();
        } else {
            gen = -1;
        }

        return gen;
    }

    public SearcherTaxonomyManager.SearcherAndTaxonomy acquire() throws IOException {
        if (nrtPrimaryNode != null) {
            return new SearcherTaxonomyManager.SearcherAndTaxonomy(nrtPrimaryNode.getSearcherManager().acquire(), null);
        } else if (nrtReplicaNode != null) {
            return new SearcherTaxonomyManager.SearcherAndTaxonomy(nrtReplicaNode.getSearcherManager().acquire(), null);
        } else {
            return manager.acquire();
        }
    }

    public void release(SearcherTaxonomyManager.SearcherAndTaxonomy s) throws IOException {
        if (nrtPrimaryNode != null) {
            nrtPrimaryNode.getSearcherManager().release(s.searcher);
        } else if (nrtReplicaNode != null) {
            nrtReplicaNode.getSearcherManager().release(s.searcher);
        } else {
            manager.release(s);
        }
    }


    /**
     * Prunes stale searchers.
     */
    private class SearcherPruningThread extends Thread {
        private final CountDownLatch shutdownNow;

        /**
         * Sole constructor.
         */
        public SearcherPruningThread(CountDownLatch shutdownNow) {
            this.shutdownNow = shutdownNow;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    final SearcherLifetimeManager.Pruner byAge = new SearcherLifetimeManager.PruneByAge(indexState.maxSearcherAgeSec);
                    final Set<Long> snapshots = new HashSet<Long>(snapshotGenToVersion.values());
                    slm.prune(new SearcherLifetimeManager.Pruner() {
                        @Override
                        public boolean doPrune(double ageSec, IndexSearcher searcher) {
                            long version = ((DirectoryReader) searcher.getIndexReader()).getVersion();
                            if (snapshots.contains(version)) {
                                // Never time-out searcher for a snapshot:
                                return false;
                            } else {
                                return byAge.doPrune(ageSec, searcher);
                            }
                        }
                    });
                } catch (IOException ioe) {
                    // nocommit log
                }
                try {
                    if (shutdownNow.await(1, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
                if (writer == null) {
                    break;
                }
            }
        }
    }

    /**
     * Start the searcher pruning thread.
     */
    public void startSearcherPruningThread(CountDownLatch shutdownNow) {
        // nocommit make one thread in GlobalState
        if (searcherPruningThread == null) {
            searcherPruningThread = new SearcherPruningThread(shutdownNow);
            searcherPruningThread.setName("LuceneSearcherPruning-" + name);
            searcherPruningThread.start();
        }
    }

    /**
     * Start this shard as standalone (not primary nor replica)
     */
    public synchronized void start() throws Exception {

        if (isStarted()) {
            throw new IllegalStateException("index \"" + name + "\" was already started");
        }

        boolean success = false;

        try {

            if (indexState.saveLoadState == null) {
                indexState.initSaveLoadState();
            }

            Path indexDirFile;
            if (rootDir == null) {
                indexDirFile = null;
            } else {
                indexDirFile = rootDir.resolve("index");
            }
            origIndexDir = indexState.df.open(indexDirFile);

            // nocommit don't allow RAMDir
            // nocommit remove NRTCachingDir too?
            if ((origIndexDir instanceof MMapDirectory) == false) {
                double maxMergeSizeMB = indexState.getDoubleSetting("nrtCachingDirectoryMaxMergeSizeMB", 5.0);
                double maxSizeMB = indexState.getDoubleSetting("nrtCachingDirectoryMaxSizeMB", 60.0);
                if (maxMergeSizeMB > 0 && maxSizeMB > 0) {
                    indexDir = new NRTCachingDirectory(origIndexDir, maxMergeSizeMB, maxSizeMB);
                } else {
                    indexDir = origIndexDir;
                }
            } else {
                indexDir = origIndexDir;
            }

            // Rather than rely on IndexWriter/TaxonomyWriter to
            // figure out if an index is new or not by passing
            // CREATE_OR_APPEND (which can be dangerous), we
            // already know the intention from the app (whether
            // it called createIndex vs openIndex), so we make it
            // explicit here:
            IndexWriterConfig.OpenMode openMode;
            if (doCreate) {
                // nocommit shouldn't we set doCreate=false after we've done the create?  make test!
                openMode = IndexWriterConfig.OpenMode.CREATE;
            } else {
                openMode = IndexWriterConfig.OpenMode.APPEND;
            }

            Path taxoDirFile;
            if (rootDir == null) {
                taxoDirFile = null;
            } else {
                taxoDirFile = rootDir.resolve("taxonomy");
            }
            taxoDir = indexState.df.open(taxoDirFile);

            taxoSnapshots = new PersistentSnapshotDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy(),
                    taxoDir,
                    IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            taxoWriter = new DirectoryTaxonomyWriter(taxoDir, openMode) {
                @Override
                protected IndexWriterConfig createIndexWriterConfig(IndexWriterConfig.OpenMode openMode) {
                    IndexWriterConfig iwc = super.createIndexWriterConfig(openMode);
                    iwc.setIndexDeletionPolicy(taxoSnapshots);
                    return iwc;
                }

                @Override
                protected IndexWriter openIndexWriter(Directory dir, IndexWriterConfig iwc) throws IOException {
                    IndexWriter w = super.openIndexWriter(dir, iwc);
                    taxoInternalWriter = w;
                    return w;
                }
            };

            writer = new IndexWriter(indexDir, indexState.getIndexWriterConfig(openMode, origIndexDir, shardOrd));
            snapshots = (PersistentSnapshotDeletionPolicy) writer.getConfig().getIndexDeletionPolicy();

            // NOTE: must do this after writer, because SDP only
            // loads its commits after writer calls .onInit:
            for (IndexCommit c : snapshots.getSnapshots()) {
                long gen = c.getGeneration();
                SegmentInfos sis = SegmentInfos.readCommit(origIndexDir, IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS, "", gen));
                snapshotGenToVersion.put(c.getGeneration(), sis.getVersion());
            }

            // nocommit must also pull snapshots for taxoReader?

            manager = new SearcherTaxonomyManager(writer, true, new SearcherFactory() {
                @Override
                public IndexSearcher newSearcher(IndexReader r, IndexReader previousReader) throws IOException {
                    IndexSearcher searcher = new MyIndexSearcher(r);
                    searcher.setSimilarity(indexState.sim);
                    return searcher;
                }
            }, taxoWriter);

            restartReopenThread();

            startSearcherPruningThread(indexState.globalState.shutdownNow);
            success = true;
        } finally {
            if (!success) {
                IOUtils.closeWhileHandlingException(reopenThread,
                        manager,
                        writer,
                        taxoWriter,
                        slm,
                        indexDir,
                        taxoDir);
                writer = null;
            }
        }
    }

    /**
     * Start this index as primary, to NRT-replicate to replicas.  primaryGen should be incremented each time a new primary is promoted for
     * a given index.
     */
    public synchronized void startPrimary(long primaryGen) throws Exception {
        throw new UnsupportedOperationException("TODO: Support running server in Primary Mode ");
    }

    /**
     * Start this index as replica, pulling NRT changes from the specified primary
     */
    public synchronized void startReplica(InetSocketAddress primaryAddress, long primaryGen) throws Exception {
        throw new UnsupportedOperationException("TODO: Support running server in Replica Mode ");
    }

    public void maybeRefreshBlocking() throws IOException {
        if (nrtPrimaryNode != null) {
            nrtPrimaryNode.getSearcherManager().maybeRefreshBlocking();
        } else {
            manager.maybeRefreshBlocking();
        }
    }

    /**
     * Context to hold state for a single indexing request.
     */
    public static class IndexingContext {

        /**
         * How many chunks are still indexing.
         */
        public final Phaser inFlightChunks = new Phaser();

        /**
         * How many documents were added.
         */
        public final AtomicInteger addCount = new AtomicInteger();

        /**
         * Any indexing errors that occurred.
         */
        public final AtomicReference<Throwable> error = new AtomicReference<>();

        /**
         * Sole constructor.
         */
        public IndexingContext() {
        }

        /**
         * Only keeps the first error seen, and all bulk indexing stops after this.
         */
        public void setError(Throwable t) {
            //System.out.println("IndexingContext.setError:");
            //t.printStackTrace(System.out);
            error.compareAndSet(null, t);
        }

        /**
         * Returns the first exception hit while indexing, or null
         */
        public Throwable getError() {
            return error.get();
        }
    }

    /**
     * Job for a single block addDocuments call.
     */
    class AddDocumentsJob implements Callable<Long> {
        private final Term updateTerm;
        private final Iterable<Document> docs;
        private final IndexingContext ctx;

        // Position of this document in the bulk request:
        private final int index;

        /**
         * Sole constructor.
         */
        public AddDocumentsJob(int index, Term updateTerm, Iterable<Document> docs, IndexingContext ctx) {
            this.updateTerm = updateTerm;
            this.docs = docs;
            this.ctx = ctx;
            this.index = index;
        }

        @Override
        public Long call() throws Exception {
            long gen = -1;
            try {
                Iterable<Document> justDocs;
                if (indexState.hasFacets()) {
                    List<Document> justDocsList = new ArrayList<Document>();
                    for (Document doc : docs) {
                        // Translate any FacetFields:
                        justDocsList.add(indexState.facetsConfig.build(taxoWriter, doc));
                    }
                    justDocs = justDocsList;
                } else {
                    justDocs = docs;
                }

                //System.out.println(Thread.currentThread().getName() + ": add; " + docs);
                if (updateTerm == null) {
                    gen = writer.addDocuments(justDocs);
                } else {
                    gen = writer.updateDocuments(updateTerm, justDocs);
                }
            } catch (Exception e) {
                ctx.setError(new RuntimeException("error while indexing document " + index, e));
            } finally {
                ctx.addCount.incrementAndGet();
                //TODO: Semaphore to be acquired before submitting a job on its own thread
                //indexState.globalState.indexingJobsRunning.release();
            }

            return gen;
        }
    }


}