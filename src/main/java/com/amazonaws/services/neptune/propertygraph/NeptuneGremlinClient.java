/*
Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License").
You may not use this file except in compliance with the License.
A copy of the License is located at
    http://www.apache.org/licenses/LICENSE-2.0
or in the "license" file accompanying this file. This file is distributed
on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
express or implied. See the License for the specific language governing
permissions and limitations under the License.
*/

package com.amazonaws.services.neptune.propertygraph;

import com.amazon.neptune.gremlin.driver.sigv4.ChainedSigV4PropertiesProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.neptune.auth.NeptuneNettyHttpSigV4Signer;
import com.amazonaws.neptune.auth.NeptuneSigV4SignerException;
import com.amazonaws.services.neptune.cluster.Cluster;
import com.amazonaws.services.neptune.cluster.ConcurrencyConfig;
import com.amazonaws.services.neptune.cluster.ConnectionConfig;
import com.amazonaws.services.neptune.propertygraph.io.SerializationConfig;
import org.apache.tinkerpop.gremlin.driver.*;
import org.apache.tinkerpop.gremlin.driver.Cluster.Builder;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NeptuneGremlinClient implements AutoCloseable {

    public static final int DEFAULT_BATCH_SIZE = 64;

    private static final Logger logger = LoggerFactory.getLogger(NeptuneGremlinClient.class);

    public static NeptuneGremlinClient create(Cluster cluster, SerializationConfig serializationConfig) {
        ConnectionConfig connectionConfig = cluster.connectionConfig();
        ConcurrencyConfig concurrencyConfig = cluster.concurrencyConfig();

        if (!connectionConfig.useSsl()){
            logger.warn("SSL has been disabled");
        }

        Builder builder = org.apache.tinkerpop.gremlin.driver.Cluster.build()
                .port(connectionConfig.port())
                .enableSsl(connectionConfig.useSsl())
                .maxWaitForConnection(10000);

        builder = serializationConfig.apply(builder);

        if (connectionConfig.useIamAuth()) {
            builder = configureIamSigning(builder, connectionConfig);
        }

        for (String endpoint : connectionConfig.endpoints()) {
            builder = builder.addContactPoint(endpoint);
        }

        int numberOfEndpoints = connectionConfig.endpoints().size();

        return new NeptuneGremlinClient(concurrencyConfig.applyTo(builder, numberOfEndpoints).create());
    }

    protected static Builder configureIamSigning (Builder builder, ConnectionConfig connectionConfig) {
        if (connectionConfig.isDirectConnection()) {
            builder = builder.handshakeInterceptor( r ->
                    {
                        try {
                            NeptuneNettyHttpSigV4Signer sigV4Signer =
                                    new NeptuneNettyHttpSigV4Signer(
                                            new ChainedSigV4PropertiesProvider().getSigV4Properties().getServiceRegion(),
                                            new DefaultAWSCredentialsProviderChain());
                            sigV4Signer.signRequest(r);
                        } catch (NeptuneSigV4SignerException e) {
                            throw new RuntimeException("Exception occurred while signing the request", e);
                        }
                        return r;
                    }
            );
        } else {
            builder = builder
                    // use the JAAS_ENTRY auth property to pass Host header info to the channelizer
                    .authProperties(new AuthProperties().with(AuthProperties.Property.JAAS_ENTRY, connectionConfig.handshakeRequestConfig().value()))
                    .channelizer(LBAwareSigV4WebSocketChannelizer.class);
        }
        return builder;
    }

    private final org.apache.tinkerpop.gremlin.driver.Cluster cluster;

    private NeptuneGremlinClient(org.apache.tinkerpop.gremlin.driver.Cluster cluster) {
        this.cluster = cluster;
    }

    public GraphTraversalSource newTraversalSource() {
        return AnonymousTraversalSource.traversal().withRemote(DriverRemoteConnection.using(cluster));
    }

    public QueryClient queryClient() {
        return new QueryClient(cluster.connect());
    }

    @Override
    public void close() throws Exception {
        if (cluster != null && !cluster.isClosed() && !cluster.isClosing()) {
            cluster.close();
        }
    }

    public static class QueryClient implements AutoCloseable {

        private final Client client;

        QueryClient(Client client) {
            this.client = client;
        }

        public ResultSet submit(String gremlin, Long timeoutMillis) {

            if (timeoutMillis != null){
                return client.submit(gremlin, RequestOptions.build().timeout(timeoutMillis).create());
            } else {
                return client.submit(gremlin);
            }
        }

        @Override
        public void close() throws Exception {
            client.close();
        }
    }

}
