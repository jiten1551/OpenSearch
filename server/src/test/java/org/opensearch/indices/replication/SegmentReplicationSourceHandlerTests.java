/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.mockito.Mockito;
import org.opensearch.OpenSearchException;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardTestCase;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.indices.recovery.FileChunkWriter;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.indices.replication.common.CopyState;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.mock;

public class SegmentReplicationSourceHandlerTests extends IndexShardTestCase {

    private final DiscoveryNode localNode = new DiscoveryNode("local", buildNewFakeTransportAddress(), Version.CURRENT);
    private DiscoveryNode replicaDiscoveryNode;
    private IndexShard primary;
    private IndexShard replica;

    private FileChunkWriter chunkWriter;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        primary = newStartedShard(true);
        replica = newShard(primary.shardId(), false);
        recoverReplica(replica, primary, true);
        replicaDiscoveryNode = replica.recoveryState().getTargetNode();
    }

    @Override
    public void tearDown() throws Exception {
        closeShards(primary, replica);
        super.tearDown();
    }

    public void testSendFiles() throws IOException {
        chunkWriter = (fileMetadata, position, content, lastChunk, totalTranslogOps, listener) -> listener.onResponse(null);

        final ReplicationCheckpoint latestReplicationCheckpoint = primary.getLatestReplicationCheckpoint();
        final CopyState copyState = new CopyState(latestReplicationCheckpoint, primary);
        SegmentReplicationSourceHandler handler = new SegmentReplicationSourceHandler(
            localNode,
            chunkWriter,
            threadPool,
            copyState,
            5000,
            1
        );

        final List<StoreFileMetadata> expectedFiles = List.copyOf(copyState.getMetadataSnapshot().asMap().values());

        final GetSegmentFilesRequest getSegmentFilesRequest = new GetSegmentFilesRequest(
            1L,
            replica.routingEntry().allocationId().getId(),
            replicaDiscoveryNode,
            expectedFiles,
            latestReplicationCheckpoint
        );

        handler.sendFiles(getSegmentFilesRequest, new ActionListener<>() {
            @Override
            public void onResponse(GetSegmentFilesResponse getSegmentFilesResponse) {
                MatcherAssert.assertThat(getSegmentFilesResponse.files, Matchers.containsInAnyOrder(expectedFiles.toArray()));
            }

            @Override
            public void onFailure(Exception e) {
                Assert.fail();
            }
        });
    }

    public void testSendFiles_emptyRequest() throws IOException {
        chunkWriter = mock(FileChunkWriter.class);

        final ReplicationCheckpoint latestReplicationCheckpoint = primary.getLatestReplicationCheckpoint();
        final CopyState copyState = new CopyState(latestReplicationCheckpoint, primary);
        SegmentReplicationSourceHandler handler = new SegmentReplicationSourceHandler(
            localNode,
            chunkWriter,
            threadPool,
            copyState,
            5000,
            1
        );

        final GetSegmentFilesRequest getSegmentFilesRequest = new GetSegmentFilesRequest(
            1L,
            replica.routingEntry().allocationId().getId(),
            replicaDiscoveryNode,
            Collections.emptyList(),
            latestReplicationCheckpoint
        );

        handler.sendFiles(getSegmentFilesRequest, new ActionListener<>() {
            @Override
            public void onResponse(GetSegmentFilesResponse getSegmentFilesResponse) {
                assertTrue(getSegmentFilesResponse.files.isEmpty());
                Mockito.verifyNoInteractions(chunkWriter);
            }

            @Override
            public void onFailure(Exception e) {
                Assert.fail();
            }
        });
    }

    public void testSendFileFails() throws IOException {
        chunkWriter = (fileMetadata, position, content, lastChunk, totalTranslogOps, listener) -> listener.onFailure(
            new OpenSearchException("Test")
        );

        final ReplicationCheckpoint latestReplicationCheckpoint = primary.getLatestReplicationCheckpoint();
        final CopyState copyState = new CopyState(latestReplicationCheckpoint, primary);
        SegmentReplicationSourceHandler handler = new SegmentReplicationSourceHandler(
            localNode,
            chunkWriter,
            threadPool,
            copyState,
            5000,
            1
        );

        final List<StoreFileMetadata> expectedFiles = List.copyOf(copyState.getMetadataSnapshot().asMap().values());

        final GetSegmentFilesRequest getSegmentFilesRequest = new GetSegmentFilesRequest(
            1L,
            replica.routingEntry().allocationId().getId(),
            replicaDiscoveryNode,
            expectedFiles,
            latestReplicationCheckpoint
        );

        handler.sendFiles(getSegmentFilesRequest, new ActionListener<>() {
            @Override
            public void onResponse(GetSegmentFilesResponse getSegmentFilesResponse) {
                Assert.fail();
            }

            @Override
            public void onFailure(Exception e) {
                assertEquals(e.getClass(), OpenSearchException.class);
            }
        });
    }

    public void testReplicationAlreadyRunning() throws IOException {
        chunkWriter = mock(FileChunkWriter.class);

        final ReplicationCheckpoint latestReplicationCheckpoint = primary.getLatestReplicationCheckpoint();
        final CopyState copyState = new CopyState(latestReplicationCheckpoint, primary);
        SegmentReplicationSourceHandler handler = new SegmentReplicationSourceHandler(
            localNode,
            chunkWriter,
            threadPool,
            copyState,
            5000,
            1
        );

        final GetSegmentFilesRequest getSegmentFilesRequest = new GetSegmentFilesRequest(
            1L,
            replica.routingEntry().allocationId().getId(),
            replicaDiscoveryNode,
            Collections.emptyList(),
            latestReplicationCheckpoint
        );

        handler.sendFiles(getSegmentFilesRequest, mock(ActionListener.class));
        Assert.assertThrows(OpenSearchException.class, () -> { handler.sendFiles(getSegmentFilesRequest, mock(ActionListener.class)); });
    }
}
