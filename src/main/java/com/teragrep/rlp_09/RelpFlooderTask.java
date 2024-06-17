/*
 * Teragrep RELP Flooder Library RLP_09
 * Copyright (C) 2024  Suomen Kanuuna Oy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://github.com/teragrep/teragrep/blob/main/LICENSE>.
 *
 *
 * Additional permission under GNU Affero General Public License version 3
 * section 7
 *
 * If you modify this Program, or any covered work, by linking or combining it
 * with other code, such other code is not for that reason alone subject to any
 * of the requirements of the GNU Affero GPL version 3 as long as this Program
 * is the same Program as licensed from Suomen Kanuuna Oy without any additional
 * modifications.
 *
 * Supplemented terms under GNU Affero General Public License version 3
 * section 7
 *
 * Origin of the software must be attributed to Suomen Kanuuna Oy. Any modified
 * versions must be marked as "Modified version of" The Program.
 *
 * Names of the licensors and authors may not be used for publicity purposes.
 *
 * No rights are granted for use of trade names, trademarks, or service marks
 * which are in The Program if any.
 *
 * Licensee must indemnify licensors and authors for any liability that these
 * contractual assumptions impose on licensors and authors.
 *
 * To the extent this program is licensed as part of the Commercial versions of
 * Teragrep, the applicable Commercial License may apply to this file if you as
 * a licensee so wish it.
 */

package com.teragrep.rlp_09;

import com.teragrep.rlp_03.client.Client;
import com.teragrep.rlp_03.client.ClientFactory;
import com.teragrep.rlp_03.frame.RelpFrame;
import com.teragrep.rlp_03.frame.RelpFrameFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.concurrent.*;

class RelpFlooderTask implements Callable<Object> {
    private long recordsSent = 0;
    private long bytesSent = 0;
    private boolean stayRunning = true;
    private final RelpFlooderConfig relpFlooderConfig;
    private final int threadId;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final Iterator<String> iterator;
    private final ClientFactory clientFactory;
    private final RelpFrameFactory relpFrameFactory;
    private static final Logger LOGGER = LoggerFactory.getLogger(RelpFlooderTask.class);
    RelpFlooderTask(int threadId, RelpFlooderConfig relpFlooderConfig, Iterator<String> iterator, ClientFactory clientFactory) throws RuntimeException {
        this.threadId = threadId;
        this.iterator = iterator;
        this.relpFlooderConfig = relpFlooderConfig;
        this.clientFactory = clientFactory;
        this.relpFrameFactory = new RelpFrameFactory();
    }

    @Override
    public Object call() {
        final String eventType = "syslog";
        final String ack = "200 OK";
        try (Client client = clientFactory.open(new InetSocketAddress(relpFlooderConfig.target, relpFlooderConfig.port)).get(relpFlooderConfig.connectTimeout, TimeUnit.SECONDS)) {
            CompletableFuture<RelpFrame> open = client.transmit(relpFrameFactory.create("open", "open"));
            try (RelpFrame openResponse = open.get()) {
                if (!openResponse.payload().toString().startsWith(ack)) {
                    throw new RuntimeException("Got unexpected response when opening connection: " + openResponse.payload().toString());
                }
            }

            while (stayRunning && iterator.hasNext()) {
                String record = iterator.next();
                CompletableFuture<RelpFrame> syslog = client.transmit(relpFrameFactory.create(eventType, record));
                if (relpFlooderConfig.waitForAcks) {
                    try (RelpFrame syslogResponse = syslog.get()) {
                        if (!syslogResponse.payload().toString().equals(ack)) {
                            throw new RuntimeException("Got unexpected when sending records: " + syslogResponse.payload().toString());
                        }
                    }
                }
                recordsSent++;
                bytesSent += record.length();
            }
            CompletableFuture<RelpFrame> close = client.transmit(relpFrameFactory.create("close", ""));
            close.get();
        } catch (TimeoutException ignored) {
            // Ignore Timeout if server has gone down and so on.
        } catch (RuntimeException | InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to run flooder: " + e.getMessage());
        }
        latch.countDown();
        return null;
    }

    public long getRecordsSent() {
        return recordsSent;
    }
    public long getBytesSent() {
        return bytesSent;
    }
    public int getThreadId() {
        return threadId;
    }
    public void stop()  {
        stayRunning=false;
        try {
            if(!latch.await(5L, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timed out waiting for thread to shut down");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
