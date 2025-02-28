package org.infinispan.scattered.impl;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.infinispan.commons.util.IntSet;
import org.infinispan.commons.util.IntSets;
import org.infinispan.configuration.cache.Configurations;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.RemoteMetadata;
import org.infinispan.container.versioning.SimpleClusteredVersion;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.persistence.spi.MarshallableEntry;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.impl.MapResponseCollector;
import org.infinispan.scattered.ScatteredStateProvider;
import org.infinispan.scattered.ScatteredVersionManager;
import org.infinispan.statetransfer.OutboundTransferTask;
import org.infinispan.statetransfer.StateChunk;
import org.infinispan.statetransfer.StateProviderImpl;
import org.infinispan.topology.CacheTopology;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.reactivestreams.Publisher;

import io.reactivex.rxjava3.core.Flowable;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class ScatteredStateProviderImpl extends StateProviderImpl implements ScatteredStateProvider {
   private static final Log log = LogFactory.getLog(ScatteredStateProviderImpl.class);

   @Inject protected ScatteredVersionManager svm;

   @Override
   public void start() {
      super.start();
   }

   @Override
   public CompletableFuture<Void> onTopologyUpdate(CacheTopology cacheTopology, boolean isRebalance) {
      if (isRebalance) {
         // TODO [rvansa]: do this only when member was lost
         return replicateAndInvalidate(cacheTopology);
      } else {
         return CompletableFutures.completedNull();
      }
   }

   // This method handles creating the backup copy and invalidation on other nodes for segments
   // that this node keeps from previous topology.
   private CompletableFuture<Void> replicateAndInvalidate(CacheTopology cacheTopology) {
      Address nextMember = getNextMember(cacheTopology);
      if (nextMember != null) {
         HashSet<Address> otherMembers = new HashSet<>(cacheTopology.getActualMembers());
         Address localAddress = rpcManager.getAddress();
         otherMembers.remove(localAddress);
         otherMembers.remove(nextMember);

         if (!cacheTopology.getCurrentCH().getMembers().contains(localAddress)) {
            log.trace("Local address is not a member of currentCH, returning");
            return CompletableFutures.completedNull();
         }

         IntSet oldSegments = IntSets.from(cacheTopology.getCurrentCH().getSegmentsForOwner(localAddress));
         oldSegments.retainAll(cacheTopology.getPendingCH().getSegmentsForOwner(localAddress));

         log.trace("Segments to replicate and invalidate: " + oldSegments);
         if (oldSegments.isEmpty()) {
            return CompletableFutures.completedNull();
         }

         // We pretend to do one extra invalidation at the beginning that finishes after we sent all chunks
         AtomicInteger outboundInvalidations = new AtomicInteger(1);
         CompletableFuture<Void> invalidationFuture = new CompletableFuture<>();
         OutboundTransferTask outboundTransferTask =
            new OutboundTransferTask(nextMember, oldSegments, cacheTopology.getCurrentCH().getNumSegments(), chunkSize,
                                     cacheTopology.getTopologyId(), keyPartitioner,
                                     chunks -> invalidateChunks(chunks, otherMembers, outboundInvalidations,
                                                                invalidationFuture, cacheTopology),
                                     rpcManager, commandsFactory,
                                     timeout, cacheName, true, true);
         outboundTransferTask.execute(Flowable.concat(publishDataContainerEntries(oldSegments),
                                                      publishStoreEntries(oldSegments)))
                             .whenComplete((ignored, throwable) -> {
                                if (throwable != null) {
                                   logError(outboundTransferTask, throwable);
                                }
                                if (outboundInvalidations.decrementAndGet() == 0) {
                                   invalidationFuture.complete(null);
                                }
                             });
         return invalidationFuture;
      } else {
         return CompletableFutures.completedNull();
      }
   }

   private void invalidateChunks(Collection<StateChunk> stateChunks, Set<Address> otherMembers,
                                 AtomicInteger outboundInvalidations, CompletableFuture<Void> invalidationFuture,
                                 CacheTopology cacheTopology) {
      int numEntries = stateChunks.stream().mapToInt(chunk -> chunk.getCacheEntries().size()).sum();
      if (numEntries == 0) {
         log.tracef("Nothing to invalidate");
         return;
      }
      Object[] keys = new Object[numEntries];
      int[] topologyIds = new int[numEntries];
      long[] versions = new long[numEntries];
      int i = 0;
      for (StateChunk chunk : stateChunks) {
         for (InternalCacheEntry entry : chunk.getCacheEntries()) {
            // we have replicated the non-versioned entries but we won't invalidate them elsewhere
            if (entry.getMetadata() != null && entry.getMetadata().version() != null) {
               keys[i] = entry.getKey();
               SimpleClusteredVersion version = (SimpleClusteredVersion) entry.getMetadata().version();
               topologyIds[i] = version.getTopologyId();
               versions[i] = version.getVersion();
               ++i;
            }
         }
      }
      if (log.isTraceEnabled()) {
         log.tracef("Invalidating %d entries from segments %s", numEntries,
                    stateChunks.stream().map(StateChunk::getSegmentId).collect(Collectors.toList()));
      }
      outboundInvalidations.incrementAndGet();
      rpcManager.invokeCommand(otherMembers,
                               commandsFactory.buildInvalidateVersionsCommand(cacheTopology.getTopologyId(), keys,
                                                                              topologyIds, versions, true),
                               MapResponseCollector.ignoreLeavers(otherMembers.size()),
                               rpcManager.getSyncRpcOptions())
                .whenComplete((r, t) -> {
         try {
            if (t != null) {
               log.failedInvalidatingRemoteCache(t);
            }
         } finally {
            if (outboundInvalidations.decrementAndGet() == 0) {
               invalidationFuture.complete(null);
            }
         }
      });
   }

   private Address getNextMember(CacheTopology cacheTopology) {
      Address myAddress = rpcManager.getAddress();
      List<Address> members = cacheTopology.getActualMembers();
      if (members.size() == 1) {
         return null;
      }
      Iterator<Address> it = members.iterator();
      while (it.hasNext()) {
         Address member = it.next();
         if (member.equals(myAddress)) {
            if (it.hasNext()) {
               return it.next();
            } else {
               return members.get(0);
            }
         }
      }
      throw new IllegalStateException();
   }

   @Override
   public void startKeysTransfer(IntSet segments, Address origin) {
      CacheTopology cacheTopology = distributionManager.getCacheTopology();
      OutboundTransferTask outboundTransferTask =
         new OutboundTransferTask(origin, segments, cacheTopology.getCurrentCH().getNumSegments(), chunkSize,
                                  cacheTopology.getTopologyId(), keyPartitioner, chunks -> {},
                                  rpcManager, commandsFactory,
                                  timeout, cacheName, true, false);
      addTransfer(outboundTransferTask);
      outboundTransferTask.execute(Flowable.concat(publishDataContainerKeys(segments), publishStoreKeys(segments)))
                          .whenComplete((ignored, throwable) -> {
         if (throwable != null) {
            logError(outboundTransferTask, throwable);
         }
         onTaskCompletion(outboundTransferTask);
      });
   }

   private Flowable<InternalCacheEntry<Object, Object>> publishDataContainerKeys(IntSet segments) {
      Address localAddress = rpcManager.getAddress();
      return Flowable.fromIterable(() -> dataContainer.iterator(segments))
                     .filter(ice -> ice.getMetadata() != null && ice.getMetadata().version() != null)
                     .map(ice -> entryFactory.create(ice.getKey(), null,
                                                     new RemoteMetadata(localAddress, ice.getMetadata().version())));

   }

   private Flowable<InternalCacheEntry<Object, Object>> publishStoreKeys(IntSet segments) {
      Address localAddress = rpcManager.getAddress();
      Publisher<MarshallableEntry<Object, Object>> loaderPublisher =
         persistenceManager.publishEntries(segments, k -> !dataContainer.containsKey(k), true, true,
                                           Configurations::isStateTransferStore);
      return Flowable.fromPublisher(loaderPublisher)
                     // We rely on MarshallableEntry implementations caching the unmarshalled metadata for performance
                     .filter(me -> me.getMetadata() != null && me.getMetadata().version() != null)
                     .map(me -> entryFactory.create(me.getKey(), null,
                                                    new RemoteMetadata(localAddress, me.getMetadata().version())));
   }

   @Override
   public CompletionStage<Void> confirmRevokedSegments(int topologyId) {
      return stateTransferLock.topologyFuture(topologyId);
   }
}
