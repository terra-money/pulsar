/**
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
 */
package org.apache.pulsar.broker.systopic;

import com.google.common.collect.Sets;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.pulsar.broker.admin.impl.BrokersBase;
import org.apache.pulsar.broker.namespace.NamespaceService;
import org.apache.pulsar.broker.service.BrokerTestBase;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.common.events.EventsTopicNames;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.util.FutureUtil;
import org.powermock.reflect.Whitebox;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Test(groups = "broker")
public class PartitionedSystemTopicTest extends BrokerTestBase {

    static final int PARTITIONS = 5;

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        resetConfig();
        conf.setAllowAutoTopicCreation(false);
        conf.setAllowAutoTopicCreationType("partitioned");
        conf.setDefaultNumPartitions(PARTITIONS);
        conf.setManagedLedgerMaxEntriesPerLedger(1);
        conf.setBrokerDeleteInactiveTopicsEnabled(false);

        conf.setSystemTopicEnabled(true);
        conf.setTopicLevelPoliciesEnabled(true);

        super.baseSetup();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testAutoCreatedPartitionedSystemTopic() throws Exception {
        final String ns = "prop/ns-test";
        admin.namespaces().createNamespace(ns, 2);
        NamespaceEventsSystemTopicFactory systemTopicFactory = new NamespaceEventsSystemTopicFactory(pulsarClient);
        TopicPoliciesSystemTopicClient systemTopicClientForNamespace = systemTopicFactory
                .createTopicPoliciesSystemTopicClient(NamespaceName.get(ns));
        SystemTopicClient.Reader reader = systemTopicClientForNamespace.newReader();

        int partitions = admin.topics().getPartitionedTopicMetadata(
                String.format("persistent://%s/%s", ns, EventsTopicNames.NAMESPACE_EVENTS_LOCAL_NAME)).partitions;
        Assert.assertEquals(admin.topics().getPartitionedTopicList(ns).size(), 1);
        Assert.assertEquals(partitions, PARTITIONS);
        Assert.assertEquals(admin.topics().getList(ns).size(), PARTITIONS);
        reader.close();
    }

    @Test(timeOut = 1000 * 60)
    public void testConsumerCreationWhenEnablingTopicPolicy() throws Exception {
        String tenant = "tenant-" + RandomStringUtils.randomAlphabetic(4).toLowerCase();
        admin.tenants().createTenant(tenant, new TenantInfoImpl(Sets.newHashSet(), Sets.newHashSet("test")));
        int namespaceCount = 30;
        for (int i = 0; i < namespaceCount; i++) {
            String ns = tenant + "/ns-" + i;
            admin.namespaces().createNamespace(ns, 4);
            String topic = ns + "/t1";
            admin.topics().createPartitionedTopic(topic, 2);
        }

        List<CompletableFuture<Consumer<byte[]>>> futureList = new ArrayList<>();
        for (int i = 0; i < namespaceCount; i++) {
            String topic = tenant + "/ns-" + i + "/t1";
            futureList.add(pulsarClient.newConsumer()
                    .topic(topic)
                    .subscriptionName("sub")
                    .subscribeAsync());
        }
        FutureUtil.waitForAll(futureList).get();
        // Close all the consumers after check
        for (CompletableFuture<Consumer<byte[]>> consumer : futureList) {
            consumer.join().close();
        }
    }

    @Test
    public void testHealthCheckTopicNotOffload() throws Exception {
        final String heartbeatNamespace = NamespaceService.getHeartbeatNamespace(pulsar.getAdvertisedAddress(),
                pulsar.getConfig());
        TopicName topicName = TopicName.get("persistent://" + heartbeatNamespace
                        + "/" + BrokersBase.HEALTH_CHECK_TOPIC_SUFFIX);
        PersistentTopic persistentTopic = (PersistentTopic) pulsar.getBrokerService()
                .getTopic(topicName.toString(), true).get().get();
        admin.brokers().healthcheck();
        admin.topics().triggerOffload(topicName.toString(), MessageId.earliest);
        Assert.assertEquals(admin.topics().getStats(topicName.toString()).getMsgInCounter(), 1);
        Assert.assertEquals(persistentTopic.getManagedLedger().getOffloadedSize(), 0);
    }

    @Test
    private void testSetBacklogCausedCreatingProducerFailure() throws Exception {
        final String ns = "prop/ns-test";
        final String topic = ns + "/topic-1";

        admin.namespaces().createNamespace(ns, 2);
        admin.topics().createPartitionedTopic(String.format("persistent://%s", topic), 1);
        BacklogQuota quota = BacklogQuota.builder()
                .limitTime(2)
                .limitSize(-1)
                .retentionPolicy(BacklogQuota.RetentionPolicy.producer_exception)
                .build();
        admin.namespaces().setBacklogQuota(ns, quota, BacklogQuota.BacklogQuotaType.message_age);

        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .create();

        String partition0 = TopicName.get(String.format("persistent://%s", topic)).getPartition(0).toString();
        Optional<Topic> topicReference = pulsar.getBrokerService().getTopicReference(partition0);
        Assert.assertTrue(topicReference.isPresent());
        PersistentTopic persistentTopic = (PersistentTopic) topicReference.get();
        ManagedLedgerConfig config = persistentTopic.getManagedLedger().getConfig();
        config.setMinimumRolloverTime(1, TimeUnit.SECONDS);
        config.setMaximumRolloverTime(1, TimeUnit.SECONDS);
        persistentTopic.getManagedLedger().setConfig(config);
        Whitebox.invokeMethod(persistentTopic.getManagedLedger(), "updateLastLedgerCreatedTimeAndScheduleRolloverTask");
        String msg1 = "msg-1";
        producer.send(msg1);
        Thread.sleep(3 * 1000);

        Consumer<String> consumer2 = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub-1")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscriptionType(SubscriptionType.Key_Shared)
                .subscribe();

        Message<String> receive = consumer2.receive();
        consumer2.acknowledge(receive);

        Thread.sleep(3 * 1000);

        try {
            Producer<String> producerN = PulsarClient.builder()
                    .maxBackoffInterval(3, TimeUnit.SECONDS)
                    .operationTimeout(5, TimeUnit.SECONDS)
                    .serviceUrl(lookupUrl.toString()).connectionTimeout(2, TimeUnit.SECONDS).build()
                    .newProducer(Schema.STRING).topic(topic).sendTimeout(3, TimeUnit.SECONDS).create();
            Assert.assertTrue(producerN.isConnected());
            producerN.close();
        } catch (Exception ex) {
            Assert.fail("failed to create producer");
        }
    }
}