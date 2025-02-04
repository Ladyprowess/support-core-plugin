/*
 * The MIT License
 * 
 * Copyright (c) 2015 schristou88
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.support.impl;

import com.cloudbees.jenkins.support.AsyncResultCache;
import com.cloudbees.jenkins.support.api.Component;
import com.cloudbees.jenkins.support.api.Container;
import com.cloudbees.jenkins.support.api.Content;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Node;
import hudson.security.Permission;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import jenkins.security.MasterToSlaveCallable;

/**
 * @author schristou88
 */
@Extension
public class NetworkInterfaces extends Component {
    private final WeakHashMap<Node, String> networkInterfaceCache = new WeakHashMap<Node, String>();

    @NonNull
    @Override
    public Set<Permission> getRequiredPermissions() {
        return Collections.singleton(Jenkins.ADMINISTER);
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "Networking Interface";
    }

    @Override
    public void addContents(@NonNull Container result) {
        result.add(
                new Content("nodes/master/networkInterface.md") {
                    @Override
                    public void writeTo(OutputStream os) throws IOException {
                        os.write(getNetworkInterface(Jenkins.get()).getBytes(StandardCharsets.UTF_8));
                    }
                }
        );

        for (final Node node : Jenkins.getInstance().getNodes()) {
            result.add(
                    new Content("nodes/slave/{0}/networkInterface.md", node.getNodeName()) {
                        @Override
                        public void writeTo(OutputStream os) throws IOException {
                            os.write(getNetworkInterface(node).getBytes(StandardCharsets.UTF_8));
                        }
                    }
            );
        }
    }

    public String getNetworkInterface(Node node) throws IOException {
        return AsyncResultCache.get(node,
                networkInterfaceCache,
                new GetNetworkInterfaces(),
                "network interfaces",
                "N/A: No connection to node, or no cache.");
    }

    private static final class GetNetworkInterfaces extends MasterToSlaveCallable<String, RuntimeException> {

        public String call() {
            try {
                // we need to do this in parallel otherwise we can not complete in a reasonable time (each nic will take about 10ms and on windows we can easily have 60)
                List<NetworkInterface> nics = new ArrayList<>();
                {
                    Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                    while (networkInterfaces.hasMoreElements()) {
                        nics.add(networkInterfaces.nextElement());
                    }
                }
                
                return nics.parallelStream().map(n -> nicDetails(n)).collect(Collectors.joining("\n"));
            } catch (SocketException e) {
                return e.getMessage();
            }
        }

        public static String nicDetails(NetworkInterface ni) {
            StringBuilder sb = new StringBuilder();
            sb.append(" * Name ").append(ni.getDisplayName()).append('\n');

            try {
                byte[] hardwareAddress = ni.getHardwareAddress();

                // Do not have permissions or address does not exist
                if (hardwareAddress != null && hardwareAddress.length != 0) {
                    sb.append(" ** Hardware Address - ").append(Util.toHexString(hardwareAddress)).append("\n");
                }
                sb.append(" ** Index - ").append(ni.getIndex()).append('\n');
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress =  inetAddresses.nextElement();
                    sb.append(" ** InetAddress - ").append(inetAddress).append('\n');
                }
                sb.append(" ** MTU - ").append(ni.getMTU()).append('\n');
                sb.append(" ** Is Up - ").append(ni.isUp()).append('\n');
                sb.append(" ** Is Virtual - ").append(ni.isVirtual()).append('\n');
                sb.append(" ** Is Loopback - ").append(ni.isLoopback()).append('\n');
                sb.append(" ** Is Point to Point - ").append(ni.isPointToPoint()).append('\n');
                sb.append(" ** Supports multicast - ").append(ni.supportsMulticast()).append('\n');

                if (ni.getParent() != null) {
                    sb.append(" ** Child of - ").append(ni.getParent().getDisplayName()).append('\n');
                }
            } catch (SocketException e) {
                sb.append(e.getMessage()).append('\n');
            }
            return sb.toString();
        }

    }

}
