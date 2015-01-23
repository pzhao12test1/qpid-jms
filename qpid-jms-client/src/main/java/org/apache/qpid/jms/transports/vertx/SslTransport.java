/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.transports.vertx;

import java.io.IOException;
import java.net.URI;

import org.apache.qpid.jms.transports.TransportListener;
import org.apache.qpid.jms.transports.TransportOptions;
import org.apache.qpid.jms.transports.TransportSslOptions;
import org.vertx.java.core.net.NetClient;

/**
 * Provides SSL configuration to the Vert.x NetClient object used by the underling
 * TCP based Transport.
 */
public class SslTransport extends TcpTransport {

    /**
     * Create a new transport instance
     *
     * @param remoteLocation
     *        the URI that defines the remote resource to connect to.
     * @param options
     *        the transport options used to configure the socket connection.
     */
    public SslTransport(URI remoteLocation, TransportOptions options) {
        super(null, remoteLocation, options);
    }

    /**
     * Create a new transport instance
     *
     * @param listener
     *        the TransportListener that will receive events from this Transport.
     * @param remoteLocation
     *        the URI that defines the remote resource to connect to.
     * @param options
     *        the transport options used to configure the socket connection.
     */
    public SslTransport(TransportListener listener, URI remoteLocation, TransportOptions options) {
        super(listener, remoteLocation, options);
    }

    @Override
    protected void configureNetClient(NetClient client, TransportOptions options) throws IOException {
        super.configureNetClient(client, options);

        client.setSSL(true);
        client.setKeyStorePath(getSslOptions().getKeyStoreLocation());
        client.setKeyStorePassword(getSslOptions().getKeyStorePassword());
        client.setTrustStorePath(getSslOptions().getTrustStoreLocation());
        client.setTrustStorePassword(getSslOptions().getTrustStorePassword());
    }

    private TransportSslOptions getSslOptions() {
        return (TransportSslOptions) options;
    }
}